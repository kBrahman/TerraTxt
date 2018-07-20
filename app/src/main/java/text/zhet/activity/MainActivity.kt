package text.zhet.activity

import android.Manifest.permission.CAMERA
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
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.SparseIntArray
import android.view.*
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.InterstitialAd
import com.google.android.gms.ads.MobileAds
import com.google.cloud.translate.Translate
import com.google.cloud.translate.TranslateOptions
import com.google.cloud.translate.Translation
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.cloud.FirebaseVisionCloudDetectorOptions
import com.google.firebase.ml.vision.cloud.text.FirebaseVisionCloudText
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import kotlinx.android.synthetic.main.activity_main.*
import text.zhet.BuildConfig
import text.zhet.R
import text.zhet.model.Language
import java.util.*
import kotlin.collections.ArrayList


class MainActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener, View.OnTouchListener {

    private val ORIENTATIONS = SparseIntArray()

    companion object {
        const val PERMISSION_REQUEST = 1
        const val ACTIVITY_REQUEST_CODE_IMAGE_CAPTURE = 2
        const val ACTIVITY_REQUEST_CODE_ECOGNIZE_SPEECH = 3
        private val TAG = MainActivity::class.java.simpleName
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

        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
        val permissions = arrayOf(CAMERA)
        if (!checkPermissions(permissions)) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST)
        } else {
            startCam()
        }
        ad = InterstitialAd(this)
        ad?.adUnitId = getString(R.string.int_id)
        ad?.loadAd(AdRequest.Builder().build())
    }

    override fun onBackPressed() {
        super.onBackPressed()
        ad?.show()
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo != null
    }

    private fun checkPermissions(permissions: Array<String>): Boolean {
        permissions.forEach {
            if (ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED) {
                return false
            }
        }
        return true
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
                val string = edt_src.text.toString()
                if (srcText == null || string != srcText) {
                    srcText = string
                    progress_bar.visibility = VISIBLE
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
            startActivityForResult(intent, ACTIVITY_REQUEST_CODE_ECOGNIZE_SPEECH)
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
            when (requestCode) {
                ACTIVITY_REQUEST_CODE_IMAGE_CAPTURE -> {
                    val bitmap = data?.extras?.get("data") as Bitmap
                    progress_bar.visibility = VISIBLE
                    rec(bitmap)
                }
                ACTIVITY_REQUEST_CODE_ECOGNIZE_SPEECH -> {
                    srcText = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
                    edt_src.setText(srcText)
                    progress_bar.visibility = VISIBLE
                    Thread { translate(srcText, null) }.start()
                }
            }
            ad?.show()
        } else {
            progress_bar.visibility = GONE
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
            return
        }
        val options = FirebaseVisionCloudDetectorOptions.Builder()
                .setModelType(FirebaseVisionCloudDetectorOptions.LATEST_MODEL)
                .setMaxResults(15)
                .build()

        val image = FirebaseVisionImage.fromBitmap(bitmap)
        FirebaseVision.getInstance().getVisionCloudTextDetector(options)
                .detectInImage(image).addOnSuccessListener {
                    process(it, bitmap)
                }
                .addOnFailureListener {
                    it.printStackTrace()
                }
    }

    private fun process(cloudText: FirebaseVisionCloudText?, bitmap: Bitmap) {
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
        progress_bar.visibility = GONE
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
        val detection = translateService.detect(string)
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
        progress_bar.visibility = GONE
        Log.i(TAG, "prpgBar should have disappeared")
        edt_src.setText(string)
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
        Log.i(TAG, "onItemSelected")
        val viewId = parent.id
        if (shouldCall) {
            progress_bar.visibility = VISIBLE
            srcText = edt_src.text.toString()
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
                            edt_src.setText(srcText)
                        }
                        translatedTxt = translateService.translate(srcText, Translate.TranslateOption
                                .targetLanguage(targetLanguageCode), Translate.TranslateOption.sourceLanguage(srcLanguageCode)).translatedText
                    }
                    R.id.target_spinner -> {
                        targetLanguageCode = (parent.adapter.getItem(position) as Language).code
                        if (targetLanguageCode == srcLanguageCode) {
                            Log.i(TAG, "target spinner=> targetLangCode=$targetLanguageCode, srcLangCode=$srcLanguageCode, edt text=${edt_src.text}")
                            translatedTxt = edt_src.text.toString()
                        } else {
                            translatedTxt = translateService.translate(srcText, Translate.TranslateOption
                                    .targetLanguage(targetLanguageCode), Translate.TranslateOption.sourceLanguage(srcLanguageCode)).translatedText
                        }
                        targetSpinnerSelection = position
                    }

                }
                runOnUiThread {
                    tv_target.text = translatedTxt
                    progress_bar.visibility = GONE
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
