package text.zhet.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.InterstitialAd
import com.google.android.gms.ads.MobileAds
import com.google.cloud.translate.Translate
import com.google.cloud.translate.TranslateOptions
import com.google.cloud.translate.Translation
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.text.FirebaseVisionCloudTextRecognizerOptions
import com.google.firebase.ml.vision.text.FirebaseVisionText
import kotlinx.android.synthetic.main.activity_main.*
import text.zhet.BuildConfig
import text.zhet.R
import text.zhet.model.Language
import java.util.*
import kotlin.collections.ArrayList


class MainActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener, View.OnTouchListener {

    companion object {
        const val PERMISSION_REQUEST = 1
        const val ACTIVITY_REQUEST_CODE_IMAGE_CAPTURE = 2
        const val ACTIVITY_REQUEST_CODE_RECOGNIZE_SPEECH = 3
    }

    private lateinit var translateService: Translate
    private var srcText: String? = null
    private var targetLanguageCode: String = Locale.getDefault().language
    private lateinit var srcLanguageCode: String
    private var shouldCall = false
    private lateinit var supportedLanguages: MutableList<com.google.cloud.translate.Language>
    private var targetSpinnerSelection: Int? = null

    private var ad: InterstitialAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        MobileAds.initialize(this, getString(R.string.app_id))
        startCam()
        ad = InterstitialAd(this)
        ad?.adUnitId = getString(R.string.int_id)
        ad?.loadAd(AdRequest.Builder().build())
        adView.loadAd(AdRequest.Builder().build())
    }

    override fun onBackPressed() {
        super.onBackPressed()
        ad?.show()
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo != null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSION_REQUEST -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    startCam()
                } else {
                    finish()
                }
                return
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_shoot -> startCam()
            R.id.action_translate -> {
                val string = edtSrc.text.toString()
                if (srcText == null || string != srcText) {
                    srcText = string
                    prgBar.visibility = VISIBLE
                    Thread { translate(string, null) }.start()
                }
            }
            R.id.action_mic -> speechToText()

        }
        return super.onOptionsItemSelected(item)
    }

    private fun speechToText() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.translate_what))
        try {
            startActivityForResult(intent, ACTIVITY_REQUEST_CODE_RECOGNIZE_SPEECH)
        } catch (a: ActivityNotFoundException) {
            a.printStackTrace()
        }
    }

    private fun startCam() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, ACTIVITY_REQUEST_CODE_IMAGE_CAPTURE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            prgBar.visibility = VISIBLE
            when (requestCode) {
                ACTIVITY_REQUEST_CODE_IMAGE_CAPTURE -> {
                    val bitmap = data?.extras?.get("data") as Bitmap
                    rec(bitmap)
                }
                ACTIVITY_REQUEST_CODE_RECOGNIZE_SPEECH -> {
                    srcText = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
                    edtSrc.setText(srcText)
                    Thread { translate(srcText, null) }.start()
                }
            }
            ad?.show()
            ad?.adListener = object : AdListener() {
                override fun onAdClosed() {
                    ad?.loadAd(AdRequest.Builder().build())
                }
            }
        } else {
            prgBar.visibility = GONE
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    private fun rec(bitmap: Bitmap) {
        if (!isNetworkConnected()) {
            Toast.makeText(this, R.string.app_needs_inet_conn, LENGTH_LONG).show()
            prgBar.visibility = GONE
            return
        }
        val options = FirebaseVisionCloudTextRecognizerOptions.Builder().build()

        val image = FirebaseVisionImage.fromBitmap(bitmap)
        FirebaseVision.getInstance().getCloudTextRecognizer(options)
                .processImage(image).addOnSuccessListener {
                    process(it, bitmap)
                }
                .addOnFailureListener {
                    it.printStackTrace()
                }
    }

    private fun process(cloudText: FirebaseVisionText?, bitmap: Bitmap) {
        if (cloudText == null) {
            Toast.makeText(this, R.string.could_not_process_pic, Toast.LENGTH_LONG).show()
            changeViewStates(bitmap)
        } else {
            srcText = cloudText.text
            Thread { translate(srcText, bitmap) }.start()
        }
    }

    private fun changeViewStates(bitmap: Bitmap) {
        img.setImageBitmap(bitmap)
        prgBar.visibility = GONE
    }

    private fun translate(string: String?, bitmap: Bitmap?) {
        if (!isNetworkConnected()) {
            Toast.makeText(this, R.string.app_needs_inet_conn, LENGTH_LONG).show()
            return
        }
        val instance = TranslateOptions.newBuilder().setApiKey(BuildConfig.KEY).build()

        translateService = instance.service
        supportedLanguages = translateService
                .listSupportedLanguages(Translate.LanguageListOption.targetLanguage(Locale.getDefault().language))
        val languageNames = ArrayList<Language>()
        val split = string?.split(" ")
        val reduce = if (split?.size!! > 1) split.reduce { s1, s2 -> if (s1.length > s2.length) s1 else s2 } else split[0]
        val detection = translateService
                .detect(Regex("[^A-Za-z0-9 ]").replace(reduce, ""))
        srcLanguageCode = detection.language
        var srcSpinnerSelection = 0


        supportedLanguages.forEachIndexed { index, it ->
            if (it.code == detection.language) {
                srcSpinnerSelection = index
            }

            if (targetSpinnerSelection == null && Locale.getDefault().language == Locale(it.code).language) {
                targetSpinnerSelection = index
            }
            languageNames.add(Language(it.name.capitalize(), it.code))
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val translation = translateService
                .translate(string,
                        Translate.TranslateOption.targetLanguage(targetLanguageCode))

        runOnUiThread {
            changeViewStates(adapter, string, translation, bitmap, srcSpinnerSelection, targetSpinnerSelection)
        }
    }

    private fun changeViewStates(adapter: ArrayAdapter<Language>, string: String?, translation: Translation, bitmap: Bitmap?, srcSpinnerSelection: Int,
                                 targetSpinnerSelection: Int?) {
        src_spinner.adapter = adapter
        target_spinner.adapter = adapter
        src_spinner.setSelection(srcSpinnerSelection)
        target_spinner.setSelection(targetSpinnerSelection!!)
        prgBar.visibility = GONE
        edtSrc.setText(string)
        tv_target.text = translation.translatedText
        if (bitmap != null) img.setImageBitmap(bitmap)
        src_spinner.onItemSelectedListener = this
        target_spinner.onItemSelectedListener = this
        src_spinner.setOnTouchListener(this)
        target_spinner.setOnTouchListener(this)
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        shouldCall = true
        v?.performClick()
        return true
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {}

    override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        val viewId = parent.id
        if (shouldCall) {
            prgBar.visibility = VISIBLE
            srcText = edtSrc.text.toString()
            var translatedTxt: String? = null
            Thread {
                when (viewId) {
                    R.id.src_spinner -> {
                        val oldSrcLangCode = srcLanguageCode
                        srcLanguageCode = (parent.adapter.getItem(position) as Language).code
                        if (srcLanguageCode == targetLanguageCode) {
                            target_spinner.post {
                                target_spinner.setSelection(getIndexByLanguage(oldSrcLangCode))
                            }
                            targetLanguageCode = oldSrcLangCode
                            srcText = tv_target.text.toString()
                            edtSrc.setText(srcText)
                        }
                        translatedTxt = translateService.translate(srcText, Translate.TranslateOption
                                .targetLanguage(targetLanguageCode), Translate.TranslateOption.sourceLanguage(srcLanguageCode)).translatedText
                    }
                    R.id.target_spinner -> {
                        targetLanguageCode = (parent.adapter.getItem(position) as Language).code
                        if (targetLanguageCode == srcLanguageCode) {
                            translatedTxt = edtSrc.text.toString()
                        } else {
                            translatedTxt = translateService.translate(srcText, Translate.TranslateOption
                                    .targetLanguage(targetLanguageCode), Translate.TranslateOption.sourceLanguage(srcLanguageCode)).translatedText
                        }
                        targetSpinnerSelection = position
                    }

                }
                runOnUiThread {
                    tv_target.text = translatedTxt
                    prgBar.visibility = GONE
                }
            }.start()

        }
    }

    private fun getIndexByLanguage(oldSrcLangCode: String): Int {
        supportedLanguages.forEachIndexed { index, language ->
            if (oldSrcLangCode == language.code) {
                return index
            }
        }
        return 0
    }
}
