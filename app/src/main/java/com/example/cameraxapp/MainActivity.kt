package com.example.cameraxapp
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.content.ContentResolver
import android.provider.Settings
import android.provider.Settings.System

class MainActivity : AppCompatActivity(), PermissionManager.PermissionListener {
    private val tag = "MainActivity"

    // UI components
    private lateinit var previewView: PreviewView
    private lateinit var roiOverlay: ROIOverlay
    private lateinit var captureButton: Button
    private lateinit var flashButton: ImageButton
    private lateinit var switchButton: ImageButton
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var exposureSeekBar: SeekBar
    private lateinit var progressBar: ProgressBar
    private lateinit var resultLayout: LinearLayout
    private lateinit var resultImageView: ImageView
    private lateinit var readingTextView: TextView

    private lateinit var titleTextView: TextView
    private lateinit var serviceIdTextView: TextView
    private lateinit var valueTypeTextView: TextView
    private lateinit var resultServiceIdTextView: TextView
    private lateinit var resultValueTypeTextView: TextView

    private lateinit var saveButton: Button
    private lateinit var retakeButton: Button
    private lateinit var processButton: Button

    // Managers and detectors
    private lateinit var permissionManager: PermissionManager
    private lateinit var cameraManager: CameraManager
    private lateinit var meterDetector: MeterDetector

    // State variables
    private var currentCaptureResult: CameraManager.CaptureResult? = null
    private var currentMeterReading: String? = null
    private val inputNumber = StringBuilder()

    var tapCount = 0
    var editFlag = false
    var serviceId = "default_service"
    var valType = "default"
    var savedFileName="default"
    var meterReading="null"
    var imagePath="paht_not_found"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Inside your Activity class
        val isAutoRotateEnabled = isAutoRotateOn(contentResolver)


        if (isAutoRotateEnabled) {
            // Auto-rotation is on
            Log.d(tag, "Auto-rotation is enabled")
        } else {
            // Auto-rotation is off
            Log.d(tag, "Auto-rotation is disabled")
        }

        // Create an instance with your intent
        val dataHandler = IntentDataHandler(intent)

        serviceId = dataHandler.getServiceId()
        valType = dataHandler.getValType()
        savedFileName = serviceId + "_" + valType

        // Initialize UI components
        initializeViews()

        // Update UI with service ID and value type
        serviceIdTextView.text = serviceId
        valueTypeTextView.text = valType

        Log.d(tag, "ServiceID: $serviceId")
        Log.d(tag, "ValueType: $valType")

        resultServiceIdTextView.text = serviceId
        resultValueTypeTextView.text = valType

        // Initialize permission manager
        permissionManager = PermissionManager(this, this)

        // Initialize meter detector
        meterDetector = MeterDetector(this)

