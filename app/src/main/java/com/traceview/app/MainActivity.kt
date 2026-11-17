package com.traceview.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.traceview.app.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("traceview", MODE_PRIVATE) }
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraStarting = false
    private var cameraSkipped = false
    private var torchOn = false

    private var uiHidden = false
    private var fullscreen = false
    private var dpadShown = false

    private val transformButtons: List<View> by lazy {
        listOf(
            b.btnZoomIn, b.btnZoomOut, b.btnRotLeft, b.btnRotRight, b.btnFlipH, b.btnFlipV,
            b.btnMove, b.btnFit, b.btnReset, b.btnUp, b.btnDown, b.btnLeft, b.btnRight
        )
    }

    // ---- activity results ----------------------------------------------------------------

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionPanel(denied = true)
        }

    private val pickFromGallery =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) importImage(uri)
        }

    private val pickFromFiles =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importImage(uri)
        }

    // ---- lifecycle -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Prevent the screen from sleeping while tracing.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Camera + overlay stay full-bleed; only the controls avoid status/nav bars and notches.
        ViewCompat.setOnApplyWindowInsetsListener(b.uiLayer) { v, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    uiHidden -> setUiHidden(false)
                    dpadShown -> setDpad(false)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        restoreSettings()
        setupControls()
        restoreLastImage()
        if (savedInstanceState == null) handleShareIntent(intent)
        requestCameraIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // Covers returning from system Settings after granting the permission.
        if (hasCameraPermission() && camera == null && !cameraStarting) startCamera()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(saveRunnable)
        saveRunnable.run()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreen) applyFullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Volume keys change opacity — handy while one hand holds the pencil. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 5f
            KeyEvent.KEYCODE_VOLUME_DOWN -> -5f
            else -> return super.onKeyDown(keyCode, event)
        }
        val v = (b.opacitySlider.value + delta).coerceIn(10f, 100f)
        b.opacitySlider.value = v
        scheduleSave()
        return true
    }

    // ---- setup -----------------------------------------------------------------------------

    private fun setupControls() {
        b.overlay.onChanged = { scheduleSave() }
        b.overlay.onTap = { x, y -> focusAt(x, y) }

        b.btnImport.setOnClickListener { chooseImageSource() }
        b.btnEmptyImport.setOnClickListener { chooseImageSource() }
        b.btnSwitch.setOnClickListener { switchCamera() }
        b.btnFlash.setOnClickListener { toggleTorch() }
        b.btnGrid.setOnClickListener {
            b.overlay.gridMode = (b.overlay.gridMode + 1) % 3
            updateGridUi()
            toast(
                getString(
                    when (b.overlay.gridMode) {
                        1 -> R.string.grid_large
                        2 -> R.string.grid_small
                        else -> R.string.grid_off
                    }
                )
            )
            scheduleSave()
        }
        b.btnFullscreen.setOnClickListener {
            fullscreen = !fullscreen
            applyFullscreen()
        }
        b.btnHideUi.setOnClickListener { setUiHidden(true) }
        b.btnShowUi.setOnClickListener { setUiHidden(false) }

        // Tap = one step, hold = continuous.
        repeatOnHold(b.btnZoomIn) { first -> b.overlay.zoomBy(if (first) 1.05f else 1.015f) }
        repeatOnHold(b.btnZoomOut) { first -> b.overlay.zoomBy(if (first) 1 / 1.05f else 1 / 1.015f) }
        repeatOnHold(b.btnRotLeft) { first -> b.overlay.rotateBy(if (first) -5f else -0.5f) }
        repeatOnHold(b.btnRotRight) { first -> b.overlay.rotateBy(if (first) 5f else 0.5f) }
        repeatOnHold(b.btnUp) { first -> b.overlay.moveBy(0f, -nudge(first)) }
        repeatOnHold(b.btnDown) { first -> b.overlay.moveBy(0f, nudge(first)) }
        repeatOnHold(b.btnLeft) { first -> b.overlay.moveBy(-nudge(first), 0f) }
        repeatOnHold(b.btnRight) { first -> b.overlay.moveBy(nudge(first), 0f) }

        b.btnFlipH.setOnClickListener { b.overlay.flipHorizontal() }
        b.btnFlipV.setOnClickListener { b.overlay.flipVertical() }
        b.btnMove.setOnClickListener { setDpad(!dpadShown) }
        b.btnFit.setOnClickListener {
            b.overlay.fitToScreen()
            toast(getString(R.string.fitted))
        }
        b.btnReset.setOnClickListener {
            b.overlay.resetTransform()
            toast(getString(R.string.reset_done))
        }
        b.btnLock.setOnClickListener {
            b.overlay.locked = !b.overlay.locked
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            updateLockUi()
            toast(getString(if (b.overlay.locked) R.string.locked else R.string.unlocked))
            scheduleSave()
        }

        b.opacitySlider.addOnChangeListener { _, value, fromUser ->
            b.overlay.opacity = value / 100f
            updateOpacityText()
            if (fromUser) scheduleSave()
        }

        b.btnPermSkip.setOnClickListener {
            cameraSkipped = true
            b.permPanel.isVisible = false
            updateEmptyState()
        }
    }

    private fun nudge(first: Boolean): Float =
        resources.displayMetrics.density * (if (first) 1f else 3f)

    @SuppressLint("ClickableViewAccessibility")
    private fun repeatOnHold(v: View, action: (Boolean) -> Unit) {
        var repeater: Runnable? = null
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    action(true)
                    val r = object : Runnable {
                        override fun run() {
                            action(false)
                            main.postDelayed(this, 40)
                        }
                    }
                    repeater = r
                    main.postDelayed(r, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    repeater?.let { main.removeCallbacks(it) }
                    repeater = null
                }
            }
            true
        }
        // Accessibility (TalkBack) activation still performs a single step.
        v.setOnClickListener { action(true) }
    }

    // ---- settings persistence ---------------------------------------------------------------

    private val saveRunnable = Runnable {
        if (!::b.isInitialized) return@Runnable
        val s = b.overlay.state
        prefs.edit()
            .putFloat("tx", s.tx)
            .putFloat("ty", s.ty)
            .putFloat("scale", s.scale)
            .putFloat("rot", s.rot)
            .putBoolean("flipH", s.flipH)
            .putBoolean("flipV", s.flipV)
            .putFloat("opacity", b.overlay.opacity)
            .putInt("grid", b.overlay.gridMode)
            .putBoolean("locked", b.overlay.locked)
            .putInt("lens", lensFacing)
            .apply()
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 300)
    }

    private fun restoreSettings() {
        b.overlay.applyState(
            OverlayView.State(
                tx = prefs.getFloat("tx", 0f),
                ty = prefs.getFloat("ty", 0f),
                scale = prefs.getFloat("scale", 1f),
                rot = prefs.getFloat("rot", 0f),
                flipH = prefs.getBoolean("flipH", false),
                flipV = prefs.getBoolean("flipV", false)
            )
        )
        val op = prefs.getFloat("opacity", 0.5f).coerceIn(0.1f, 1f)
        b.overlay.opacity = op
        b.opacitySlider.value = (op * 100).roundToInt().coerceIn(10, 100).toFloat()
        b.overlay.gridMode = prefs.getInt("grid", 0).coerceIn(0, 2)
        b.overlay.locked = prefs.getBoolean("locked", false)
        lensFacing = prefs.getInt("lens", CameraSelector.LENS_FACING_BACK)
        updateOpacityText()
        updateGridUi()
        updateLockUi()
        updateTorchUi(available = false)
    }

    private fun restoreLastImage() {
        io.execute {
            val bmp = try {
                ImageLoader.load(this)
            } catch (t: Throwable) {
                null
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (bmp != null && b.overlay.bitmap == null) b.overlay.setImage(bmp, reset = false)
                updateEmptyState()
                updateLockUi()
            }
        }
    }

    // ---- camera ----------------------------------------------------------------------------

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraIfNeeded() {
        when {
            hasCameraPermission() -> Unit // onResume starts the camera
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ->
                showPermissionPanel(denied = false)
            else -> requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionPanel(denied: Boolean) {
        val permanentlyDenied =
            denied && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        b.permTitle.setText(R.string.perm_title)
        b.permMessage.setText(if (permanentlyDenied) R.string.perm_blocked else R.string.perm_explain)
        b.btnPermRetry.setText(if (permanentlyDenied) R.string.open_settings else R.string.allow_camera)
        b.btnPermRetry.setOnClickListener {
            if (permanentlyDenied) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            } else {
                requestCamera.launch(Manifest.permission.CAMERA)
            }
        }
        cameraSkipped = false
        b.permPanel.isVisible = true
        updateEmptyState()
    }

    private fun showCameraError() {
        b.permTitle.setText(R.string.perm_title)
        b.permMessage.setText(R.string.camera_failed)
        b.btnPermRetry.setText(R.string.try_again)
        b.btnPermRetry.setOnClickListener { startCamera() }
        b.permPanel.isVisible = true
        updateEmptyState()
    }

    private fun startCamera() {
        if (cameraStarting) return
        cameraStarting = true
        b.permPanel.isVisible = false
        updateEmptyState()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraStarting = false
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                showCameraError()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val hasBack = try {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        } catch (e: Exception) {
            false
        }
        val hasFront = try {
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        } catch (e: Exception) {
            false
        }
        if (!hasBack && !hasFront) {
            showCameraError()
            return
        }
        if (lensFacing == CameraSelector.LENS_FACING_BACK && !hasBack) lensFacing = CameraSelector.LENS_FACING_FRONT
        if (lensFacing == CameraSelector.LENS_FACING_FRONT && !hasFront) lensFacing = CameraSelector.LENS_FACING_BACK
        b.btnSwitch.isVisible = hasBack && hasFront

        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
        preview.setSurfaceProvider(b.previewView.surfaceProvider)

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        camera?.cameraInfo?.torchState?.removeObservers(this)
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(this, selector, preview)
            camera = cam
            torchOn = false
            val flash = cam.cameraInfo.hasFlashUnit()
            updateTorchUi(available = flash)
            cam.cameraInfo.torchState.observe(this) { st ->
                torchOn = st == TorchState.ON
                updateTorchUi(available = flash)
            }
        } catch (e: Exception) {
            camera = null
            showCameraError()
        }
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCamera()
        scheduleSave()
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            toast(getString(R.string.no_flash))
            return
        }
        cam.cameraControl.enableTorch(!torchOn)
    }

    /** Tap on the camera view to focus on the paper. */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = b.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        try {
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
        }
    }

    // ---- image import ----------------------------------------------------------------------

    private fun chooseImageSource() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_title)
            .setItems(arrayOf(getString(R.string.from_gallery), getString(R.string.from_files))) { _, which ->
                if (which == 0) {
                    pickFromGallery.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                } else {
                    pickFromFiles.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                }
            }
            .show()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri != null) importImage(uri)
    }

    private fun importImage(uri: Uri) {
        if (!ImageLoader.isAllowed(contentResolver, uri)) {
            toast(getString(R.string.bad_format))
            return
        }
        toast(getString(R.string.loading))
        io.execute {
            val bmp = try {
                ImageLoader.decode(contentResolver, uri)
            } catch (t: Throwable) {
                null
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (bmp == null) toast(getString(R.string.load_failed)) else showPreview(bmp)
            }
        }
    }

    private fun showPreview(bmp: Bitmap) {
        val v = layoutInflater.inflate(R.layout.dialog_preview, null)
        v.findViewById<ImageView>(R.id.previewImage).setImageBitmap(bmp)
        v.findViewById<TextView>(R.id.previewInfo).text =
            getString(R.string.preview_info, bmp.width, bmp.height)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preview_title)
            .setView(v)
            .setPositiveButton(R.string.start_tracing) { _, _ -> useImage(bmp) }
            .setNegativeButton(R.string.choose_another) { _, _ -> chooseImageSource() }
            .show()
    }

    private fun useImage(bmp: Bitmap) {
        b.overlay.locked = false
        b.overlay.setImage(bmp, reset = true)
        updateLockUi()
        updateEmptyState()
        toast(getString(R.string.hint_gestures))
        io.execute {
            try {
                ImageLoader.save(this, bmp)
            } catch (e: Throwable) {
            }
        }
    }

    // ---- UI state --------------------------------------------------------------------------

    private fun updateEmptyState() {
        b.emptyState.isVisible = b.overlay.bitmap == null && !b.permPanel.isVisible
    }

    private fun updateOpacityText() {
        b.opacityText.text = "${(b.overlay.opacity * 100).roundToInt()}%"
    }

    private fun updateLockUi() {
        val locked = b.overlay.locked
        b.btnLock.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_unlock)
        setActive(b.btnLock, locked)
        val enabled = !locked && b.overlay.bitmap != null
        transformButtons.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.35f
        }
        if (locked && dpadShown) setDpad(false)
    }

    private fun updateGridUi() = setActive(b.btnGrid, b.overlay.gridMode > 0)

    private fun updateTorchUi(available: Boolean) {
        b.btnFlash.alpha = if (available) 1f else 0.35f
        setActive(b.btnFlash, available && torchOn)
    }

    private fun setActive(btn: ImageButton, on: Boolean) {
        btn.setBackgroundResource(if (on) R.drawable.bg_btn_active else R.drawable.bg_btn)
    }

    private fun setDpad(show: Boolean) {
        dpadShown = show
        setActive(b.btnMove, show)
        if (!uiHidden) fade(b.dpad, show)
    }

    /** Focus mode: hide everything except the opacity slider + lock, so you can draw. */
    private fun setUiHidden(hidden: Boolean) {
        uiHidden = hidden
        fade(b.topBar, !hidden)
        fade(b.rail, !hidden)
        fade(b.dpad, !hidden && dpadShown)
        fade(b.btnShowUi, hidden)
    }

    private fun applyFullscreen() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        b.btnFullscreen.setImageResource(
            if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        setActive(b.btnFullscreen, fullscreen)
    }

    private fun fade(v: View, show: Boolean) {
        v.animate().cancel()
        if (show) {
            if (!v.isVisible) {
                v.alpha = 0f
                v.isVisible = true
            }
            v.animate().alpha(1f).setDuration(180).start()
        } else if (v.isVisible) {
            v.animate().alpha(0f).setDuration(180).withEndAction { v.isVisible = false }.start()
        }
    }

    private val hideToast = Runnable { fade(b.toast, false) }

    private fun toast(msg: String) {
        b.toast.text = msg
        fade(b.toast, true)
        main.removeCallbacks(hideToast)
        main.postDelayed(hideToast, 2200)
    }
}
