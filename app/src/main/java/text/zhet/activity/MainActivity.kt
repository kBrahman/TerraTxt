package text.zhet.activity

import android.Manifest.permission.CAMERA
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.SparseIntArray
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
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
import text.zhet.R
import text.zhet.model.Language
import java.util.*
import kotlin.collections.ArrayList


class MainActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener {

    private val ORIENTATIONS = SparseIntArray()

    companion object {
        const val PERMISSION_REQUEST = 1
        const val ACTIVITY_REQUEST_CODE = 2
    }

    private lateinit var translateService: Translate
    private lateinit var srcText: String
    private var callCounter = 0
    private var targetLanguageCode: String = Locale.getDefault().language
    private lateinit var srcLanguageCode: String
    private lateinit var interstitialAd: InterstitialAd

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
                if (string != srcText) {
                    srcText = string
                    progress_bar.visibility = VISIBLE
                    Thread { translate(string, null) }.start()
                }
            }

        }
        return super.onOptionsItemSelected(item)
    }

    private fun startCam() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, ACTIVITY_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val build = AdRequest.Builder().build()
        adView.loadAd(build)
        interstitialAd = InterstitialAd(this)
        interstitialAd.adUnitId = getString(R.string.int_id)
        interstitialAd.loadAd(build)
//        if (interstitialAd.isLoaded) {
//            interstitialAd.show()
//        }
        if (resultCode == Activity.RESULT_OK) {
            val bitmap = data?.extras?.get("data") as Bitmap
            progress_bar.visibility = VISIBLE
            rec(bitmap)
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

    private fun translate(string: String, bitmap: Bitmap?) {
        val instance = TranslateOptions.newBuilder().setApiKey("AIzaSyDYpWXlXuXsSxqH1Cp2-JxrjBKrhYMfLlw").build()

        translateService = instance.service
        val supportedLanguages = translateService
                .listSupportedLanguages(Translate.LanguageListOption.targetLanguage(Locale.getDefault().language))
        val languageNames = ArrayList<Language>()
        val detection = translateService.detect(string)
        srcLanguageCode = detection.language
        var srcSpinnerSelection = 0
        var targetSpinnerSelection = 0

        supportedLanguages.forEachIndexed { index, it ->
            if (it.code == detection.language) {
                srcSpinnerSelection = index
            }

            if (Locale.getDefault().language == Locale(it.code).language) {
                targetSpinnerSelection = index
            }
            languageNames.add(Language(it.name.capitalize(), it.code))
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val translation = translateService.translate(string,
                Translate.TranslateOption.targetLanguage(targetLanguageCode))
        runOnUiThread({
            changeViewStates(adapter, string, translation, bitmap, srcSpinnerSelection, targetSpinnerSelection)
        })
    }

    private fun changeViewStates(adapter: ArrayAdapter<Language>, string: String, translation: Translation, bitmap: Bitmap?, srcSpinnerSelection: Int, targetSpinnerSelection: Int) {
        src_spinner.adapter = adapter
        target_spinner.adapter = adapter
        src_spinner.setSelection(srcSpinnerSelection)
        target_spinner.setSelection(targetSpinnerSelection)
        progress_bar.visibility = GONE
        edt_src.setText(string)
        tv_target.text = translation.translatedText
        if (bitmap != null) img.setImageBitmap(bitmap)
        src_spinner.onItemSelectedListener = this
        target_spinner.onItemSelectedListener = this
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {}

    override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        if (callCounter > 1) {
            val viewId = parent.id
            progress_bar.visibility = VISIBLE
            when (viewId) {
                R.id.src_spinner -> {
                    Thread {
                        srcLanguageCode = (parent.adapter.getItem(position) as Language).code
                        val translation = translateService.translate(srcText, Translate.TranslateOption
                                .targetLanguage(targetLanguageCode), Translate.TranslateOption.sourceLanguage(srcLanguageCode))
                        runOnUiThread({
                            tv_target.text = translation.translatedText
                            progress_bar.visibility = GONE
                        })
                    }.start()
                }
                R.id.target_spinner -> {
                    Thread {
                        targetLanguageCode = (parent.adapter.getItem(position) as Language).code
                        val translation = translateService.translate(srcText, Translate.TranslateOption
                                .targetLanguage(targetLanguageCode), Translate.TranslateOption.sourceLanguage(srcLanguageCode))
                        runOnUiThread({
                            tv_target.text = translation.translatedText
                            progress_bar.visibility = GONE
                        })
                    }.start()
                }
            }
        }
        callCounter++
    }

}
