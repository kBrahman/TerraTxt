package text.zhet

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
import android.util.Log
import android.util.SparseIntArray
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.View.GONE
import android.widget.ArrayAdapter
import com.google.cloud.translate.Translate
import com.google.cloud.translate.TranslateOptions
import com.google.cloud.translate.Translation
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.cloud.FirebaseVisionCloudDetectorOptions
import com.google.firebase.ml.vision.cloud.text.FirebaseVisionCloudText
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.util.*


class MainActivity : AppCompatActivity() {

    private val ORIENTATIONS = SparseIntArray()

    companion object {
        val TAG: String = MainActivity::class.java.simpleName
        const val PERMISSION_REQUEST = 1
        const val ACTIVITY_REQUEST_CODE = 2;
    }


    private lateinit var file: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContentView(R.layout.activity_main)
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

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        startCam()
        return super.onOptionsItemSelected(item)
    }

    private fun startCam() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, ACTIVITY_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val bitmap = data?.extras?.get("data") as Bitmap
            Log.i(TAG, "${bitmap.height}, ${bitmap.width}")
            rec(bitmap)
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

    private fun process(cloudText: FirebaseVisionCloudText, bitmap: Bitmap) {
        val text = cloudText.text
        Log.i(TAG, "pages=>${cloudText.pages.size}")
        Thread { translate(text, bitmap) }.start()
    }

    private fun translate(string: String, bitmap: Bitmap) {
        val instance = TranslateOptions.newBuilder().setApiKey("AIzaSyDYpWXlXuXsSxqH1Cp2-JxrjBKrhYMfLlw").build()

        val translate = instance.service
        Log.i(TAG, "src=>$string")
        val supportedLanguages = translate
                .listSupportedLanguages(Translate.LanguageListOption.targetLanguage(Locale.getDefault().language))
        val languageNames = ArrayList<String>()
        val detection = translate.detect(string)
        var srcSpinnerSelection = 0
        var targetSpinnerSelection = 0
        supportedLanguages.forEachIndexed { index, it ->
            if (it.code == detection.language) {
                srcSpinnerSelection = index
            }

            if (Locale.getDefault().language == Locale(it.code).language) {
                targetSpinnerSelection = index
            }
            languageNames.add(it.name.capitalize())
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val translation = translate.translate(string,
                Translate.TranslateOption.targetLanguage(Locale.getDefault().language))
        Log.i(TAG, "tr=>${translation.translatedText}")
        runOnUiThread({
            changeUIElements(adapter, string, translation, bitmap, srcSpinnerSelection, targetSpinnerSelection)
        })
    }

    private fun changeUIElements(adapter: ArrayAdapter<String>, string: String, translation: Translation, bitmap: Bitmap, srcSpinnerSelection: Int, targetSpinnerSelection: Int) {
        src_spinner.adapter = adapter
        target_spinner.adapter = adapter
        src_spinner.setSelection(srcSpinnerSelection)
        target_spinner.setSelection(targetSpinnerSelection)
        progress_bar.visibility = GONE
        tv_src.text = string
        tv_target.text = translation.translatedText
        img.setImageBitmap(bitmap)
    }
}
