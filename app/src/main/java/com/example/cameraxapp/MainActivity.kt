
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
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import com.app.autocrop.DateUtils
import kotlin.math.sqrt

/**
 * Enhanced MainActivity with improved grayscale processing for OCR
 * Features:
 * - Adaptive grayscale conversion for better OCR accuracy
 * - Enhanced error handling and memory management
 * - Better UI state management
 * - Improved accessibility and logging
 */
class MainActivity : AppCompatActivity(), PermissionManager.PermissionListener {

    // Logging and configuration
    private companion object {
        const val TAG = "MainActivity_OCR"
        const val DEFAULT_SERVICE = "default_service"
        const val DEFAULT_VAL_TYPE = "default"
        const val PATH_NOT_FOUND = "path_not_found"
        const val MAX_READING_LENGTH = 20
        const val TAP_COUNT_THRESHOLD = 3
        const val PROCESSING_DELAY_MS = 1000L
    }

    // UI Components
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

    // App configuration
    private lateinit var appConfig: AppConfig

    // State management
    private var currentState: AppState = AppState.Camera
    private var currentCaptureResult: CameraManager.CaptureResult? = null
    private var currentMeterReading: String? = null
    private val inputNumber = StringBuilder()
    private var tapCount = 0
    private var editFlag = false

    // Grayscale processing state
    private var isGrayscaleMode = false
    private var isProcessingInGrayscale = true
    private var originalResultBitmap: Bitmap? = null
    private var processedGrayscaleBitmap: Bitmap? = null

    // Feature flags
    private object FeatureFlags {
        const val ENABLE_GRAYSCALE_TOGGLE = true
        const val ENABLE_MANUAL_INPUT = true
        const val ENABLE_ADAPTIVE_PROCESSING = true
        const val ENABLE_ENHANCED_LOGGING = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

// Define the allowed date range
        val startDate = "2025-02-01" // Start date (YYYY-MM-DD)
        val endDate = "2025-06-30"   // End date (YYYY-MM-DD)

        Log.d(TAG, "Start date : $startDate")
        Log.d(TAG, "End date : $endDate")

        if (DateUtils.isWithinDateRange(startDate, endDate)) {
            try {
                initializeApp()
            } catch (e: Exception) {
                AppLogger.e("Failed to initialize app", e)
                showError("Failed to initialize app: ${e.message}")
                finish()
            }
            Log.d(TAG, "Date with in range")
        } else {
            titleTextView = findViewById(R.id.titleTextView)
            titleTextView.setText("Actiation Exppired ${getVersionName()}")
            Log.d(TAG, "Date not ok")
        }
      
    }

    /**
     * Initialize application components
     */
    private fun initializeApp() {
        AppLogger.d("Initializing application")

        // Set orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Log auto-rotation status
        logAutoRotationStatus()

        // Initialize configuration
        appConfig = AppConfig.fromIntent(intent)
        AppLogger.d("App configuration: $appConfig")

        // Initialize UI
        initializeViews()
        updateConfigurationUI()
        setupAccessibility()

        // Initialize managers
        initializeManagers()

        // Request permissions
        permissionManager.requestPermissions()
    }

    /**
     * Log auto-rotation status for debugging
     */
    private fun logAutoRotationStatus() {
        val isAutoRotateEnabled = isAutoRotateOn(contentResolver)
        AppLogger.d("Auto-rotation is ${if (isAutoRotateEnabled) "enabled" else "disabled"}")
    }

    /**
     * Check if auto-rotation is enabled
     */
    private fun isAutoRotateOn(contentResolver: ContentResolver): Boolean {
        return Settings.System.getInt(
            contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        ) == 1
    }

    /**
     * Get app version name
     */
    private fun getVersionName(): String {
        return try {
            val packageManager = packageManager
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            "(Ver:${packageInfo.versionName})"
        } catch (e: PackageManager.NameNotFoundException) {
            AppLogger.e("Version not found", e)
            "Version not found"
        }
    }

    /**
     * Initialize UI components and set up listeners
     */
    private fun initializeViews() {
        AppLogger.d("Initializing UI components")

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

        setupClickListeners()
        setupSeekBarListeners()

        // Set title with version
        titleTextView.text = "Ebilly OCR ${getVersionName()}"
    }

    /**
     * Setup click listeners for UI components
     */
    private fun setupClickListeners() {
        captureButton.setOnClickListener {
            AppLogger.logUserAction("capture_clicked", mapOf("valType" to appConfig.valType))
            captureImage()
        }

        flashButton.setOnClickListener {
            AppLogger.logUserAction("flash_toggled")
            toggleFlash()
        }

        switchButton.setOnClickListener {
            AppLogger.logUserAction("camera_switched")
            switchCamera()
        }

        if (FeatureFlags.ENABLE_GRAYSCALE_TOGGLE) {
            resultImageView.setOnClickListener {
                AppLogger.logUserAction("grayscale_toggled")
                toggleGrayscale()
            }
        }

        saveButton.setOnClickListener {
            AppLogger.logUserAction("save_clicked")
            saveCurrentImage()
        }

        if (FeatureFlags.ENABLE_MANUAL_INPUT) {
            readingTextView.setOnClickListener {
                handleReadingTextTap()
            }
        }

        retakeButton.setOnClickListener {
            AppLogger.logUserAction("retake_clicked")
            updateUIState(AppState.Camera)
        }

        processButton.setOnClickListener {
            AppLogger.logUserAction("process_clicked")
            processCurrentImage()
        }

        // Long click for processing options (if feature enabled)
        if (FeatureFlags.ENABLE_ADAPTIVE_PROCESSING) {
            processButton.setOnLongClickListener {
                showProcessingOptionsDialog()
                true
            }
        }
    }

