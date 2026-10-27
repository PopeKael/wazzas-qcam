/*
 * Modified for Wazza's QCam by Warren Gerdes, 2026. See LICENSE and NOTICE.
 */

package com.wordhint.teleprompter

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.view.View
import android.view.WindowInsetsController
import android.view.Surface
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.DynamicRange
import androidx.camera.core.MirrorMode
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.AudioStats
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import java.util.Locale
import kotlin.random.Random

class WazzaCueCamActivity : ComponentActivity() {
    private lateinit var repository: ScriptRepository
    private lateinit var scriptEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var recordButton: ImageButton
    private lateinit var timerText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var previewStage: CameraPreviewStage
    private lateinit var mainRoot: View
    private lateinit var titleText: TextView
    private lateinit var themeToggleButton: Button
    private lateinit var cameraFlipButton: ImageButton
    private lateinit var micStatusText: AudioLevelGraphView

    private val handler = Handler(Looper.getMainLooper())
    private var script: Script? = null
    private var cameraProvider: androidx.camera.lifecycle.ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraEnabled = false
    private var cameraStarting = false
    private var recordingStartedAt = 0L
    private var darkModeEnabled = false
    private var launchSplashVisible = false
    private var keyboardVisible = false
    private var changingScript = false
    private var rootPadding = intArrayOf(0, 0, 0, 0)
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    private var selectedQuality: Quality? = null
    private var supportedQualities: List<Quality> = emptyList()
    private var bindingGeneration = 0
    private var pendingCameraRollback: (() -> Unit)? = null
    private val cameraPrefs by lazy { getSharedPreferences("camera_settings", MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (requiredPermissions().all { result[it] == true }) {
            startCameraPreview()
        } else {
            setStatus(getString(R.string.status_permissions_needed))
            updateRecordButton()
        }
    }

    private val saveScriptRunnable = Runnable { saveWorkingScript(showStatus = true) }
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (recording != null) {
                val elapsed = ((SystemClock.elapsedRealtime() - recordingStartedAt) / 1000L).coerceAtLeast(0)
                timerText.text = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) showLaunchSplash()
        darkModeEnabled = getSharedPreferences(THEME_PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, isSystemDarkMode())
        repository = ScriptRepository(this)
        cameraFacing = cameraPrefs.getInt("camera_facing", CameraSelector.LENS_FACING_FRONT)
        bindViews()
        setupKeyboardInsets()
        applyColorScheme()
        loadLastScript()
        setupControls()
        ensureCameraPermissionsAndStart()
    }

