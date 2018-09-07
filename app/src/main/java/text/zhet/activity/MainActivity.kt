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
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import com.android.billingclient.api.*
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


class MainActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener, View.OnTouchListener, PurchasesUpdatedListener {

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName
        private const val PERMISSION_REQUEST = 1
        private const val ACTIVITY_REQUEST_CODE_IMAGE_CAPTURE = 2
        private const val ACTIVITY_REQUEST_CODE_RECOGNIZE_SPEECH = 3
        private const val SKU_ID = "text.zhet.remove.ads"
    }

    private lateinit var translateService: Translate
    private var srcText: String? = null
    private var targetLanguageCode: String = Locale.getDefault().language
    private lateinit var srcLanguageCode: String
    private var shouldCall = false
    private lateinit var supportedLanguages: MutableList<com.google.cloud.translate.Language>
    private var targetSpinnerSelection: Int? = null
    private var client: BillingClient? = null
    private var removeAdsMenuItem: MenuItem? = null

    private var ad: InterstitialAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this, getString(R.string.app_id))
        if (!isNetworkConnected()) {
            setContentView(R.layout.no_internet)
        } else {
            init(null)
        }
    }

    fun init(v: View?) {
        if (!isNetworkConnected()) return
        setContentView(R.layout.activity_main)
        startCam()
        billing()
    }

    private fun isNetworkConnected() = (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo != null

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
                if (string.isNotBlank() && string != srcText) {
                    srcText = string
                    prgBar.visibility = VISIBLE
                    Thread { translate(string, null) }.start()
                }
            }
            R.id.action_mic -> speechToText()

        }
        return true
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

    private fun billing() {
        if (client == null) client = BillingClient.newBuilder(this).setListener(this).build()
        client?.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {}

            override fun onBillingSetupFinished(responseCode: Int) {
                if (responseCode == BillingClient.BillingResponse.OK) {
                    val purchases = client?.queryPurchases(BillingClient.SkuType.SUBS)
                    val purchasesList = purchases?.purchasesList
                    Log.i(TAG, purchasesList.toString())
                    if (purchasesList?.isEmpty()!! && client?.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS) == 0) {
                        removeAdsMenuItem?.isVisible = true
                        initAds()
                    }
                }
            }

        })
    }

    private fun initAds() {
        adView?.loadAd(AdRequest.Builder().build())
        ad = InterstitialAd(this)
        ad?.adUnitId = getString(R.string.int_id)
        ad?.loadAd(AdRequest.Builder().build())
    }

    override fun onPurchasesUpdated(responseCode: Int, purchases: MutableList<Purchase>?) {
        Log.i(TAG, "onPurchasesUpdated")
        if (responseCode == BillingClient.BillingResponse.OK && purchases != null) {
            Toast.makeText(this, R.string.thank_you, LENGTH_SHORT).show()
            adView.visibility = GONE
            ad = null
            removeAdsMenuItem?.isVisible = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        this.removeAdsMenuItem = menu.findItem(R.id.action_remove_ads)
        billing()
        return true
    }

    fun removeAds(item: MenuItem) {
        client?.launchBillingFlow(this, BillingFlowParams.newBuilder().setType(BillingClient.SkuType.SUBS).setSku(SKU_ID).build())
    }

    private fun rec(bitmap: Bitmap) {
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
        val instance = TranslateOptions.newBuilder().setApiKey(BuildConfig.KEY).build()

        translateService = instance.service
        supportedLanguages = translateService
                .listSupportedLanguages(Translate.LanguageListOption.targetLanguage(Locale.getDefault().language))
        val languageNames = ArrayList<Language>()
        val detection = translateService
                .detect(string)
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
        tvTarget.text = translation.translatedText
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
                            srcText = tvTarget.text.toString()
                            edtSrc.setText(srcText)
                        }
                        translatedTxt = translateService.translate(srcText, Translate.TranslateOption
                                .targetLanguage(targetLanguageCode), Translate.TranslateOption.sourceLanguage(srcLanguageCode)).translatedText
                    }
                    R.id.target_spinner -> {
                        targetLanguageCode = (parent.adapter.getItem(position) as Language).code
                        translatedTxt = if (targetLanguageCode == srcLanguageCode) {
                            edtSrc.text.toString()
                        } else {
                            translateService.translate(srcText, Translate.TranslateOption
                                    .targetLanguage(targetLanguageCode), Translate.TranslateOption.sourceLanguage(srcLanguageCode)).translatedText
                        }
                        targetSpinnerSelection = position
                    }

                }
                runOnUiThread {
                    tvTarget.text = translatedTxt
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