    /**
     * Setup seekbar listeners
     */
    private fun setupSeekBarListeners() {
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

        exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::cameraManager.isInitialized) {
                    // Exposure control implementation would go here
                    // Currently commented out as mentioned in original code
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * Update UI with configuration data
     */
    private fun updateConfigurationUI() {
        serviceIdTextView.text = appConfig.serviceId
        valueTypeTextView.text = appConfig.valType
        resultServiceIdTextView.text = appConfig.serviceId
        resultValueTypeTextView.text = appConfig.valType
    }

    /**
     * Setup accessibility features
     */
    private fun setupAccessibility() {
        captureButton.contentDescription = "Capture meter reading"
        flashButton.contentDescription = "Toggle flash"
        switchButton.contentDescription = "Switch camera"
        resultImageView.contentDescription = "Captured meter image, tap to toggle grayscale"
        readingTextView.contentDescription = "Meter reading result, tap 3 times to edit manually"
        processButton.contentDescription = "Process image with OCR, long press for options"
    }

    /**
     * Initialize managers
     */
    private fun initializeManagers() {
        permissionManager = PermissionManager(this, this)
        meterDetector = MeterDetector(this)
    }

    /**
     * Initialize camera after permissions are granted
     */
    private fun initializeCamera() {
        AppLogger.d("Initializing camera")

        try {
            cameraManager = CameraManager(this, this, previewView, roiOverlay)

            // Observe camera capture results
            lifecycleScope.launch {
                cameraManager.captureResult.collectLatest { result ->
                    result?.let {
                        currentCaptureResult = it
                        updateUIState(AppState.Result(it))
                    }
                }
            }

            cameraManager.initialize()

            // Set initial exposure
            if (::cameraManager.isInitialized) {
                exposureSeekBar.progress = 50
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to initialize camera", e)
            showError("Failed to initialize camera: ${e.message}")
        }
    }

    /**
     * Handle reading text tap for manual input
     */
    private fun handleReadingTextTap() {
        val text = readingTextView.text.toString()

        if (isValidForTapping(text)) {
            tapCount++
            AppLogger.d("Reading text tapped: count=$tapCount")

            when (tapCount) {
                TAP_COUNT_THRESHOLD -> {
                    editFlag = true
                    showNumericBottomDialog()
                    tapCount = 0
                }
                else -> {
                    val remaining = TAP_COUNT_THRESHOLD - tapCount
                    readingTextView.text = "Tap: ${if (remaining == 1) "1 time" else "$remaining times"}"
                }
            }
        }
    }

    /**
     * Check if text is valid for tapping
     */
    private fun isValidForTapping(text: String): Boolean {
        return text.toDoubleOrNull() != null ||
                text == "No meter detected" ||
                text.startsWith("Tap:")
    }

    /**
     * Capture image with error handling
     */
    private fun captureImage() {
        if (!::cameraManager.isInitialized) {
            showError("Camera not initialized")
            return
        }

        try {
            // Show capturing feedback
            progressBar.visibility = View.VISIBLE
            captureButton.isEnabled = false
            cameraManager.captureImage(appConfig.valType)
        } catch (e: Exception) {
            AppLogger.e("Capture failed", e)
            showError("Failed to capture image: ${e.message}")
            resetCaptureUI()
        }
    }

    /**
     * Toggle camera flash
     */
    private fun toggleFlash() {
        if (!::cameraManager.isInitialized) return

        try {
            val flashOn = cameraManager.toggleFlash()
            flashButton.setImageResource(
                if (flashOn) R.drawable.ic_flash_on
                else R.drawable.ic_flash_off
            )
        } catch (e: Exception) {
            AppLogger.e("Flash toggle failed", e)
            showError("Failed to toggle flash")
        }
    }

    /**
     * Switch between cameras
     */
    private fun switchCamera() {
        if (!::cameraManager.isInitialized) return

        try {
            cameraManager.switchCamera()
        } catch (e: Exception) {
            AppLogger.e("Camera switch failed", e)
            showError("Failed to switch camera")
        }
    }

    /**
     * Enhanced grayscale conversion with multiple options
     */
    private fun convertToGrayscaleAdvanced(
        originalBitmap: Bitmap,
        enhancementType: GrayscaleEnhancement = GrayscaleEnhancement.CONTRAST
    ): Bitmap {
        val width = originalBitmap.width
        val height = originalBitmap.height

        val grayscaleBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)

        val colorMatrix = when (enhancementType) {
            GrayscaleEnhancement.SIMPLE -> {
                ColorMatrix().apply { setSaturation(0f) }
            }

            GrayscaleEnhancement.CONTRAST -> {
                ColorMatrix().apply {
                    setSaturation(0f)
                    val contrast = 1.3f
                    val brightness = -0.1f
                    val scale = contrast
                    val translate = brightness * 255f * (1f - contrast)

                    postConcat(ColorMatrix(floatArrayOf(
                        scale, 0f, 0f, 0f, translate,
                        0f, scale, 0f, 0f, translate,
                        0f, 0f, scale, 0f, translate,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
            }

            GrayscaleEnhancement.HIGH_CONTRAST -> {
                ColorMatrix().apply {
                    setSaturation(0f)
                    val contrast = 1.8f
                    val brightness = -0.2f
                    val scale = contrast
                    val translate = brightness * 255f * (1f - contrast)

                    postConcat(ColorMatrix(floatArrayOf(
                        scale, 0f, 0f, 0f, translate,
                        0f, scale, 0f, 0f, translate,
                        0f, 0f, scale, 0f, translate,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
            }

            GrayscaleEnhancement.LUMINANCE_WEIGHTED -> {
                ColorMatrix(floatArrayOf(
                    0.299f, 0.587f, 0.114f, 0f, 0f,
                    0.299f, 0.587f, 0.114f, 0f, 0f,
                    0.299f, 0.587f, 0.114f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
        }

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
            isAntiAlias = true
            isDither = true
        }

        canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
        return grayscaleBitmap
    }

    /**
     * Adaptive grayscale conversion based on image analysis
     */
    private fun convertToGrayscaleAdaptive(originalBitmap: Bitmap): Bitmap {
        val brightness = calculateAverageBrightness(originalBitmap)
        val contrast = calculateContrast(originalBitmap)

        val enhancement = when {
            brightness < 0.3f && contrast < 0.4f -> GrayscaleEnhancement.HIGH_CONTRAST
            brightness > 0.7f -> GrayscaleEnhancement.CONTRAST
            contrast > 0.6f -> GrayscaleEnhancement.LUMINANCE_WEIGHTED
            else -> GrayscaleEnhancement.CONTRAST
        }

        AppLogger.d("Adaptive grayscale: brightness=$brightness, contrast=$contrast, using=$enhancement")
        return convertToGrayscaleAdvanced(originalBitmap, enhancement)
    }

    /**
     * Calculate average brightness of an image
     */
    private fun calculateAverageBrightness(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalBrightness = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalBrightness += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }

        return (totalBrightness / (pixels.size * 255f))
    }

    /**
     * Calculate contrast of an image
     */
    private fun calculateContrast(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val brightness = mutableListOf<Float>()
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            brightness.add((0.299f * r + 0.587f * g + 0.114f * b) / 255f)
        }

        val mean = brightness.average().toFloat()
        val variance = brightness.map { (it - mean) * (it - mean) }.average().toFloat()
        return sqrt(variance)
    }

    /**
     * Toggle grayscale display mode
     */
    private fun toggleGrayscale() {
        val result = currentCaptureResult ?: return

        try {
            if (!isGrayscaleMode) {
                val currentBitmap = originalResultBitmap ?: result.roiBitmap
                val grayscaleBitmap = convertToGrayscaleAdvanced(currentBitmap, GrayscaleEnhancement.CONTRAST)

                if (originalResultBitmap == null) {
                    originalResultBitmap = (resultImageView.drawable as? BitmapDrawable)?.bitmap
                }

                resultImageView.setImageBitmap(grayscaleBitmap)
                isGrayscaleMode = true
                showToast("Grayscale mode")

            } else {
                val originalBitmap = originalResultBitmap ?: result.roiBitmap
                resultImageView.setImageBitmap(originalBitmap)
                isGrayscaleMode = false
                showToast("Color mode")
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to toggle grayscale", e)
            showError("Failed to convert image")
        }
    }

    /**
     * Process current image with OCR
     */
    private fun processCurrentImage() {
        val result = currentCaptureResult ?: return

        // Disable buttons and show progress
        processButton.isEnabled = false
        saveButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        readingTextView.text = "Processing..."

        lifecycleScope.launch {
            try {
                val processingResult = withContext(Dispatchers.IO) {
                    val bitmapForProcessing = if (isProcessingInGrayscale) {
                        AppLogger.d("Using adaptive grayscale conversion for OCR processing")
                        if (FeatureFlags.ENABLE_ADAPTIVE_PROCESSING) {
                            convertToGrayscaleAdaptive(result.modelBitmap)
                        } else {
                            convertToGrayscaleAdvanced(result.modelBitmap, GrayscaleEnhancement.CONTRAST)
                        }
                    } else {
                        result.modelBitmap
                    }

                    processedGrayscaleBitmap = bitmapForProcessing

                    // Update progress
                    withContext(Dispatchers.Main) {
                        readingTextView.text = "Analyzing text..."
                    }

                    val (detections, resultBitmap) = meterDetector.detectMeterReading(bitmapForProcessing)
                    val reading = meterDetector.extractMeterReading(detections)

                    Pair(reading, resultBitmap)
                }

                val (reading, resultBitmap) = processingResult
                currentMeterReading = reading
                resultImageView.setImageBitmap(resultBitmap)

                readingTextView.text = if (reading.isNullOrEmpty()) {
                    "No meter detected"
                } else {
                    reading
                }

                // Enable save button after successful processing
                saveButton.isEnabled = true

                AppLogger.logUserAction("ocr_completed", mapOf(
                    "reading" to (reading ?: "null"),
                    "grayscale" to isProcessingInGrayscale
                ))

            } catch (e: Exception) {
                AppLogger.e("Processing failed", e)
                readingTextView.text = "Processing failed: ${e.message}"
                // Re-enable process button on failure so user can retry
                processButton.isEnabled = true
            } finally {
                progressBar.visibility = View.GONE
                // Always re-enable process button so user can reprocess if needed
                processButton.isEnabled = true
            }
        }
    }

    /**
     * Show processing options dialog
     */
    private fun showProcessingOptionsDialog() {
        val options = arrayOf(
            "Auto (Adaptive)",
            "Simple Grayscale",
            "Enhanced Contrast",
            "High Contrast",
            "Luminance Weighted",
            "Color (Original)"
        )

        val currentSelection = if (!isProcessingInGrayscale) 5 else 0

        android.app.AlertDialog.Builder(this)
            .setTitle("Processing Mode")
            .setSingleChoiceItems(options, currentSelection) { dialog, which ->
                when (which) {
                    0, 1, 2, 3, 4 -> isProcessingInGrayscale = true
                    5 -> isProcessingInGrayscale = false
                }

                val mode = options[which]
                showToast("Processing mode: $mode")
                AppLogger.logUserAction("processing_mode_changed", mapOf("mode" to mode))
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show numeric input bottom sheet dialog
     */
    private fun showNumericBottomDialog() {
        try {
            val inflater = layoutInflater
            val dialogView = inflater.inflate(R.layout.dialog_numeric_keyboard, null)

            val bottomSheetDialog = BottomSheetDialog(this)
            bottomSheetDialog.setContentView(dialogView)
            bottomSheetDialog.show()

            val displayText = dialogView.findViewById<TextView>(R.id.txtTitle)
            inputNumber.clear()

            // Setup number buttons
            val numberButtonListener = View.OnClickListener { view ->
                val button = view as Button
                inputNumber.append(button.text.toString())
                displayText.text = inputNumber.toString()
            }

            // Assign listeners to number buttons
            listOf(R.id.button_0, R.id.button_1, R.id.button_2, R.id.button_3, R.id.button_4,
                R.id.button_5, R.id.button_6, R.id.button_7, R.id.button_8, R.id.button_9,
                R.id.button_decimal).forEach { id ->
                dialogView.findViewById<Button>(id).setOnClickListener(numberButtonListener)
            }

            // Clear button
            dialogView.findViewById<Button>(R.id.button_clear).setOnClickListener {
                inputNumber.setLength(0)
                displayText.text = ""
            }

            // Enter button
            dialogView.findViewById<Button>(R.id.button_enter).setOnClickListener {
                val enteredValue = inputNumber.toString()
                if (validateMeterReading(enteredValue)) {
                    readingTextView.text = enteredValue
                    currentMeterReading = enteredValue
                    editFlag = true
                    AppLogger.logUserAction("manual_input", mapOf("value" to enteredValue))
                    bottomSheetDialog.dismiss()
                } else {
                    showError("Invalid reading format")
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to show numeric dialog", e)
            showError("Failed to show input dialog")
        }
    }

    /**
     * Validate meter reading input
     */
    private fun validateMeterReading(reading: String): Boolean {
        return when {
            reading.isBlank() -> false
            reading.toDoubleOrNull() == null -> false
            reading.length > MAX_READING_LENGTH -> false
            else -> true
        }
    }

    /**
     * Save current image and reading
     */
    private fun saveCurrentImage() {
        val result = currentCaptureResult ?: return

        // Disable save button and show progress
        saveButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        readingTextView.text = "Saving..."

        lifecycleScope.launch {
            try {
                val meterReading = currentMeterReading.toString()
                val imagePath = cameraManager.saveImage(
                    result,
                    currentMeterReading,
                    appConfig.savedFileName,
                    editFlag
                ).toString()

                AppLogger.d("Image saved at: $imagePath")
                AppLogger.logUserAction("image_saved", mapOf(
                    "reading" to meterReading,
                    "edited" to editFlag,
                    "path" to imagePath
                ))

                sendBackResults(meterReading, imagePath)

            } catch (e: Exception) {
                AppLogger.e("Failed to save image", e)
                showError("Failed to save image: ${e.message}")
                // Re-enable save button on failure
                saveButton.isEnabled = true
                progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * Send results back to calling activity
     */
    private fun sendBackResults(meterReading: String, imagePath: String) {
        val metadata = JSONObject().apply {
            put("meterReading", meterReading)
            put("filename", imagePath)
            put("isEdited", editFlag)
            put("valType", appConfig.valType)
            put("serviceId", appConfig.serviceId)
        }

        val resultCode = ResultCodes.getResultCode(appConfig.valType)
        intent.putExtra("metadata", metadata.toString())
        setResult(resultCode, intent)

        AppLogger.d("Sending results: code=$resultCode, metadata=$metadata")

        // Delay before finishing
        CoroutineScope(Dispatchers.Main).launch {
            delay(PROCESSING_DELAY_MS)
            finish()
        }
    }

    /**
     * Update UI state
     */
    private fun updateUIState(state: AppState) {
        currentState = state

        when (state) {
            is AppState.Camera -> showCameraView()
            is AppState.Result -> showResultView(state.captureResult)
            is AppState.Error -> showError(state.message)
            is AppState.Processing -> {
                // Simple processing state - just show message
                showToast(state.message)
            }
        }
    }

    /**
     * Show camera view
     */
    private fun showCameraView() {
        resultLayout.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        roiOverlay.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE
        flashButton.visibility = View.VISIBLE
        switchButton.visibility = View.VISIBLE
        zoomSeekBar.visibility = View.VISIBLE
        exposureSeekBar.visibility = View.GONE

        cleanupBitmaps()
        isGrayscaleMode = false
        tapCount = 0
    }

    /**
     * Show result view
     */
    private fun showResultView(result: CameraManager.CaptureResult) {
        resetCaptureUI()
        resetProcessingUI() // Ensure all buttons are enabled

        val displayBitmap = if (isProcessingInGrayscale) {
            val grayscaleBitmap = convertToGrayscaleAdvanced(result.roiBitmap, GrayscaleEnhancement.CONTRAST)
            originalResultBitmap = result.roiBitmap
            isGrayscaleMode = true
            grayscaleBitmap
        } else {
            result.roiBitmap
        }

        resultImageView.setImageBitmap(displayBitmap)
        resultLayout.visibility = View.VISIBLE
        previewView.visibility = View.GONE
        roiOverlay.visibility = View.GONE
        captureButton.visibility = View.GONE
        flashButton.visibility = View.GONE
        switchButton.visibility = View.GONE
        zoomSeekBar.visibility = View.GONE
        exposureSeekBar.visibility = View.GONE

        // Ensure buttons are in correct state for the value type
        if (appConfig.valType == "IMG") {
            readingTextView.text = "Tap 'Save'"
            processButton.visibility = View.GONE
            saveButton.visibility = View.VISIBLE
            saveButton.isEnabled = true
        } else {
            readingTextView.text = "Tap 'Process'"
            processButton.visibility = View.VISIBLE
            processButton.isEnabled = true
            saveButton.visibility = View.VISIBLE
            saveButton.isEnabled = false // Enable after processing
        }

        currentMeterReading = null
        updateConfigurationUI()
    }

    /**
     * Reset capture UI state
     */
    private fun resetCaptureUI() {
        progressBar.visibility = View.GONE
        captureButton.isEnabled = true
    }

    /**
     * Reset processing UI state (simplified)
     */
    private fun resetProcessingUI() {
        progressBar.visibility = View.GONE
        processButton.isEnabled = true
        saveButton.isEnabled = true
    }

    /**
     * Show error message
     */
    private fun showError(message: String) {
        showToast(message)
        AppLogger.e("Error displayed to user: $message")
    }

    /**
     * Show toast message
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Clean up all bitmaps
     */
    private fun cleanupBitmaps() {
        currentCaptureResult?.let {
            ImageCropper.safeRecycle(it.roiBitmap)
            ImageCropper.safeRecycle(it.modelBitmap)
            if (it.originalBitmap != it.roiBitmap && it.originalBitmap != it.modelBitmap) {
                ImageCropper.safeRecycle(it.originalBitmap)
            }
        }

        originalResultBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                ImageCropper.safeRecycle(bitmap)
            }
        }

        processedGrayscaleBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                ImageCropper.safeRecycle(bitmap)
            }
        }

        originalResultBitmap = null
        processedGrayscaleBitmap = null
        currentCaptureResult = null
        currentMeterReading = null
    }

    // Permission callbacks
    override fun onPermissionsGranted() {
        AppLogger.d("All permissions granted")
        initializeCamera()
    }

    override fun onPermissionsDenied() {
        AppLogger.e("Permissions denied")
        showError("Camera and storage permissions are required")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.d("Activity destroyed")

        cleanupBitmaps()

        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
        if (::meterDetector.isInitialized) {
            meterDetector.close()
        }
    }

    // Data classes and enums
    data class AppConfig(
        val serviceId: String,
        val valType: String,
        val savedFileName: String
    ) {
        companion object {
            fun fromIntent(intent: Intent): AppConfig {
                val dataHandler = IntentDataHandler(intent)
                val serviceId = dataHandler.getServiceId()
                val valType = dataHandler.getValType()

                return AppConfig(
                    serviceId = serviceId,
                    valType = valType,
                    savedFileName = "${serviceId}_${valType}"
                )
            }
        }
    }

    sealed class AppState {
        object Camera : AppState()
        data class Processing(val message: String) : AppState()
        data class Result(val captureResult: CameraManager.CaptureResult) : AppState()
        data class Error(val message: String) : AppState()
    }

    enum class GrayscaleEnhancement {
        SIMPLE,
        CONTRAST,
        HIGH_CONTRAST,
        LUMINANCE_WEIGHTED
    }

    object ResultCodes {
        const val OCR_KWH_RESULT_CODE = 666
        const val OCR_KVAH_RESULT_CODE = 667
        const val OCR_RMD_RESULT_CODE = 668
        const val OCR_LT_RESULT_CODE = 669
        const val OCR_IMG_RESULT_CODE = 770
        const val OCR_SKWH_RESULT_CODE = 771
        const val OCR_SKVAH_RESULT_CODE = 772
        const val OCR_INVALID_RESULT_CODE = 773

        fun getResultCode(valType: String): Int {
            return when (valType) {
                "KWH" -> OCR_KWH_RESULT_CODE
                "KVAH" -> OCR_KVAH_RESULT_CODE
                "SKWH" -> OCR_SKWH_RESULT_CODE
                "SKVAH" -> OCR_SKVAH_RESULT_CODE
                "RMD" -> OCR_RMD_RESULT_CODE
                "LT" -> OCR_LT_RESULT_CODE
                "IMG" -> OCR_IMG_RESULT_CODE
                else -> OCR_INVALID_RESULT_CODE
            }
        }
    }

    object AppLogger {
        fun d(message: String, tag: String = TAG) {
            if (FeatureFlags.ENABLE_ENHANCED_LOGGING) {
                Log.d(tag, message)
            }
        }

        fun e(message: String, error: Throwable? = null, tag: String = TAG) {
            Log.e(tag, message, error)
        }

        fun logUserAction(action: String, details: Map<String, Any> = emptyMap()) {
            if (FeatureFlags.ENABLE_ENHANCED_LOGGING) {
                val logMessage = "UserAction: $action ${details.entries.joinToString { "${it.key}=${it.value}" }}"
                d(logMessage)
            }
        }
    }
}
//package com.example.cameraxapp
//import android.content.pm.ActivityInfo
//import android.content.pm.PackageManager
//import android.os.Bundle
//import android.util.Log
//import android.view.View
//import android.widget.Button
//import android.widget.ImageButton
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.ProgressBar
//import android.widget.SeekBar
//import android.widget.TextView
//import android.widget.Toast
//import androidx.appcompat.app.AppCompatActivity
//import androidx.camera.view.PreviewView
//import androidx.lifecycle.lifecycleScope
//import com.google.android.material.bottomsheet.BottomSheetDialog
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import org.json.JSONObject
//import android.content.ContentResolver
//import android.graphics.Bitmap
//import android.graphics.Canvas
//import android.graphics.ColorMatrix
//import android.graphics.ColorMatrixColorFilter
//import android.graphics.Paint
//import android.graphics.drawable.BitmapDrawable
//import android.provider.Settings
//import android.provider.Settings.System
//
//class MainActivity : AppCompatActivity(), PermissionManager.PermissionListener {
//    private val tag = "MainActivity"
//
//    // UI components
//    private lateinit var previewView: PreviewView
//    private lateinit var roiOverlay: ROIOverlay
//    private lateinit var captureButton: Button
//    private lateinit var flashButton: ImageButton
//    private lateinit var switchButton: ImageButton
//    private lateinit var zoomSeekBar: SeekBar
//    private lateinit var exposureSeekBar: SeekBar
//    private lateinit var progressBar: ProgressBar
//    private lateinit var resultLayout: LinearLayout
//    private lateinit var resultImageView: ImageView
//    private lateinit var readingTextView: TextView
//
//    private lateinit var titleTextView: TextView
//    private lateinit var serviceIdTextView: TextView
//    private lateinit var valueTypeTextView: TextView
//    private lateinit var resultServiceIdTextView: TextView
//    private lateinit var resultValueTypeTextView: TextView
//
//    private lateinit var saveButton: Button
//    private lateinit var retakeButton: Button
//    private lateinit var processButton: Button
//
//    // Managers and detectors
//    private lateinit var permissionManager: PermissionManager
//    private lateinit var cameraManager: CameraManager
//    private lateinit var meterDetector: MeterDetector
//
//    // State variables
//    private var currentCaptureResult: CameraManager.CaptureResult? = null
//    private var currentMeterReading: String? = null
//    private val inputNumber = StringBuilder()
//
//    var tapCount = 0
//    var editFlag = false
//    var serviceId = "default_service"
//    var valType = "default"
//    var savedFileName="default"
//    var meterReading="null"
//    var imagePath="paht_not_found"
//    private var isGrayscaleMode = false
//    private var originalResultBitmap: Bitmap? = null
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//
//        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
//
//        // Inside your Activity class
//        val isAutoRotateEnabled = isAutoRotateOn(contentResolver)
//
//
//        if (isAutoRotateEnabled) {
//            // Auto-rotation is on
//            Log.d(tag, "Auto-rotation is enabled")
//        } else {
//            // Auto-rotation is off
//            Log.d(tag, "Auto-rotation is disabled")
//        }
//
//        // Create an instance with your intent
//        val dataHandler = IntentDataHandler(intent)
//
//        serviceId = dataHandler.getServiceId()
//        valType = dataHandler.getValType()
//        savedFileName = serviceId + "_" + valType
//
//        // Initialize UI components
//        initializeViews()
//
//        // Update UI with service ID and value type
//        serviceIdTextView.text = serviceId
//        valueTypeTextView.text = valType
//
//        Log.d(tag, "ServiceID: $serviceId")
//        Log.d(tag, "ValueType: $valType")
//
//        resultServiceIdTextView.text = serviceId
//        resultValueTypeTextView.text = valType
//
//        // Initialize permission manager
//        permissionManager = PermissionManager(this, this)
//
//        // Initialize meter detector
//        meterDetector = MeterDetector(this)
//
//        // Request permissions
//        permissionManager.requestPermissions()
//    }
//
//
//    private fun convertToGrayscale(originalBitmap: Bitmap): Bitmap {
//        val width = originalBitmap.width
//        val height = originalBitmap.height
//
//        val grayscaleBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
//        val canvas = Canvas(grayscaleBitmap)
//
//        val colorMatrix = ColorMatrix().apply {
//            setSaturation(0f) // 0f = grayscale, 1f = original colors
//        }
//
//        val paint = Paint().apply {
//            colorFilter = ColorMatrixColorFilter(colorMatrix)
//        }
//
//        canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
//        return grayscaleBitmap
//    }
//
//
//    // Method to check if auto-rotation is enabled
//    fun isAutoRotateOn(contentResolver: ContentResolver): Boolean {
//        return Settings.System.getInt(
//            contentResolver,
//            Settings.System.ACCELEROMETER_ROTATION,
//            0
//        ) == 1
//    }
//
//    private fun getVersionName(): String {
//        return try {
//            val packageManager = packageManager
//            val packageInfo = packageManager.getPackageInfo(packageName, 0)
//            "(Ver:${packageInfo.versionName})"
//        } catch (e: PackageManager.NameNotFoundException) {
//            e.printStackTrace()
//            "Version not found"
//        }
//    }
//
//    /**
//     * Initialize UI components and set up listeners
//     */
//    private fun initializeViews() {
//        // Camera preview components
//        previewView = findViewById(R.id.viewFinder)
//        roiOverlay = findViewById(R.id.roiOverlay)
//        captureButton = findViewById(R.id.captureButton)
//        flashButton = findViewById(R.id.flashButton)
//        switchButton = findViewById(R.id.switchButton)
//        zoomSeekBar = findViewById(R.id.zoomSeekBar)
//        exposureSeekBar = findViewById(R.id.exposureSeekBar)
//        progressBar = findViewById(R.id.progressBar)
//
//        // Service ID and Value Type displays
//        serviceIdTextView = findViewById(R.id.serviceIdTextView)
//        valueTypeTextView = findViewById(R.id.valueTypeTextView)
//
//        // Result view components
//        resultLayout = findViewById(R.id.resultLayout)
//        resultImageView = findViewById(R.id.resultImageView)
//        readingTextView = findViewById(R.id.readingTextView)
//        resultServiceIdTextView = findViewById(R.id.resultServiceIdTextView)
//        resultValueTypeTextView = findViewById(R.id.resultValueTypeTextView)
//        saveButton = findViewById(R.id.saveButton)
//        retakeButton = findViewById(R.id.retakeButton)
//        processButton = findViewById(R.id.processButton)
//        titleTextView = findViewById(R.id.titleTextView)
//
//
//        resultImageView.setOnClickListener {
//            toggleGrayscale()
//        }
//
//        // Set up button click listeners
//        captureButton.setOnClickListener {
//            captureImage(valType)
//        }
//
//        flashButton.setOnClickListener {
//            toggleFlash()
//        }
//
//        switchButton.setOnClickListener {
//            switchCamera()
//        }
//
//
//        saveButton.setOnClickListener {
//            saveCurrentImage()
//            sendBackvalues()
//
//        }
//
//        readingTextView.setOnClickListener {
//
//            val text = readingTextView.text.toString()
//            if (text.toDoubleOrNull() != null || text == "No meter detected" || text == "Tap: 2 times" || text == "Tap: 1 time") {
//                tapCount++
//                Log.d(tag, "Tapped : $tapCount")
//
//                if (tapCount == 3) {
//                    editFlag = true
//                    Log.d(tag, "Edit Flag set to : $editFlag")
//                    showNumericBottomDialog()
//                    tapCount = 0
//                } else {
//                    val remaining = 3 - tapCount
//                    readingTextView.text = if (remaining == 1) "Tap: 1 time" else "Tap: $remaining times"
//                }
//            }
//
////            if (readingTextView.text.toString() == "No meter detected") {
////                editFlag = true
////            }
////
////            tapCount = tapCount + 1
////            if (tapCount == 3) {
////                showNumericBottomDialog()
////                tapCount = 0
////            } else {
////                readingTextView.text = buildString {
////                    append("Tap: ")
////                    append(3 - tapCount)
////                    append(" times")
////                }
////            }
//        }
//
//        retakeButton.setOnClickListener {
//            showCameraView()
//        }
//
//        processButton.setOnClickListener {
//            processCurrentImage()
//        }
//
//
//
//        // Set up zoom seekbar
//        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser && ::cameraManager.isInitialized) {
//                    val zoomLevel = progress / 100f
//                    cameraManager.setZoom(zoomLevel)
//                }
//            }
//
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })
//
//        // Set up exposure seekbar
//        exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser && ::cameraManager.isInitialized) {
//                   // cameraManager.setExposure(progress)
//                }
//            }
//
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })
//
//        titleTextView.text = buildString {
//            append("Ebilly OCR ")
//            append(getVersionName())
//        }
//    }
//
//    private fun toggleGrayscale() {
//        val result = currentCaptureResult ?: return
//
//        try {
//            if (!isGrayscaleMode) {
//                // Convert to grayscale
//                originalResultBitmap = (resultImageView.drawable as? BitmapDrawable)?.bitmap
//                val currentBitmap = originalResultBitmap ?: result.roiBitmap
//
//                val grayscaleBitmap = convertToGrayscale(currentBitmap)
//                resultImageView.setImageBitmap(grayscaleBitmap)
//                isGrayscaleMode = true
//
//                // Optional: Show toast to indicate grayscale mode
//                Toast.makeText(this, "Grayscale mode", Toast.LENGTH_SHORT).show()
//
//            } else {
//                // Convert back to original
//                val originalBitmap = originalResultBitmap ?: result.roiBitmap
//                resultImageView.setImageBitmap(originalBitmap)
//                isGrayscaleMode = false
//
//                // Optional: Show toast to indicate color mode
//                Toast.makeText(this, "Color mode", Toast.LENGTH_SHORT).show()
//            }
//        } catch (e: Exception) {
//            Log.e(tag, "Failed to toggle grayscale: ${e.message}", e)
//            Toast.makeText(this, "Failed to convert image", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    private fun sendBackvalues(){
//        // Create JSON object with metadata
//        val metadata = JSONObject().apply {
//            put("meterReading", meterReading)
//            put("filename", imagePath)
//            put("isEdited", editFlag)
//            // Any other metadata fields you want to include
//        }
//
//        Log.d(tag, "Metadata: $metadata")
//
//        Log.d(tag, "Valtype: $valType")
//
//        // Add value type specific extras and set result code
//        when (valType) {
//            "KWH" -> {
//
//                setResult(OCR_KWH_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : $OCR_KWH_RESULT_CODE")
//            }
//            "KVAH" -> {
//
//                setResult(OCR_KVAH_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : $OCR_KVAH_RESULT_CODE")
//
//            }
//            "SKWH" -> {
//
//                setResult(OCR_SKWH_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : $OCR_SKWH_RESULT_CODE")
//            }
//            "SKVAH" -> {
//
//                setResult(OCR_SKVAH_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : $OCR_SKVAH_RESULT_CODE")
//
//            }
//            "RMD" -> {
//
//                setResult(OCR_RMD_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : ${OCR_RMD_RESULT_CODE}")
//
//            }
//            "LT" -> {
//                setResult(OCR_LT_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : ${OCR_LT_RESULT_CODE}")
//            }
//            "IMG" -> {
//                setResult(OCR_IMG_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : ${OCR_IMG_RESULT_CODE}")
//            }
//            else -> {
//                setResult(OCR_INVALID_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : ${OCR_INVALID_RESULT_CODE}")
//            }
//
//        }
//
//        intent.putExtra("metadata", metadata.toString())
//
//        // Launch a coroutine to delay and then finish the task
//        CoroutineScope(Dispatchers.Main).launch {
//            delay(1000) // 2 seconds delay
//            finish()
//        }
//    }
//
//    /**
//     * Initialize camera after permissions are granted
//     */
//    private fun initializeCamera() {
//        Log.d(tag, "Initializing camera")
//
//        // Initialize camera manager
//        cameraManager = CameraManager(this, this, previewView, roiOverlay)
//
//        // Observe camera capture results
//        lifecycleScope.launch {
//            cameraManager.captureResult.collectLatest { result ->
//                result?.let {
//                    currentCaptureResult = it
//                    showResultView(it)
//                }
//            }
//        }
//
//        // Start camera
//        cameraManager.initialize()
//
//        // Set initial exposure (middle value)
//        if (::cameraManager.isInitialized) {
//            exposureSeekBar.progress = 50
//           // cameraManager.setExposure(50)
//        }
//    }
//
//    /**
//     * Capture button click handler
//     */
//    private fun captureImage(valType1: String) {
//        if (::cameraManager.isInitialized) {
//            // Show progress indicator
//            progressBar.visibility = View.VISIBLE
//            captureButton.isEnabled = false
//
//            // Capture image
//            cameraManager.captureImage(valType1)
//        }
//    }
//
//    /**
//     * Toggle camera flash
//     */
//    private fun toggleFlash() {
//        if (::cameraManager.isInitialized) {
//            val flashOn = cameraManager.toggleFlash()
//            flashButton.setImageResource(
//                if (flashOn) R.drawable.ic_flash_on
//                else R.drawable.ic_flash_off
//            )
//        }
//    }
//
//    /**
//     * Switch between front and back cameras
//     */
//    private fun switchCamera() {
//        if (::cameraManager.isInitialized) {
//            cameraManager.switchCamera()
//        }
//    }
//
//    /**
//     * Show camera preview view
//     */
//    private fun showCameraView() {
//        resultLayout.visibility = View.GONE
//        previewView.visibility = View.VISIBLE
//        roiOverlay.visibility = View.VISIBLE
//        captureButton.visibility = View.VISIBLE
//        flashButton.visibility = View.VISIBLE
//        switchButton.visibility = View.VISIBLE
//        zoomSeekBar.visibility = View.VISIBLE
//        exposureSeekBar.visibility = View.VISIBLE
//
////        findViewById(R.id.exposureLabel).visibility = View.VISIBLE
////        findViewById(R.id.infoPanel).visibility = View.VISIBLE
//
//        // Clean up previous bitmaps to prevent memory leaks
//        currentCaptureResult?.let {
//            ImageCropper.safeRecycle(it.roiBitmap)
//            ImageCropper.safeRecycle(it.modelBitmap)
//            if (it.originalBitmap != it.roiBitmap && it.originalBitmap != it.modelBitmap) {
//                ImageCropper.safeRecycle(it.originalBitmap)
//            }
//        }
//
//        // Reset current capture result
//        currentCaptureResult = null
//        currentMeterReading = null
//    }
//
//    private fun showNumericBottomDialog() {
//        // Inflate the custom layout
//        val inflater = layoutInflater
//        val dialogView = inflater.inflate(R.layout.dialog_numeric_keyboard, null)
//
//        // Initialize BottomSheetDialog with the custom view
//        val bottomSheetDialog = BottomSheetDialog(this)
//        bottomSheetDialog.setContentView(dialogView)
//        bottomSheetDialog.show()
//
//        // Set up display TextView to show entered numbers
//        val displayText = dialogView.findViewById<TextView>(R.id.txtTitle)
//        inputNumber.clear()
//
//        // Numeric buttons logic
//        val numberButtonListener = View.OnClickListener { view ->
//            val button = view as Button
//            inputNumber.append(button.text.toString())
//            displayText.text = inputNumber.toString()
//        }
//
//        // Assign listener to each number button
//        dialogView.findViewById<Button>(R.id.button_0).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_1).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_2).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_3).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_4).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_5).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_6).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_7).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_8).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_9).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_decimal)
//            .setOnClickListener(numberButtonListener)
//
//        // Clear button logic
//        dialogView.findViewById<Button>(R.id.button_clear).setOnClickListener {
//            inputNumber.setLength(0)
//            displayText.text = ""
//        }
//
//        // Enter button logic
//        dialogView.findViewById<Button>(R.id.button_enter).setOnClickListener {
//            readingTextView.text = inputNumber.toString()
//            currentMeterReading = inputNumber.toString()
//            editFlag = true
//            bottomSheetDialog.dismiss()
//        }
//    }
//
//    /**
//     * Show result view with captured image
//     */
//    private fun showResultView(result: CameraManager.CaptureResult) {
//        // Hide progress indicator
//        progressBar.visibility = View.GONE
//        captureButton.isEnabled = true
//
//        // Update UI
//        resultImageView.setImageBitmap(result.roiBitmap)
//        resultLayout.visibility = View.VISIBLE
//        previewView.visibility = View.GONE
//        roiOverlay.visibility = View.GONE
//        captureButton.visibility = View.GONE
//        flashButton.visibility = View.GONE
//        switchButton.visibility = View.GONE
//        zoomSeekBar.visibility = View.GONE
//        exposureSeekBar.visibility = View.GONE
////        findViewById(R.id.exposureLabel).visibility = View.GONE
////        findViewById(R.id.infoPanel).visibility = View.GONE
//
//        // Clear previous reading
//        if(valType=="IMG")
//            readingTextView.text = "Tap 'Save'"
//        else
//        readingTextView.text = "Tap 'Process'"
//
//        currentMeterReading = null
//
//        // Make sure service ID and value type are displayed in the result view
//        resultServiceIdTextView.text = serviceId
//        resultValueTypeTextView.text = valType
//    }
//
//    /**
//     * Process the current captured image
//     */
//    private fun processCurrentImage() {
//        val result = currentCaptureResult ?: return
//
//        // These UI updates are on the main thread
//        readingTextView.text = "Processing..."
//        progressBar.visibility = View.VISIBLE
//        processButton.isEnabled = false
//
//        // Process in background
//        lifecycleScope.launch {
//            try {
//                // Run processing on IO dispatcher
//                val processingResult = withContext(Dispatchers.IO) {
//                    // Detect meter reading
//                    val (detections, resultBitmap) = meterDetector.detectMeterReading(result.modelBitmap)
//
//                    // Extract meter reading
//                    val reading = meterDetector.extractMeterReading(detections)
//
//                    Pair(reading, resultBitmap)
//                }
//
//                // Update UI on Main dispatcher
//                val (reading, resultBitmap) = processingResult
//                currentMeterReading = reading
//
//                resultImageView.setImageBitmap(resultBitmap)
//                readingTextView.text = if (reading.isNullOrEmpty()) {
//                    "No meter detected"
//                } else {
//                    "$reading"
//                }
//            } catch (e: Exception) {
//                Log.e(tag, "Processing failed: ${e.message}", e)
//                readingTextView.text = "Processing failed: ${e.message}"
//            } finally {
//                // Hide progress
//                progressBar.visibility = View.GONE
//                processButton.isEnabled = true
//            }
//        }
//    }
//
//    /**
//     * Save the current image and detected reading
//     */
//    private fun saveCurrentImage() {
//        val result = currentCaptureResult ?: return
//
//        progressBar.visibility = View.VISIBLE
//        saveButton.isEnabled = false
//
//        lifecycleScope.launch {
//            try {
//                meterReading=  currentMeterReading.toString()
//                Log.d(tag, "Current Reading: $currentMeterReading")
//                imagePath=cameraManager.saveImage(result, currentMeterReading, savedFileName, editFlag).toString()
//                Log.d(tag, "Image saved at: $imagePath")
//                showCameraView()
//            } catch (e: Exception) {
//                Log.e(tag, "Failed to save: ${e.message}", e)
//                Toast.makeText(this@MainActivity, "Failed to save image", Toast.LENGTH_SHORT).show()
//            } finally {
//                progressBar.visibility = View.GONE
//                saveButton.isEnabled = true
//            }
//        }
//    }
//
//    /**
//     * Permission granted callback
//     */
//    override fun onPermissionsGranted() {
//        Log.d(tag, "All permissions granted")
//        initializeCamera()
//    }
//
//    /**
//     * Permission denied callback
//     */
//    override fun onPermissionsDenied() {
//        Log.e(tag, "Permissions denied")
//        Toast.makeText(this, "Camera and storage permissions are required", Toast.LENGTH_LONG).show()
//        finish()
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//
//        // Clean up bitmaps
//        currentCaptureResult?.let {
//            ImageCropper.safeRecycle(it.roiBitmap)
//            ImageCropper.safeRecycle(it.modelBitmap)
//            ImageCropper.safeRecycle(it.originalBitmap)
//        }
//
//        // Clean up other resources
//        if (::cameraManager.isInitialized) {
//            cameraManager.shutdown()
//        }
//        if (::meterDetector.isInitialized) {
//            meterDetector.close()
//        }
//    }
//    companion object {
//        const val OCR_KWH_RESULT_CODE = 666
//        const val OCR_KVAH_RESULT_CODE = 667
//        const val OCR_RMD_RESULT_CODE = 668
//        const val OCR_LT_RESULT_CODE = 669
//        const val OCR_IMG_RESULT_CODE = 770
//        const val OCR_SKWH_RESULT_CODE = 771
//        const val OCR_SKVAH_RESULT_CODE = 772
//        const val OCR_INVALID_RESULT_CODE = 773
//    }
//}