    private fun showLaunchSplash() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val images = if (landscape) LANDSCAPE_SPLASHES else PORTRAIT_SPLASHES
        val content = findViewById<FrameLayout>(android.R.id.content)
        launchSplashVisible = true
        val splash = ImageView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(16, 19, 24))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(images[Random.nextInt(images.size)])
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(
            splash,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        handler.postDelayed({
            content.removeView(splash)
            launchSplashVisible = false
            if (!isFinishing) applyColorScheme()
        }, SPLASH_DURATION_MS)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && keyboardVisible && ::scriptEdit.isInitialized) {
            val editorBounds = Rect()
            scriptEdit.getGlobalVisibleRect(editorBounds)
            if (!editorBounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                dismissScriptKeyboard()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (hasRecordingPermissions() && !cameraEnabled && !cameraStarting) startCameraPreview()
    }

    override fun onPause() {
        handler.removeCallbacks(saveScriptRunnable)
        saveWorkingScript(showStatus = false)
        stopRecording()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraProvider?.unbindAll()
        cameraEnabled = false
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    private fun bindViews() {
        scriptEdit = findViewById(R.id.scriptEdit)
        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordButton)
        timerText = findViewById(R.id.recordTimerText)
        previewView = findViewById(R.id.cameraPreview)
        previewStage = findViewById(R.id.cameraPreviewStage)
        mainRoot = findViewById(R.id.mainRoot)
        titleText = findViewById(R.id.titleText)
        themeToggleButton = findViewById(R.id.themeToggleButton)
        cameraFlipButton = findViewById(R.id.cameraFlipButton)
        micStatusText = findViewById(R.id.micStatusText)
        micStatusText.setReady(getString(R.string.mic_ready))
    }

    private fun setupKeyboardInsets() {
        mainRoot.isFocusableInTouchMode = true
        rootPadding = intArrayOf(
            mainRoot.paddingLeft,
            mainRoot.paddingTop,
            mainRoot.paddingRight,
            mainRoot.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeBottom = if (imeVisible) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            val left = rootPadding[0] + safe.left
            val top = rootPadding[1] + safe.top
            val right = rootPadding[2] + safe.right
            val bottom = rootPadding[3] + maxOf(safe.bottom, imeBottom)
            if (view.paddingLeft != left || view.paddingTop != top ||
                view.paddingRight != right || view.paddingBottom != bottom) {
                view.setPadding(left, top, right, bottom)
            }
            setKeyboardControlsVisible(imeVisible)
            insets
        }
        ViewCompat.requestApplyInsets(mainRoot)
    }

    private fun setKeyboardControlsVisible(visible: Boolean) {
        if (keyboardVisible == visible) return
        keyboardVisible = visible
        val controlVisibility = if (visible) View.GONE else View.VISIBLE
        findViewById<View>(R.id.brightnessButton).visibility = controlVisibility
        findViewById<View>(R.id.settingsButton).visibility = controlVisibility
        findViewById<View>(R.id.micStatusText).visibility = controlVisibility
        findViewById<View>(R.id.openVideoButton).visibility = controlVisibility
        cameraFlipButton.visibility = controlVisibility
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            findViewById<View>(R.id.scriptToolbar).visibility = controlVisibility
            findViewById<View>(R.id.fontToolbar).visibility = controlVisibility
        }

        val buttonSize = dp(
            when {
                visible -> 60f
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> 68f
                else -> 72f
            }
        ).toInt()
        recordButton.layoutParams = recordButton.layoutParams.apply {
            width = buttonSize
            height = buttonSize
        }
        recordButton.requestLayout()
    }

    private fun dismissScriptKeyboard() {
        val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethod.hideSoftInputFromWindow(scriptEdit.windowToken, 0)
        scriptEdit.clearFocus()
        mainRoot.requestFocus()
    }

    private fun loadLastScript() {
        // Existing single-script installs already use the library format; keep their ID and content.
        showScript(repository.loadWorkingScript() ?: repository.loadScripts().firstOrNull() ?: repository.createDraft())
    }

    private fun setupControls() {
        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.newButton).setOnClickListener {
            confirmDiscard { showScript(repository.createDraft()) }
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener { showSaveDialog() }
        findViewById<Button>(R.id.openButton).setOnClickListener { showLibrary() }
        findViewById<ImageButton>(R.id.openVideoButton).setOnClickListener { showVideoLibrary() }
        findViewById<Button>(R.id.fontSmallerButton).setOnClickListener { changeFontSize(-2) }
        findViewById<Button>(R.id.fontLargerButton).setOnClickListener { changeFontSize(2) }
        themeToggleButton.setOnClickListener {
            darkModeEnabled = !darkModeEnabled
            getSharedPreferences(THEME_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DARK_MODE, darkModeEnabled)
                .apply()
            applyColorScheme()
        }
        findViewById<ImageButton>(R.id.brightnessButton).setOnClickListener {
            showBrightnessDialog()
        }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            showSettingsDialog()
        }
        cameraFlipButton.setOnClickListener { switchCamera() }
        recordButton.setOnClickListener {
            if (recording != null) {
                stopRecording()
            } else if (!cameraEnabled) {
                ensureCameraPermissionsAndStart()
            } else {
                startRecording()
            }
        }
        scriptEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (changingScript) return
                handler.removeCallbacks(saveScriptRunnable)
                handler.postDelayed(saveScriptRunnable, SCRIPT_SAVE_DELAY_MS)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        updateRecordButton()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            setStatus(getString(R.string.status_clipboard_empty))
            return
        }
        val pasted = clip.getItemAt(0).coerceToText(this).toString()
        confirmDiscard {
            scriptEdit.setText(pasted)
            scriptEdit.setSelection(0)
            saveWorkingScript(showStatus = false)
            setStatus(getString(R.string.status_script_pasted))
        }
    }

    private fun changeFontSize(delta: Int) {
        val current = script ?: return
        script = current.copy(fontSize = ScriptSettings.clampFontSize(current.fontSize + delta))
        updateFontSize()
        saveWorkingScript(showStatus = false)
    }

    private fun updateFontSize() {
        val size = ScriptSettings.clampFontSize(script?.fontSize ?: ScriptSettings.DEFAULT_FONT_SIZE)
        scriptEdit.textSize = size.toFloat()
    }

    private fun saveWorkingScript(showStatus: Boolean) {
        if (!::scriptEdit.isInitialized) return
        val current = script ?: repository.createDraft()
        script = current.copy(content = scriptEdit.text.toString(), fontSize = ScriptSettings.clampFontSize(current.fontSize))
        repository.saveWorkingScript(script!!)
        if (showStatus && recording == null) setStatus(getString(R.string.status_draft_saved))
    }

    private fun showScript(next: Script) {
        handler.removeCallbacks(saveScriptRunnable)
        changingScript = true
        script = next
        scriptEdit.setText(next.content)
        scriptEdit.setSelection(0)
        changingScript = false
        updateFontSize()
        repository.saveWorkingScript(next)
    }

    private fun hasUnsavedChanges(): Boolean {
        val current = script ?: return false
        val saved = repository.loadScript(current.id)
        return if (saved == null) {
            scriptEdit.text.isNotEmpty() || current.fontSize != ScriptSettings.DEFAULT_FONT_SIZE
        } else {
            scriptEdit.text.toString() != saved.content || current.fontSize != saved.fontSize
        }
    }

    private fun confirmDiscard(action: () -> Unit) {
        if (!hasUnsavedChanges()) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.discard_action) { _, _ -> action() }
            .show()
    }

    private fun showSaveDialog() {
        dismissScriptKeyboard()
        saveWorkingScript(showStatus = false)
        val current = script ?: return
        val saved = repository.loadScript(current.id)
        val titleInput = EditText(this).apply {
            setSingleLine(true)
            setText(saved?.title ?: ScriptTitles.fromContent(current.content))
            setSelection(text.length)
            hint = getString(R.string.script_title_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.save_script_title)
            .setView(titleInput)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save_script) { _, _ ->
                val title = titleInput.text.toString().trim().ifBlank { ScriptTitles.fromContent(current.content) }
                val savedScript = repository.save(current.copy(title = title))
                script = savedScript
                repository.saveWorkingScript(savedScript)
                setStatus(getString(R.string.status_script_saved))
            }
            .show()
    }

    private fun showLibrary() {
        dismissScriptKeyboard()
        saveWorkingScript(showStatus = false)
        val scripts = repository.loadScripts()
        val labels = scripts.map { it.title }.toTypedArray()
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.library_title)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.new_script) { _, _ ->
                confirmDiscard { showScript(repository.createDraft()) }
            }
        if (scripts.isEmpty()) builder.setMessage(R.string.library_empty)
        else builder.setItems(labels) { _, index ->
            val selected = scripts[index]
            if (selected.id != script?.id || hasUnsavedChanges()) {
                confirmDiscard { showScript(selected) }
            }
        }
        builder.show()
    }

    private fun ensureCameraPermissionsAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCameraPreview() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hasRecordingPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun selector(facing: Int): CameraSelector =
        if (facing == CameraSelector.LENS_FACING_BACK) CameraSelector.DEFAULT_BACK_CAMERA
        else CameraSelector.DEFAULT_FRONT_CAMERA

    private val qualityOrder = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)

    private fun qualityName(quality: Quality): String = when (quality) {
        Quality.UHD -> "4K UHD"
        Quality.FHD -> "1080p FHD"
        Quality.HD -> "720p HD"
        else -> "SD"
    }

    private fun qualityKey(quality: Quality): String = when (quality) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        else -> "SD"
    }

    private fun qualitiesFor(provider: androidx.camera.lifecycle.ProcessCameraProvider, facing: Int): List<Quality> {
        return runCatching {
            val info = selector(facing).filter(provider.availableCameraInfos).firstOrNull() ?: return emptyList()
            val available = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
            qualityOrder.filter { it in available }
        }.getOrDefault(emptyList())
    }

    private fun startCameraPreview() {
        if (cameraStarting || cameraEnabled || !hasRecordingPermissions()) return
        cameraStarting = true
        updateRecordButton()
        previewStage.setCameraAvailable(false)
        val providerFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraStarting = false
            if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val available = qualitiesFor(provider, cameraFacing)
                if (available.isEmpty()) throw IllegalStateException("No supported recording quality")
                val saved = cameraPrefs.getString("quality", null)
                val chosen = available.firstOrNull { qualityKey(it) == saved } ?: available.first()
                bindCamera(provider, cameraFacing, chosen)
                supportedQualities = available
                selectedQuality = chosen
                cameraPrefs.edit().putString("quality", qualityKey(chosen)).apply()
                if (saved != null && qualityKey(chosen) != saved) {
                    AlertDialog.Builder(this).setMessage(getString(R.string.quality_startup_changed, saved, qualityName(chosen)))
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            } catch (_: Exception) {
                cameraEnabled = false
                previewStage.setCameraAvailable(false)
                setStatus(getString(R.string.status_camera_unavailable))
            }
            updateRecordButton()
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera(provider: androidx.camera.lifecycle.ProcessCameraProvider, facing: Int, quality: Quality): androidx.camera.core.Camera {
        bindingGeneration++
        val displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
            .setTargetRotation(displayRotation)
            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
        val capture = VideoCapture.Builder(recorder)
            .setTargetRotation(displayRotation)
            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            .build()
        provider.unbindAll()
        val camera = provider.bindToLifecycle(this, selector(facing), preview, capture)
        val fpsRanges = Camera2CameraInfo.from(camera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val range30 = fpsRanges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: fpsRanges.filter { it.lower <= 30 && it.upper >= 30 }.minByOrNull { it.upper - it.lower }
        if (range30 != null) {
            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range30).build()
            )
        }
        videoCapture = capture
        // CameraX PreviewView already mirrors the front camera; recordings use MIRROR_MODE_OFF.
        previewView.scaleX = 1f
        cameraEnabled = true
        previewStage.setCameraAvailable(true)
        previewStage.contentDescription = getString(if (facing == CameraSelector.LENS_FACING_FRONT)
            R.string.camera_front_ready else R.string.camera_rear_ready)
        setStatus(getString(if (facing == CameraSelector.LENS_FACING_FRONT)
            R.string.camera_front_ready else R.string.camera_rear_ready))
        return camera
    }

    private fun reconfigureCamera(facing: Int, quality: Quality, available: List<Quality>, userSelected: Boolean = false) {
        val provider = cameraProvider ?: return
        if (recording != null) return
        val oldFacing = cameraFacing
        val oldQuality = selectedQuality
        val oldUserSelected = cameraPrefs.getBoolean("quality_user_selected", false)
        cameraStarting = true
        updateRecordButton()
        try {
            val camera = bindCamera(provider, facing, quality)
            val generation = bindingGeneration
            cameraFacing = facing
            selectedQuality = quality
            supportedQualities = available
            cameraPrefs.edit().putInt("camera_facing", facing).putString("quality", qualityKey(quality))
                .apply { if (userSelected) putBoolean("quality_user_selected", true) }.apply()
            camera.cameraInfo.cameraState.observe(this) { state ->
                if (state.error != null && generation == bindingGeneration && oldQuality != null &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    bindingGeneration++
                    val rollback: () -> Unit = {
                        try {
                            bindCamera(provider, oldFacing, oldQuality)
                            cameraFacing = oldFacing
                            selectedQuality = oldQuality
                            supportedQualities = qualitiesFor(provider, oldFacing)
                            cameraPrefs.edit().putInt("camera_facing", oldFacing)
                                .putString("quality", qualityKey(oldQuality))
                                .putBoolean("quality_user_selected", oldUserSelected).apply()
                        } catch (_: Exception) {
                            cameraEnabled = false
                            previewStage.setCameraAvailable(false)
                        }
                        updateRecordButton()
                        AlertDialog.Builder(this).setMessage(if (cameraEnabled) R.string.camera_quality_failed else R.string.status_camera_unavailable)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                    if (recording != null) {
                        pendingCameraRollback = rollback
                        stopRecording()
                    } else handler.post { rollback() }
                }
            }
        } catch (_: Exception) {
            cameraEnabled = false
            if (oldQuality != null) {
                try { bindCamera(provider, oldFacing, oldQuality) } catch (_: Exception) {
                    previewStage.setCameraAvailable(false)
                }
            }
            AlertDialog.Builder(this).setMessage(if (cameraEnabled) R.string.camera_quality_failed else R.string.status_camera_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
        } finally {
            cameraStarting = false
            updateRecordButton()
        }
    }

    private fun switchCamera() {
        val provider = cameraProvider ?: return
        if (recording != null || cameraStarting) return
        val nextFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT)
            CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        val available = qualitiesFor(provider, nextFacing)
        if (available.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.quality_unavailable).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val current = selectedQuality ?: available.first()
        if (current in available) {
            val nextQuality = if (cameraPrefs.getBoolean("quality_user_selected", false)) current else available.first()
            reconfigureCamera(nextFacing, nextQuality, available)
            if (nextQuality != current && cameraFacing == nextFacing) {
                setStatus(getString(R.string.camera_ready_quality, qualityName(nextQuality)))
            }
        }
        else AlertDialog.Builder(this)
            .setMessage(getString(R.string.camera_quality_changed, qualityName(current), qualityName(available.first())))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> reconfigureCamera(nextFacing, available.first(), available) }
            .show()
    }

    private fun showBrightnessDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f).toInt(), dp(12f).toInt(), dp(24f).toInt(), 0)
        }
        val slider = SeekBar(this).apply {
            contentDescription = getString(R.string.screen_brightness)
            max = 100
            val current = window.attributes.screenBrightness
            progress = if (current < 0f) 50 else (current * 100).toInt().coerceIn(1, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) window.attributes = window.attributes.apply {
                        screenBrightness = value.coerceAtLeast(1) / 100f
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        content.addView(slider)
        AlertDialog.Builder(this).setTitle(R.string.screen_brightness).setView(content)
            .setNeutralButton(R.string.system_default) { _, _ ->
                window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showSettingsDialog() {
        val awake = cameraPrefs.getBoolean("keep_awake", true)
        val options = arrayOf(
            getString(R.string.recording_quality) + ": " + (selectedQuality?.let(::qualityName) ?: "—"),
            getString(R.string.keep_screen_awake) + ": " + getString(if (awake) R.string.setting_on else R.string.setting_off),
            getString(R.string.recording_location),
            getString(R.string.about_app)
        )
        AlertDialog.Builder(this).setTitle(R.string.settings_title).setItems(options) { _, which ->
            // Show the next dialog after Android has dismissed this list dialog.
            handler.post { when (which) {
                0 -> if (recording != null) {
                    AlertDialog.Builder(this).setMessage(R.string.quality_busy).setPositiveButton(android.R.string.ok, null).show()
                } else showQualityDialog()
                1 -> {
                    cameraPrefs.edit().putBoolean("keep_awake", !awake).apply()
                    updateKeepAwake()
                    showSettingsDialog()
                }
                2 -> AlertDialog.Builder(this).setMessage(R.string.recording_location).setPositiveButton(android.R.string.ok, null).show()
                3 -> showAboutDialog()
            } }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showAboutDialog() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.about_app)
            .setMessage(getString(R.string.about_support_message, version))
            .setPositiveButton(R.string.support_buy_me_a_coffee) { _, _ ->
                openSupportLink(BUY_ME_A_COFFEE_URL)
            }
            .setNeutralButton(R.string.support_patreon) { _, _ ->
                openSupportLink(PATREON_URL)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun openSupportLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            setStatus(getString(R.string.support_browser_unavailable))
        }
    }

    private fun showQualityDialog() {
        val available = supportedQualities
        if (available.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.quality_unavailable).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.recording_quality)
            .setSingleChoiceItems(available.map(::qualityName).toTypedArray(), available.indexOf(selectedQuality)) { dialog, which ->
                dialog.dismiss()
                if (available[which] != selectedQuality) reconfigureCamera(cameraFacing, available[which], available, userSelected = true)
                else cameraPrefs.edit().putBoolean("quality_user_selected", true).apply()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun updateKeepAwake() {
        if (recording != null && cameraPrefs.getBoolean("keep_awake", true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateMicMeter(stats: AudioStats) {
        when (stats.audioState) {
            AudioStats.AUDIO_STATE_ACTIVE -> micStatusText.addSample(
                SystemClock.elapsedRealtime(), stats.audioAmplitude.coerceIn(0.0, 1.0).toFloat()
            )
            AudioStats.AUDIO_STATE_DISABLED, AudioStats.AUDIO_STATE_MUTED ->
                micStatusText.setUnavailable(getString(R.string.mic_disabled))
            AudioStats.AUDIO_STATE_SOURCE_SILENCED ->
                micStatusText.setUnavailable(getString(R.string.mic_silenced))
            else -> micStatusText.setUnavailable(getString(R.string.mic_error))
        }
    }

    private fun showVideoLibrary() {
        val videos = queryRecordedVideos()
        if (videos.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.open_video)
                .setMessage(R.string.video_library_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val rows = videos.map { mapOf("name" to it.name, "details" to it.details) }
        val adapter = android.widget.SimpleAdapter(
            this, rows, android.R.layout.simple_list_item_2,
            arrayOf("name", "details"), intArrayOf(android.R.id.text1, android.R.id.text2)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.video_library_title)
            .setAdapter(adapter) { _, index ->
                val selected = videos[index]
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(selected.uri, RecordingOutput.MIME_TYPE)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(intent) }.onFailure {
                    setStatus(getString(R.string.video_player_unavailable))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private data class RecordedVideo(val uri: android.net.Uri, val name: String, val details: String)

    private fun queryRecordedVideos(): List<RecordedVideo> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.Video.VideoColumns.WIDTH,
            MediaStore.Video.VideoColumns.HEIGHT
        )
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            selectionArgs = arrayOf(RecordingOutput.relativePath(Environment.DIRECTORY_MOVIES) + "/")
        } else {
            val directory = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                RecordingOutput.ALBUM_NAME
            ).absolutePath
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            selectionArgs = arrayOf("$directory/%")
        }
        return runCatching {
            contentResolver.query(
                collection, projection, selection, selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                val widthColumn = cursor.getColumnIndex(MediaStore.Video.VideoColumns.WIDTH)
                val heightColumn = cursor.getColumnIndex(MediaStore.Video.VideoColumns.HEIGHT)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn) ?: getString(R.string.video_unknown_name)
                        val durationMs = if (durationColumn >= 0 && !cursor.isNull(durationColumn)) cursor.getLong(durationColumn) else 0L
                        val width = if (widthColumn >= 0 && !cursor.isNull(widthColumn)) cursor.getInt(widthColumn) else 0
                        val height = if (heightColumn >= 0 && !cursor.isNull(heightColumn)) cursor.getInt(heightColumn) else 0
                        val duration = if (durationMs > 0) {
                            val seconds = durationMs / 1000
                            String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
                        } else getString(R.string.video_duration_unavailable)
                        val resolution = if (width > 0 && height > 0) "${width}×${height}" else getString(R.string.video_resolution_unavailable)
                        add(RecordedVideo(
                            android.content.ContentUris.withAppendedId(collection, id), name,
                            getString(R.string.video_details, duration, resolution)
                        ))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun startRecording() {
        if (!cameraEnabled || recording != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensureCameraPermissionsAndStart()
            return
        }
        val capture = videoCapture ?: return
        val filename = RecordingOutput.displayName()
        val options = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, RecordingOutput.MIME_TYPE)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    RecordingOutput.relativePath(Environment.DIRECTORY_MOVIES)
                )
            }
        }).build()

        recording = capture.output.prepareRecording(this, options)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        updateKeepAwake()
                        recordingStartedAt = SystemClock.elapsedRealtime()
                        timerText.text = getString(R.string.record_timer_initial)
                        timerText.visibility = View.VISIBLE
                        handler.post(timerRunnable)
                        setStatus(getString(R.string.status_recording))
                        updateRecordButton()
                    }
                    is VideoRecordEvent.Status -> updateMicMeter(event.recordingStats.audioStats)
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        updateKeepAwake()
                        micStatusText.setReady(getString(R.string.mic_ready))
                        handler.removeCallbacks(timerRunnable)
                        timerText.text = getString(R.string.record_timer_initial)
                        if (event.hasError()) {
                            if (event.outputResults.outputUri != android.net.Uri.EMPTY) {
                                contentResolver.delete(event.outputResults.outputUri, null, null)
                            }
                            setStatus(getString(R.string.status_recording_failed))
                        } else {
                            setStatus(recordingDetails(event.outputResults.outputUri))
                        }
                        updateRecordButton()
                        pendingCameraRollback?.also { pendingCameraRollback = null; it() }
                    }
                }
            }
        updateRecordButton()
        updateKeepAwake()
    }

    private fun stopRecording() {
        val active = recording ?: return
        setStatus(getString(R.string.status_finishing))
        recordButton.isEnabled = false
        active.stop()
    }

    private fun recordingDetails(uri: android.net.Uri): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if ((rotation == 90 || rotation == 270) && width != null && height != null) {
                val oldWidth = width
                width = height
                height = oldWidth
            }
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()?.let { String.format(Locale.US, "%.0f fps", it) }
            val dimensions = if (width != null && height != null) "${width}×${height}" else null
            val detail = listOfNotNull(dimensions, fps).joinToString(" · ")
            if (detail.isEmpty()) getString(R.string.status_recording_saved) else getString(R.string.status_recording_saved_details, detail)
        } catch (_: Exception) {
            getString(R.string.status_recording_saved)
        } finally {
            retriever.release()
        }
    }

    private fun updateRecordButton() {
        if (!::recordButton.isInitialized) return
        recordButton.setImageResource(
            if (recording != null) R.drawable.ic_record_active else R.drawable.ic_record_idle
        )
        recordButton.contentDescription = when {
            recording != null -> getString(R.string.record_button_stop_desc)
            cameraEnabled -> getString(R.string.record_button_start_desc)
            else -> getString(R.string.open_camera)
        }
        recordButton.isEnabled = recording == null || cameraEnabled
        cameraFlipButton.isEnabled = recording == null && cameraEnabled && !cameraStarting
        cameraFlipButton.alpha = if (cameraFlipButton.isEnabled) 1f else 0.4f
        findViewById<ImageButton>(R.id.openVideoButton).isEnabled = recording == null
        findViewById<ImageButton>(R.id.openVideoButton).alpha = if (recording == null) 1f else 0.4f
    }

    private fun setStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun isSystemDarkMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun applyColorScheme() {
        val backgroundColor = color(if (darkModeEnabled) 0xFF101318 else 0xFFFFF9F2)
        val primaryTextColor = color(if (darkModeEnabled) 0xFFFFFFFF else 0xFF222222)
        val secondaryTextColor = color(if (darkModeEnabled) 0xFFB8C0CC else 0xFF707070)
        val editorBackgroundColor = color(if (darkModeEnabled) 0xFF1B2027 else 0xFFFFFFFF)
        val editorHintColor = color(if (darkModeEnabled) 0xFF89929F else 0xFF737B87)
        val editorBorderColor = color(if (darkModeEnabled) 0xFF414853 else 0xFFE7DAD0)

        mainRoot.setBackgroundColor(backgroundColor)
        titleText.setTextColor(primaryTextColor)
        statusText.setTextColor(secondaryTextColor)
        scriptEdit.setTextColor(primaryTextColor)
        scriptEdit.setHintTextColor(editorHintColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scriptEdit.verticalScrollbarThumbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color(if (darkModeEnabled) 0xFF89929F else 0xFF9A9289))
                cornerRadius = dp(3f)
                setSize(dp(4f).toInt(), dp(24f).toInt())
            }
        }
        scriptEdit.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(editorBackgroundColor)
            cornerRadius = dp(12f)
            setStroke(dp(1f).toInt(), editorBorderColor)
        }
        themeToggleButton.text = getString(if (darkModeEnabled) R.string.theme_dark else R.string.theme_light)
        themeToggleButton.contentDescription = getString(
            if (darkModeEnabled) R.string.switch_to_light_mode else R.string.switch_to_dark_mode
        )
        themeToggleButton.setTextColor(primaryTextColor)
        themeToggleButton.backgroundTintList = ColorStateList.valueOf(
            color(if (darkModeEnabled) 0xFF343B45 else 0xFFE8E2DB)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                if (launchSplashVisible || darkModeEnabled) 0
                else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            val currentFlags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (launchSplashVisible || darkModeEnabled) {
                currentFlags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                currentFlags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    private fun color(value: Long): Int = value.toInt()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/warrengerdes"
        private const val PATREON_URL = "https://www.patreon.com/c/chanyawazza"
        private const val SPLASH_DURATION_MS = 3_000L
        private val PORTRAIT_SPLASHES = intArrayOf(
            R.drawable.splash_portrait_1,
            R.drawable.splash_portrait_2,
            R.drawable.splash_portrait_3,
            R.drawable.splash_portrait_4
        )
        private val LANDSCAPE_SPLASHES = intArrayOf(
            R.drawable.splash_landscape_1,
            R.drawable.splash_landscape_2,
            R.drawable.splash_landscape_3
        )
        private const val SCRIPT_SAVE_DELAY_MS = 400L
        private const val THEME_PREFERENCES = "appearance"
        private const val KEY_DARK_MODE = "dark_mode"
    }
}