        // Request permissions
        permissionManager.requestPermissions()
    }


    // Method to check if auto-rotation is enabled
    fun isAutoRotateOn(contentResolver: ContentResolver): Boolean {
        return Settings.System.getInt(
            contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        ) == 1
    }

    private fun getVersionName(): String {
        return try {
            val packageManager = packageManager
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            "(Ver:${packageInfo.versionName})"
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            "Version not found"
        }
    }

    /**
     * Initialize UI components and set up listeners
     */
    private fun initializeViews() {
        // Camera preview components
        previewView = findViewById(R.id.viewFinder)
        roiOverlay = findViewById(R.id.roiOverlay)
        captureButton = findViewById(R.id.captureButton)
        flashButton = findViewById(R.id.flashButton)
        switchButton = findViewById(R.id.switchButton)
        zoomSeekBar = findViewById(R.id.zoomSeekBar)
        exposureSeekBar = findViewById(R.id.exposureSeekBar)
        progressBar = findViewById(R.id.progressBar)

        // Service ID and Value Type displays
        serviceIdTextView = findViewById(R.id.serviceIdTextView)
        valueTypeTextView = findViewById(R.id.valueTypeTextView)

        // Result view components
        resultLayout = findViewById(R.id.resultLayout)
        resultImageView = findViewById(R.id.resultImageView)
        readingTextView = findViewById(R.id.readingTextView)
        resultServiceIdTextView = findViewById(R.id.resultServiceIdTextView)
        resultValueTypeTextView = findViewById(R.id.resultValueTypeTextView)
        saveButton = findViewById(R.id.saveButton)
        retakeButton = findViewById(R.id.retakeButton)
        processButton = findViewById(R.id.processButton)
        titleTextView = findViewById(R.id.titleTextView)

        // Set up button click listeners
        captureButton.setOnClickListener {
            captureImage(valType)
        }

        flashButton.setOnClickListener {
            toggleFlash()
        }

        switchButton.setOnClickListener {
            switchCamera()
        }


        saveButton.setOnClickListener {
            saveCurrentImage()
            sendBackvalues()

        }

        readingTextView.setOnClickListener {

            val text = readingTextView.text.toString()
            if (text.toDoubleOrNull() != null || text == "No meter detected" || text == "Tap: 2 times" || text == "Tap: 1 time") {
                tapCount++
                Log.d(tag, "Tapped : $tapCount")

                if (tapCount == 3) {
                    editFlag = true
                    Log.d(tag, "Edit Flag set to : $editFlag")
                    showNumericBottomDialog()
                    tapCount = 0
                } else {
                    val remaining = 3 - tapCount
                    readingTextView.text = if (remaining == 1) "Tap: 1 time" else "Tap: $remaining times"
                }
            }

//            if (readingTextView.text.toString() == "No meter detected") {
//                editFlag = true
//            }
//
//            tapCount = tapCount + 1
//            if (tapCount == 3) {
//                showNumericBottomDialog()
//                tapCount = 0
//            } else {
//                readingTextView.text = buildString {
//                    append("Tap: ")
//                    append(3 - tapCount)
//                    append(" times")
//                }
//            }
        }

        retakeButton.setOnClickListener {
            showCameraView()
        }

        processButton.setOnClickListener {
            processCurrentImage()
        }



        // Set up zoom seekbar
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::cameraManager.isInitialized) {
                    val zoomLevel = progress / 100f
                    cameraManager.setZoom(zoomLevel)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Set up exposure seekbar
        exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::cameraManager.isInitialized) {
                   // cameraManager.setExposure(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        titleTextView.text = buildString {
            append("Ebilly OCR ")
            append(getVersionName())
        }
    }



    private fun sendBackvalues(){
        // Create JSON object with metadata
        val metadata = JSONObject().apply {
            put("meterReading", meterReading)
            put("filename", imagePath)
            put("isEdited", editFlag)
            // Any other metadata fields you want to include
        }

        Log.d(tag, "Metadata: $metadata")

        Log.d(tag, "Valtype: $valType")

        // Add value type specific extras and set result code
        when (valType) {
            "KWH" -> {

                setResult(OCR_KWH_RESULT_CODE, intent)
                Log.d(tag, "Result Code Set to : $OCR_KWH_RESULT_CODE")
            }
            "KVAH" -> {

                setResult(OCR_KVAH_RESULT_CODE, intent)
                Log.d(tag, "Result Code Set to : $OCR_KVAH_RESULT_CODE")

            }
            "SKWH" -> {

                setResult(OCR_SKWH_RESULT_CODE, intent)
                Log.d(tag, "Result Code Set to : $OCR_SKWH_RESULT_CODE")
            }
            "SKVAH" -> {

                setResult(OCR_SKVAH_RESULT_CODE, intent)
                Log.d(tag, "Result Code Set to : $OCR_SKVAH_RESULT_CODE")

            }
            "RMD" -> {

                setResult(OCR_RMD_RESULT_CODE, intent)
                Log.d(tag, "Result Code Set to : ${OCR_RMD_RESULT_CODE}")

            }
            "LT" -> {
                setResult(OCR_LT_RESULT_CODE, intent)
                Log.d(tag, "Result Code Set to : ${OCR_LT_RESULT_CODE}")
            }
            "IMG" -> {
                setResult(OCR_IMG_RESULT_CODE, intent)
                Log.d(tag, "Result Code Set to : ${OCR_IMG_RESULT_CODE}")
            }
            else -> {
                setResult(OCR_INVALID_RESULT_CODE, intent)
                Log.d(tag, "Result Code Set to : ${OCR_INVALID_RESULT_CODE}")
            }

        }

        intent.putExtra("metadata", metadata.toString())

        // Launch a coroutine to delay and then finish the task
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000) // 2 seconds delay
            finish()
        }
    }

    /**
     * Initialize camera after permissions are granted
     */
    private fun initializeCamera() {
        Log.d(tag, "Initializing camera")

        // Initialize camera manager
        cameraManager = CameraManager(this, this, previewView, roiOverlay)

        // Observe camera capture results
        lifecycleScope.launch {
            cameraManager.captureResult.collectLatest { result ->
                result?.let {
                    currentCaptureResult = it
                    showResultView(it)
                }
            }
        }

        // Start camera
        cameraManager.initialize()

        // Set initial exposure (middle value)
        if (::cameraManager.isInitialized) {
            exposureSeekBar.progress = 50
           // cameraManager.setExposure(50)
        }
    }

    /**
     * Capture button click handler
     */
    private fun captureImage(valType1: String) {
        if (::cameraManager.isInitialized) {
            // Show progress indicator
            progressBar.visibility = View.VISIBLE
            captureButton.isEnabled = false

            // Capture image
            cameraManager.captureImage(valType1)
        }
    }

    /**
     * Toggle camera flash
     */
    private fun toggleFlash() {
        if (::cameraManager.isInitialized) {
            val flashOn = cameraManager.toggleFlash()
            flashButton.setImageResource(
                if (flashOn) R.drawable.ic_flash_on
                else R.drawable.ic_flash_off
            )
        }
    }

    /**
     * Switch between front and back cameras
     */
    private fun switchCamera() {
        if (::cameraManager.isInitialized) {
            cameraManager.switchCamera()
        }
    }

    /**
     * Show camera preview view
     */
    private fun showCameraView() {
        resultLayout.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        roiOverlay.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE
        flashButton.visibility = View.VISIBLE
        switchButton.visibility = View.VISIBLE
        zoomSeekBar.visibility = View.VISIBLE
        exposureSeekBar.visibility = View.VISIBLE

//        findViewById(R.id.exposureLabel).visibility = View.VISIBLE
//        findViewById(R.id.infoPanel).visibility = View.VISIBLE

        // Clean up previous bitmaps to prevent memory leaks
        currentCaptureResult?.let {
            ImageCropper.safeRecycle(it.roiBitmap)
            ImageCropper.safeRecycle(it.modelBitmap)
            if (it.originalBitmap != it.roiBitmap && it.originalBitmap != it.modelBitmap) {
                ImageCropper.safeRecycle(it.originalBitmap)
            }
        }

        // Reset current capture result
        currentCaptureResult = null
        currentMeterReading = null
    }

    private fun showNumericBottomDialog() {
        // Inflate the custom layout
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_numeric_keyboard, null)

        // Initialize BottomSheetDialog with the custom view
        val bottomSheetDialog = BottomSheetDialog(this)
        bottomSheetDialog.setContentView(dialogView)
        bottomSheetDialog.show()

        // Set up display TextView to show entered numbers
        val displayText = dialogView.findViewById<TextView>(R.id.txtTitle)
        inputNumber.clear()

        // Numeric buttons logic
        val numberButtonListener = View.OnClickListener { view ->
            val button = view as Button
            inputNumber.append(button.text.toString())
            displayText.text = inputNumber.toString()
        }

        // Assign listener to each number button
        dialogView.findViewById<Button>(R.id.button_0).setOnClickListener(numberButtonListener)
        dialogView.findViewById<Button>(R.id.button_1).setOnClickListener(numberButtonListener)
        dialogView.findViewById<Button>(R.id.button_2).setOnClickListener(numberButtonListener)
        dialogView.findViewById<Button>(R.id.button_3).setOnClickListener(numberButtonListener)
        dialogView.findViewById<Button>(R.id.button_4).setOnClickListener(numberButtonListener)
        dialogView.findViewById<Button>(R.id.button_5).setOnClickListener(numberButtonListener)
        dialogView.findViewById<Button>(R.id.button_6).setOnClickListener(numberButtonListener)
        dialogView.findViewById<Button>(R.id.button_7).setOnClickListener(numberButtonListener)
        dialogView.findViewById<Button>(R.id.button_8).setOnClickListener(numberButtonListener)
        dialogView.findViewById<Button>(R.id.button_9).setOnClickListener(numberButtonListener)
        dialogView.findViewById<Button>(R.id.button_decimal)
            .setOnClickListener(numberButtonListener)

        // Clear button logic
        dialogView.findViewById<Button>(R.id.button_clear).setOnClickListener {
            inputNumber.setLength(0)
            displayText.text = ""
        }

        // Enter button logic
        dialogView.findViewById<Button>(R.id.button_enter).setOnClickListener {
            readingTextView.text = inputNumber.toString()
            currentMeterReading = inputNumber.toString()
            editFlag = true
            bottomSheetDialog.dismiss()
        }
    }

    /**
     * Show result view with captured image
     */
    private fun showResultView(result: CameraManager.CaptureResult) {
        // Hide progress indicator
        progressBar.visibility = View.GONE
        captureButton.isEnabled = true

        // Update UI
        resultImageView.setImageBitmap(result.roiBitmap)
        resultLayout.visibility = View.VISIBLE
        previewView.visibility = View.GONE
        roiOverlay.visibility = View.GONE
        captureButton.visibility = View.GONE
        flashButton.visibility = View.GONE
        switchButton.visibility = View.GONE
        zoomSeekBar.visibility = View.GONE
        exposureSeekBar.visibility = View.GONE
//        findViewById(R.id.exposureLabel).visibility = View.GONE
//        findViewById(R.id.infoPanel).visibility = View.GONE

        // Clear previous reading
        if(valType=="IMG")
            readingTextView.text = "Tap 'Save'"
        else
        readingTextView.text = "Tap 'Process'"

        currentMeterReading = null

        // Make sure service ID and value type are displayed in the result view
        resultServiceIdTextView.text = serviceId
        resultValueTypeTextView.text = valType
    }

    /**
     * Process the current captured image
     */
    private fun processCurrentImage() {
        val result = currentCaptureResult ?: return

        // These UI updates are on the main thread
        readingTextView.text = "Processing..."
        progressBar.visibility = View.VISIBLE
        processButton.isEnabled = false

        // Process in background
        lifecycleScope.launch {
            try {
                // Run processing on IO dispatcher
                val processingResult = withContext(Dispatchers.IO) {
                    // Detect meter reading
                    val (detections, resultBitmap) = meterDetector.detectMeterReading(result.modelBitmap)

                    // Extract meter reading
                    val reading = meterDetector.extractMeterReading(detections)

                    Pair(reading, resultBitmap)
                }

                // Update UI on Main dispatcher
                val (reading, resultBitmap) = processingResult
                currentMeterReading = reading

                resultImageView.setImageBitmap(resultBitmap)
                readingTextView.text = if (reading.isNullOrEmpty()) {
                    "No meter detected"
                } else {
                    "$reading"
                }
            } catch (e: Exception) {
                Log.e(tag, "Processing failed: ${e.message}", e)
                readingTextView.text = "Processing failed: ${e.message}"
            } finally {
                // Hide progress
                progressBar.visibility = View.GONE
                processButton.isEnabled = true
            }
        }
    }

    /**
     * Save the current image and detected reading
     */
    private fun saveCurrentImage() {
        val result = currentCaptureResult ?: return

        progressBar.visibility = View.VISIBLE
        saveButton.isEnabled = false

        lifecycleScope.launch {
            try {
                meterReading=  currentMeterReading.toString()
                Log.d(tag, "Current Reading: $currentMeterReading")
                imagePath=cameraManager.saveImage(result, currentMeterReading, savedFileName, editFlag).toString()
                Log.d(tag, "Image saved at: $imagePath")
                showCameraView()
            } catch (e: Exception) {
                Log.e(tag, "Failed to save: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Failed to save image", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
                saveButton.isEnabled = true
            }
        }
    }

    /**
     * Permission granted callback
     */
    override fun onPermissionsGranted() {
        Log.d(tag, "All permissions granted")
        initializeCamera()
    }

    /**
     * Permission denied callback
     */
    override fun onPermissionsDenied() {
        Log.e(tag, "Permissions denied")
        Toast.makeText(this, "Camera and storage permissions are required", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clean up bitmaps
        currentCaptureResult?.let {
            ImageCropper.safeRecycle(it.roiBitmap)
            ImageCropper.safeRecycle(it.modelBitmap)
            ImageCropper.safeRecycle(it.originalBitmap)
        }

        // Clean up other resources
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
        if (::meterDetector.isInitialized) {
            meterDetector.close()
        }
    }
    companion object {
        const val OCR_KWH_RESULT_CODE = 666
        const val OCR_KVAH_RESULT_CODE = 667
        const val OCR_RMD_RESULT_CODE = 668
        const val OCR_LT_RESULT_CODE = 669
        const val OCR_IMG_RESULT_CODE = 770
        const val OCR_SKWH_RESULT_CODE = 771
        const val OCR_SKVAH_RESULT_CODE = 772
        const val OCR_INVALID_RESULT_CODE = 773
    }
}
