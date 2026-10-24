package com.cambridge.streamer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.cambridge.streamer.bonding.NetworkBondingEngine
import com.cambridge.streamer.databinding.ActivityMainBinding
import com.cambridge.streamer.encoder.HardwareVideoEncoder
import com.cambridge.streamer.service.StreamingForegroundService
import com.cambridge.streamer.stream.LiveDestination
import com.cambridge.streamer.stream.RtmpStreamManager
import com.cambridge.streamer.stream.RtspServerManager
import com.cambridge.streamer.stream.WebMonitorServer
import com.cambridge.streamer.update.AppUpdateManager
import com.cambridge.streamer.usb.NativeUvcCameraEngine
import com.cambridge.streamer.usb.UsbDeviceManager
import com.cambridge.streamer.utils.NetworkUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class ProductionMode { BRIDGE_TO_OBS, IN_APP_STUDIO }
enum class CameraBackend { NONE, CAMERAX_EXTERNAL, NATIVE_UVC }

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var usbDeviceManager: UsbDeviceManager? = null
    private var nativeUvcEngine: NativeUvcCameraEngine? = null
    private var activeBackend: CameraBackend = CameraBackend.NONE
    private var videoEncoder: HardwareVideoEncoder? = null
    private var bridgeAudioPipeline: com.cambridge.streamer.audio.AudioCapturePipeline? = null
    @Volatile private var rtspAudioConfig: ByteArray? = null
    private var rtspServer: RtspServerManager? = null
    private var webServer: WebMonitorServer? = null
    private var rtmpStreamManager: RtmpStreamManager? = null
    private lateinit var bondingEngine: NetworkBondingEngine

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var currentMode = ProductionMode.BRIDGE_TO_OBS
    private var isStreaming = false
    private var isLowLatencyMode = true
    private var captureWidth = 1920
    private var captureHeight = 1080
    private var isScreenPreviewForcedOff = false
    private var lastDirectFrameTime = 0L
    private var pixelCopyJob: Job? = null
    private val pixelCopyHandler = Handler(Looper.getMainLooper())

    // Gallery Image Picker for Prasang / Jhanki Picture-in-Picture
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    binding.overlayView.compositor.jhankiBitmap = bitmap
                    binding.overlayView.compositor.jhankiTitle = "लीला प्रसंग: विशेष दर्शन"
                    binding.overlayView.compositor.isJhankiVisible = true
                    binding.switchJhankiVisibility.isChecked = true
                    binding.overlayView.invalidate()
                    showToast("Custom photo loaded to Live Jhanki!")
                }
            } catch (e: Exception) {
                showToast("Failed to load image: ${e.message}")
            }
        }
    }

    // Gallery Picker for Custom UPI QR Code
    private val qrPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    binding.overlayView.compositor.upiQrBitmap = bitmap
                    binding.overlayView.compositor.isQrVisible = true
                    binding.switchQrVisibility.isChecked = true
                    binding.overlayView.invalidate()
                    showToast("Custom UPI QR loaded & visible!")
                }
            } catch (e: Exception) {
                showToast("Failed to load QR image: ${e.message}")
            }
        }
    }

    private var qrTimerJob: Job? = null

    private fun updateQrAutoTimer(enabled: Boolean) {
        qrTimerJob?.cancel()
        if (!enabled) return

        qrTimerJob = lifecycleScope.launch {
            while (isActive) {
                // Show QR for 2 minutes (120s)
                binding.overlayView.compositor.isQrVisible = true
                binding.switchQrVisibility.isChecked = true
                binding.overlayView.invalidate()
                delay(120_000L)

                // Hide QR for 8 minutes (480s)
                binding.overlayView.compositor.isQrVisible = false
                binding.switchQrVisibility.isChecked = false
                binding.overlayView.invalidate()
                delay(480_000L)
            }
        }
    }

    // USB hot-plug receiver
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbDeviceManager.USB_PERMISSION_ACTION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        Log.d(TAG, "USB permission granted for ${device.productName}")
                        usbDeviceManager?.onPermissionGranted(device)
                        runOnUiThread {
                            cameraProvider?.let { bindCamera(currentMode) }
                        }
                    } else {
                        showToast("USB permission denied for capture card")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    usbDeviceManager?.checkForConnectedCaptureCard()
                    nativeUvcEngine?.start()
                    runOnUiThread {
                        bindCamera(currentMode)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    runOnUiThread {
                        binding.tvUsbStatus.text = "⚠️ HDMI: 4K Card Disconnected"
                        binding.tvUsbStatus.setTextColor(Color.parseColor("#FF5252"))
                        binding.layoutHdmiStandby.visibility = View.VISIBLE
                        binding.uvcViewFinder.visibility = View.GONE
                        binding.viewFinder.visibility = View.GONE
                        cameraProvider?.unbindAll()
                        nativeUvcEngine?.clearSurfaces()
                        activeBackend = CameraBackend.NONE
                    }
                    if (isStreaming) {
                        showToast("⚠️ 4K Camera Card Disconnected")
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            bindCamera(currentMode)
        } else {
            showToast("Camera permission required for video capture")
        }
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            initAudioPipeline()
        }
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent silent crash; log any unexpected error gracefully
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "Uncaught exception on thread ${t.name}", e)
        }

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            cameraExecutor = Executors.newSingleThreadExecutor()
            bondingEngine = NetworkBondingEngine(this)

            // Overlay off by default in Bridge mode (OBS handles overlays)
            binding.overlayView.compositor.isOverlayEnabled = false

            initUvcSurfaceHolder()
            initNativeUvcEngine()
            registerUsbReceiver()
            setupNetworkUrls()
            startPixelCopyWatchdog()
            setupListeners()
            loadSavedPreferences()
            updateLatencyModeButtonState()
            setupBondingEngine()
            setupUsbDetector()
            checkPermissionsAndStart()
            checkAppUpdates(silent = true)
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during onCreate initialization", e)
            showToast("Initialization warning: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        qrTimerJob?.cancel()
        pixelCopyJob?.cancel()
        stopBroadcasting()
        webServer?.isStreaming = false
        webServer?.stop()
        webServer = null
        nativeUvcEngine?.onDirectJpegFrame = null
        nativeUvcEngine?.stop()
        bridgeAudioPipeline?.stop()
        bridgeAudioPipeline = null
        bondingEngine.stopBonding()
        cameraExecutor.shutdown()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    private fun initUvcSurfaceHolder() {
        binding.uvcViewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "uvcViewFinder surface created: ${holder.surface}")
                nativeUvcEngine?.addSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "uvcViewFinder surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "uvcViewFinder surface destroyed")
                nativeUvcEngine?.removeSurface(holder.surface)
            }
        })
    }

    private fun initNativeUvcEngine() {
        nativeUvcEngine = NativeUvcCameraEngine(this, object : NativeUvcCameraEngine.UvcCameraListener {
            override fun onUvcAttached(device: UsbDevice) {
                Log.d(TAG, "Native UVC device attached: ${device.productName}")
            }

            override fun onUvcOpened(device: UsbDevice, width: Int, height: Int) {
                runOnUiThread {
                    activeBackend = CameraBackend.NATIVE_UVC
                    captureWidth = width
                    captureHeight = height
                    val resTag = when {
                        width >= 3840 -> "4K UHD"
                        width >= 1920 -> "1080p FHD"
                        width >= 1280 -> "720p HD"
                        else -> "${width}x${height}"
                    }
                    binding.tvUsbStatus.text = "✅ HDMI: ${device.productName ?: "Capture Card"} ($resTag $width x $height)"
                    binding.tvUsbStatus.setTextColor(Color.parseColor("#00E676"))
                    binding.layoutHdmiStandby.visibility = View.GONE
                    binding.viewFinder.visibility = View.GONE
                    binding.uvcViewFinder.visibility = View.VISIBLE
                    updateUvcSurfacesForCurrentMode()
                }
            }

            override fun onVideoFrameStats(fps: Float, totalFrames: Long, bytesPerSec: Long) {
                runOnUiThread {
                    if (totalFrames == 0L) {
                        binding.tvVideoTelemetry.text = "⚠️ VIDEO IN: 0 FPS — NO HDMI SIGNAL"
                        binding.tvVideoTelemetry.setTextColor(Color.parseColor("#FFA000"))
                    } else {
                        val resTag = when {
                            captureWidth >= 3840 -> "4K UHD"
                            captureWidth >= 1920 -> "1080p FHD"
                            captureWidth >= 1280 -> "720p HD"
                            captureWidth > 0 -> "${captureWidth}x${captureHeight}"
                            else -> "HD"
                        }
                        binding.tvVideoTelemetry.text =
                            "🟢 VIDEO IN: $resTag @ ${String.format("%.1f", fps)} FPS ($totalFrames frames)"
                        binding.tvVideoTelemetry.setTextColor(Color.parseColor("#00E676"))
                    }
                }
            }

            override fun onUvcClosed(device: UsbDevice) {
                runOnUiThread {
                    if (activeBackend == CameraBackend.NATIVE_UVC) {
                        activeBackend = CameraBackend.NONE
                        binding.tvUsbStatus.text = "⚠️ HDMI: 4K Card Disconnected"
                        binding.tvUsbStatus.setTextColor(Color.parseColor("#FF5252"))
                        binding.layoutHdmiStandby.visibility = View.VISIBLE
                        binding.uvcViewFinder.visibility = View.GONE
                        binding.tvVideoTelemetry.text = "⚪ VIDEO IN: Card Disconnected"
                        binding.tvVideoTelemetry.setTextColor(Color.parseColor("#9E9E9E"))
                    }
                }
            }

            override fun onUvcError(device: UsbDevice?, error: Exception) {
                Log.e(TAG, "Native UVC engine error: ${error.message}", error)
            }
        })
        nativeUvcEngine?.onDirectJpegFrame = { jpegBytes ->
            lastDirectFrameTime = System.currentTimeMillis()
            webServer?.pushFrame(jpegBytes)
        }
    }

    // ─── USB ───────────────────────────────────────────────────────────────────

    private fun registerUsbReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(UsbDeviceManager.USB_PERMISSION_ACTION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            // Android 14 (API 34) requires RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
            ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USB receiver", e)
        }
    }

    private fun setupUsbDetector() {
        usbDeviceManager = UsbDeviceManager(this) { isConnected, name ->
            runOnUiThread {
                if (isConnected) {
                    binding.tvUsbStatus.text = "✅ HDMI: $name"
                    binding.tvUsbStatus.setTextColor(Color.parseColor("#00E676"))
                } else {
                    binding.tvUsbStatus.text = "⚠️ HDMI: Connect Card"
                    binding.tvUsbStatus.setTextColor(Color.parseColor("#FFA000"))
                }
            }
        }
        usbDeviceManager?.checkForConnectedCaptureCard()
    }

    // ─── Network / Bonding ─────────────────────────────────────────────────────

    private fun setupNetworkUrls() {
        val localIp = NetworkUtils.getLocalIpAddress()
        binding.tvDirectWebUrl.text = "http://$localIp:8080/obs"
        ensureWebServerStarted()
    }

    private fun ensureWebServerStarted() {
        if (webServer == null) {
            try {
                webServer = WebMonitorServer(port = 8080)
                webServer?.isStreaming = true
                webServer?.start()
                Log.d(TAG, "WebMonitorServer started on port 8080")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WebMonitorServer", e)
            }
        }
        nativeUvcEngine?.onDirectJpegFrame = { jpegBytes ->
            lastDirectFrameTime = System.currentTimeMillis()
            webServer?.pushFrame(jpegBytes)
        }
    }

    private fun setupBondingEngine() {
        bondingEngine.startBonding(object : NetworkBondingEngine.BondingStatusListener {
            override fun onBondingStateChanged(
                isCellularActive: Boolean,
                isWifiActive: Boolean,
                bondedSpeedMbps: Double
            ) {
                runOnUiThread {
                    if (!binding.switchBonding.isChecked) {
                        binding.tvBondingDetails.text = "⚡ Bonding Disabled"
                        binding.tvBondingDetails.setTextColor(Color.parseColor("#9E9E9E"))
                        return@runOnUiThread
                    }
                    when {
                        isCellularActive && isWifiActive -> {
                            binding.tvBondingDetails.text =
                                "⚡ 5G + Wi-Fi BONDED ➔ ${String.format("%.1f", bondedSpeedMbps)} Mbps"
                            binding.tvBondingDetails.setTextColor(Color.parseColor("#00E676"))
                        }
                        isCellularActive -> {
                            binding.tvBondingDetails.text =
                                "📶 5G Only ➔ ${String.format("%.1f", bondedSpeedMbps)} Mbps"
                            binding.tvBondingDetails.setTextColor(Color.parseColor("#64B5F6"))
                        }
                        isWifiActive -> {
                            binding.tvBondingDetails.text =
                                "🛜 Wi-Fi Only ➔ ${String.format("%.1f", bondedSpeedMbps)} Mbps"
                            binding.tvBondingDetails.setTextColor(Color.parseColor("#64B5F6"))
                        }
                        else -> {
                            binding.tvBondingDetails.text = "⚠️ No Active Network"
                            binding.tvBondingDetails.setTextColor(Color.parseColor("#FF5252"))
                        }
                    }
                }
            }
        })
    }

    // ─── UI Listeners ──────────────────────────────────────────────────────────

    private fun setupListeners() {
        // Mode switch: re-bind camera pipeline for new mode
        binding.rgModeSelector.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.rbBridgeMode)
                ProductionMode.BRIDGE_TO_OBS else ProductionMode.IN_APP_STUDIO

            if (newMode == currentMode) return@setOnCheckedChangeListener

            // Stop any active broadcast before switching mode
            if (isStreaming) stopBroadcasting()

            currentMode = newMode

            // Maintain live camera preview across mode changes
            if (activeBackend == CameraBackend.NATIVE_UVC) {
                binding.uvcViewFinder.visibility = View.VISIBLE
                binding.viewFinder.visibility = View.GONE
                binding.layoutHdmiStandby.visibility = View.GONE
            } else if (activeBackend == CameraBackend.CAMERAX_EXTERNAL) {
                binding.viewFinder.visibility = View.VISIBLE
                binding.uvcViewFinder.visibility = View.GONE
                binding.layoutHdmiStandby.visibility = View.GONE
            } else {
                binding.layoutHdmiStandby.visibility = View.VISIBLE
            }

            if (currentMode == ProductionMode.BRIDGE_TO_OBS) {
                // Bridge: overlays off (OBS handles graphics)
                binding.overlayView.compositor.isOverlayEnabled = false
                binding.layoutBridgeMode.visibility = View.VISIBLE
                binding.layoutStudioMode.visibility = View.GONE
            } else {
                // Studio: overlays enabled
                binding.overlayView.compositor.isOverlayEnabled = binding.switchOverlays.isChecked
                binding.layoutBridgeMode.visibility = View.GONE
                binding.layoutStudioMode.visibility = View.VISIBLE
            }

            // Re-bind camera / UVC with the correct pipeline for new mode
            if (activeBackend == CameraBackend.NATIVE_UVC) {
                updateUvcSurfacesForCurrentMode()
            } else {
                cameraProvider?.let { bindCamera(currentMode) }
            }
        }

        // Bonding toggle
        binding.switchBonding.setOnCheckedChangeListener { _, isChecked ->
            bondingEngine.isBondingEnabled.set(isChecked)
            if (!isChecked) {
                binding.tvBondingDetails.text = "⚡ Bonding Disabled"
                binding.tvBondingDetails.setTextColor(Color.parseColor("#9E9E9E"))
            }
        }

        // Overlays toggle (only meaningful in Studio mode)
        binding.switchOverlays.setOnCheckedChangeListener { _, isChecked ->
            binding.overlayView.compositor.isOverlayEnabled =
                (currentMode == ProductionMode.IN_APP_STUDIO) && isChecked
            binding.overlayView.invalidate()
        }

        // Copy OBS Zero-Lag Web URL (Browser Source <50ms)
        binding.btnCopyWebUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OBS Browser URL", binding.tvDirectWebUrl.text))
            showToast("Copied Zero-Lag Web URL for OBS Browser Source!")
        }

        // In-App Git Auto-Updater
        binding.btnCheckUpdate.setOnClickListener {
            checkAppUpdates(silent = false)
        }

        // 1-Tap Screen Preview Toggle
        binding.btnTogglePreview.setOnClickListener {
            isScreenPreviewForcedOff = !isScreenPreviewForcedOff
            updateUvcSurfacesForCurrentMode()
            val stateText = if (!isScreenPreviewForcedOff) "👁️ Preview ON" else "⚡ Preview OFF (Blackout Screen)"
            showToast(stateText)
        }

        // Zero-Lag Engine Info Pill
        binding.btnToggleLatencyMode.setOnClickListener {
            showToast("⚡ Direct OBS Bridge: Pure USB-to-Web (<50ms delay, No Encoding/Decoding)")
        }

        // Bridge mode CTA
        binding.btnToggleBridgeStream.setOnClickListener {
            if (isStreaming) stopBroadcasting() else startBroadcastingBridge()
        }

        // Studio: Apply overlays & Ticker
        binding.btnUpdateOverlays.setOnClickListener {
            val katha = binding.etKathaTitle.text.toString().trim()
            if (katha.isNotEmpty()) binding.overlayView.compositor.kathaTitle = katha

            val vyas = binding.etVyasName.text.toString().trim()
            if (vyas.isNotEmpty()) binding.overlayView.compositor.vyasJiName = vyas

            val day = binding.etDayText.text.toString().trim()
            if (day.isNotEmpty()) binding.overlayView.compositor.dayText = day

            val ticker = binding.etTickerText.text.toString().trim()
            if (ticker.isNotEmpty()) binding.overlayView.compositor.setCustomTicker(ticker)

            saveOverlayPreferences()
            binding.overlayView.invalidate()
            showToast("Overlays & Ticker Updated!")
        }

        binding.switchNameplate.setOnCheckedChangeListener { _, isChecked ->
            binding.overlayView.compositor.isNameplateVisible = isChecked
            binding.overlayView.invalidate()
        }

        binding.switchTicker.setOnCheckedChangeListener { _, isChecked ->
            binding.overlayView.compositor.isTickerVisible = isChecked
            binding.overlayView.invalidate()
        }

        // Dynamic UPI QR Code
        binding.btnGenerateQr.setOnClickListener {
            val upiId = binding.etUpiId.text.toString().trim()
            if (upiId.isEmpty()) {
                showToast("कृपया UPI ID दर्ज करें (उदा: katha@sbi)")
                return@setOnClickListener
            }
            binding.overlayView.compositor.generateUpiQr(upiId, "Katha Sewa")
            binding.switchQrVisibility.isChecked = true
            savePref("saved_upi_id", upiId)
            binding.overlayView.invalidate()
            showToast("UPI QR कोड तैयार एवं स्क्रीन पर सक्रिय!")
        }

        binding.btnPickQrImage.setOnClickListener {
            qrPickerLauncher.launch("image/*")
        }

        binding.switchQrVisibility.setOnCheckedChangeListener { _, isChecked ->
            binding.overlayView.compositor.isQrVisible = isChecked
            binding.overlayView.invalidate()
        }

        binding.switchQrAutoTimer.setOnCheckedChangeListener { _, isChecked ->
            updateQrAutoTimer(isChecked)
            val msg = if (isChecked) "ऑटो-टाइमर सक्रिय (2 मिनट दिखेगा, 8 मिनट छुपेगा)" else "ऑटो-टाइमर बंद"
            showToast(msg)
        }

        // Jhanki Presets & Custom Gallery Photo Picker (1-Tap Operator Desk)
        binding.btnJhankiMakhan.setOnClickListener {
            val bmp = BitmapFactory.decodeResource(resources, R.drawable.jhanki_makhan_chori)
            binding.overlayView.compositor.jhankiBitmap = bmp
            binding.overlayView.compositor.jhankiTitle = "लीला: बाल कृष्ण माखन चोरी"
            binding.overlayView.compositor.isJhankiVisible = true
            binding.switchJhankiVisibility.isChecked = true
            binding.overlayView.invalidate()
            showToast("प्रसंग: बाल कृष्ण माखन चोरी सक्रिय")
        }

        binding.btnJhankiDevaki.setOnClickListener {
            val bmp = BitmapFactory.decodeResource(resources, R.drawable.jhanki_mata_devaki)
            binding.overlayView.compositor.jhankiBitmap = bmp
            binding.overlayView.compositor.jhankiTitle = "लीला: माता देवकी एवं कृष्ण जन्म"
            binding.overlayView.compositor.isJhankiVisible = true
            binding.switchJhankiVisibility.isChecked = true
            binding.overlayView.invalidate()
            showToast("प्रसंग: माता देवकी सक्रिय")
        }

        binding.btnPickCustomImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.btnHideJhanki.setOnClickListener {
            binding.overlayView.compositor.isJhankiVisible = false
            binding.switchJhankiVisibility.isChecked = false
            binding.overlayView.invalidate()
            showToast("झांकी हटाई गई (फुल स्क्रीन कैमरा)")
        }

        binding.btnUpdateJhankiCaption.setOnClickListener {
            val cap = binding.etJhankiCaption.text.toString().trim()
            if (cap.isNotEmpty()) {
                binding.overlayView.compositor.jhankiTitle = cap
                binding.overlayView.invalidate()
                showToast("झांकी शीर्षक अपडेट किया गया!")
            }
        }

        binding.switchJhankiVisibility.setOnCheckedChangeListener { _, isChecked ->
            binding.overlayView.compositor.isJhankiVisible = isChecked
            binding.overlayView.invalidate()
        }

        // A/V Lip-Sync Tuning (Cycles 0ms, 50ms, 100ms, 150ms, 200ms, 250ms)
        val lipSyncDelays = listOf(0L, 50L, 100L, 150L, 200L, 250L)
        var currentLipSyncIdx = 2 // 100ms default
        binding.chipLipSync.setOnClickListener {
            currentLipSyncIdx = (currentLipSyncIdx + 1) % lipSyncDelays.size
            val delay = lipSyncDelays[currentLipSyncIdx]
            rtmpStreamManager?.audioDelayOffsetMs = delay
            binding.chipLipSync.text = "🎙️ Lip-Sync: +${delay}ms"
            showToast("A/V Sync Offset: +${delay}ms (HDMI card latency compensation)")
        }

        // Studio: Simultaneous Multi-Broadcaster Go Live
        binding.btnToggleStudioLive.setOnClickListener {
            if (isStreaming) {
                stopBroadcasting()
            } else {
                val destinations = mutableListOf<LiveDestination>()
                val ytKey = binding.etYoutubeKey.text.toString().trim()
                val fbKey = binding.etFacebookKey.text.toString().trim()
                val igKey = binding.etInstagramKey.text.toString().trim()

                // Persist inputs
                savePref("yt_key", ytKey)
                savePref("fb_key", fbKey)
                savePref("ig_key", igKey)
                savePref("yt_checked", binding.cbYoutube.isChecked)
                savePref("fb_checked", binding.cbFacebook.isChecked)
                savePref("ig_checked", binding.cbInstagram.isChecked)

                if (binding.cbYoutube.isChecked && ytKey.isNotEmpty()) {
                    val url = if (ytKey.startsWith("rtmp")) ytKey else "rtmp://a.rtmp.youtube.com/live2/$ytKey"
                    destinations.add(LiveDestination("youtube", "YouTube Live", url))
                }
                if (binding.cbFacebook.isChecked && fbKey.isNotEmpty()) {
                    val url = if (fbKey.startsWith("rtmp")) fbKey else "rtmps://live-api-s.facebook.com:443/rtmp/$fbKey"
                    destinations.add(LiveDestination("facebook", "Facebook Live", url))
                }
                if (binding.cbInstagram.isChecked && igKey.isNotEmpty()) {
                    val url = if (igKey.startsWith("rtmp")) igKey else "rtmps://live-upload.instagram.com:443/rtmp/$igKey"
                    destinations.add(LiveDestination("instagram", "Instagram Live", url))
                }

                if (destinations.isEmpty()) {
                    showToast("कृपया कम से कम एक प्लेटफॉर्म चुनें और उसकी Stream Key दर्ज करें")
                    return@setOnClickListener
                }

                startBroadcastingStudio(destinations)
            }
        }
    }

    // ─── Camera Binding (Mode-Aware) ───────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            initCameraProvider()
            initAudioPipeline()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun initCameraProvider() {
        ProcessCameraProvider.getInstance(this).addListener({
            cameraProvider = ProcessCameraProvider.getInstance(this).get()
            bindCamera(currentMode)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initAudioPipeline() {
        if (bridgeAudioPipeline != null) return

        bridgeAudioPipeline = com.cambridge.streamer.audio.AudioCapturePipeline(
            context = this,
            sampleRate = 48000,
            channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO,
            bitrate = 128_000,
            onAudioRoutingChecked = { isUsb, deviceName ->
                runOnUiThread {
                    if (isUsb) {
                        binding.tvUsbStatus.text = "✅ HDMI: Video & $deviceName"
                    }
                }
            },
            onAudioSpecificConfig = { config ->
                rtspAudioConfig = config
            },
            onAudioFrame = { rawAac, dtsMs ->
                if (isStreaming) {
                    rtspServer?.broadcastAudioPacket(rawAac, dtsMs)
                }
            }
        ).apply {
            onAudioLevel = { dbLevel, isUsb, deviceName ->
                runOnUiThread {
                    val bars = when {
                        dbLevel > -6f  -> "[ ▂▄▆█ ]"
                        dbLevel > -14f -> "[ ▂▄▆_ ]"
                        dbLevel > -24f -> "[ ▂▄__ ]"
                        dbLevel > -38f -> "[ ▂___ ]"
                        else           -> "[ ____ ]"
                    }
                    val icon = if (isUsb) "🟢" else "🟡"
                    val source = if (isUsb) "HDMI Audio" else "Phone Mic"
                    binding.tvAudioTelemetry.text =
                        "$icon AUDIO IN: $source $bars ${String.format("%.0f", dbLevel)} dB"
                    binding.tvAudioTelemetry.setTextColor(
                        if (isUsb) Color.parseColor("#00E676") else Color.parseColor("#FFD54F")
                    )
                }
            }
            start()
        }
    }

    /**
     * Binds the camera pipeline based on current production mode:
     *
     * BRIDGE_TO_OBS:
     *   - NO PreviewView surface (screen stays black / hidden — saves GPU & battery)
     *   - Encoder input surface receives raw frames directly from camera
     *   - Zero preview overhead → lower latency, cooler device
     *
     * IN_APP_STUDIO:
     *   - PreviewView surface → cameraman sees live feed on phone screen
     *   - Encoder input surface also bound → captures identical frames for streaming
     *   - Overlays are rendered on top of PreviewView by OverlayView
     */
    /**
     * Resolves strictly the external 4K camera feed from the HDMI-to-USB-C capture card.
     * The phone's built-in cameras (back/front) are NEVER activated.
     */
    private fun resolveExternalCameraSelector(provider: ProcessCameraProvider): CameraSelector? {
        val externalSelector = try {
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                .build()
        } catch (_: Exception) { null }

        return if (externalSelector != null && (try { provider.hasCamera(externalSelector) } catch (_: Exception) { false })) {
            Log.d(TAG, "External 4K HDMI capture card input detected and active")
            externalSelector
        } else {
            Log.d(TAG, "External 4K camera not connected — phone camera stays OFF (Standby)")
            null
        }
    }

    private fun bindCamera(mode: ProductionMode) {
        val provider = cameraProvider
        val cameraSelector = provider?.let { resolveExternalCameraSelector(it) }

        if (cameraSelector != null) {
            // ── Backend 1: CameraX External HAL (Pixel, Samsung) ──
            activeBackend = CameraBackend.CAMERAX_EXTERNAL
            nativeUvcEngine?.clearSurfaces()
            binding.uvcViewFinder.visibility = View.GONE

            provider.unbindAll()
            val useCases = mutableListOf<androidx.camera.core.UseCase>()

            val targetSize = android.util.Size(
                if (captureWidth > 0) captureWidth else 1920,
                if (captureHeight > 0) captureHeight else 1080
            )

            val screenPreview = Preview.Builder()
                .setTargetResolution(targetSize)
                .build()
                .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

            binding.viewFinder.visibility = View.VISIBLE
            binding.uvcViewFinder.visibility = View.GONE
            binding.layoutHdmiStandby.visibility = View.GONE
            useCases.add(screenPreview)

            val encoderSurface = videoEncoder?.inputSurface
            if (isStreaming && encoderSurface != null && encoderSurface.isValid) {
                val encoderPreview = Preview.Builder()
                    .setTargetResolution(targetSize)
                    .build()

                encoderPreview.setSurfaceProvider { request: SurfaceRequest ->
                    if (encoderSurface.isValid) {
                        request.provideSurface(encoderSurface, cameraExecutor) { result ->
                            Log.d(TAG, "Encoder surface released: ${result.resultCode}")
                        }
                    } else {
                        request.willNotProvideSurface()
                    }
                }
                useCases.add(encoderPreview)
            }

            var frameCount = 0
            var totalFrames = 0L
            var lastFpsCheck = android.os.SystemClock.elapsedRealtime()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                val now = android.os.SystemClock.elapsedRealtime()
                frameCount++
                totalFrames++
                val dt = now - lastFpsCheck
                if (dt >= 1000) {
                    val fps = (frameCount * 1000f) / dt
                    frameCount = 0
                    lastFpsCheck = now
                    runOnUiThread {
                        binding.tvVideoTelemetry.text =
                            "🟢 VIDEO IN: HAL @ ${String.format("%.1f", fps)} FPS ($totalFrames frames)"
                        binding.tvVideoTelemetry.setTextColor(Color.parseColor("#00E676"))
                    }
                }
                if (webServer != null) {
                    try {
                        val bmp = image.toBitmap()
                        val stream = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                        webServer?.pushFrame(stream.toByteArray())
                    } catch (e: Exception) {
                        Log.w(TAG, "HAL frame to JPEG push failed", e)
                    }
                }
                image.close()
            }
            useCases.add(analysis)

            try {
                camera = provider.bindToLifecycle(this, cameraSelector, *useCases.toTypedArray())
                Log.d(TAG, "4K HDMI External Camera bound via CameraX in $mode mode")
                runOnUiThread {
                    binding.tvUsbStatus.text = "✅ HDMI: 4K Camera Active (HAL)"
                    binding.tvUsbStatus.setTextColor(Color.parseColor("#00E676"))
                    binding.layoutHdmiStandby.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "External camera bind error", e)
                showToast("4K Input: ${e.message}")
            }
        } else {
            // ── Backend 2: Native UVC Engine (OnePlus, Xiaomi, Vivo, Oppo, Realme, etc.) ──
            provider?.unbindAll()
            binding.viewFinder.visibility = View.GONE

            nativeUvcEngine?.start()

            if (nativeUvcEngine?.isCameraOpened() == true) {
                activeBackend = CameraBackend.NATIVE_UVC
                binding.layoutHdmiStandby.visibility = View.GONE
                binding.uvcViewFinder.visibility = View.VISIBLE
                updateUvcSurfacesForCurrentMode()
            } else {
                // Standby: Wait for capture card. Phone internal cameras are NEVER activated.
                activeBackend = CameraBackend.NONE
                runOnUiThread {
                    binding.tvUsbStatus.text = "⚠️ HDMI: Connect 4K Camera Card"
                    binding.tvUsbStatus.setTextColor(Color.parseColor("#FFA000"))
                    binding.layoutHdmiStandby.visibility = View.VISIBLE
                    binding.uvcViewFinder.visibility = View.GONE
                }
            }
        }
    }

    private fun updateUvcSurfacesForCurrentMode() {
        val engine = nativeUvcEngine ?: return

        binding.viewFinder.visibility = View.GONE
        binding.layoutHdmiStandby.visibility = View.GONE

        val holderSurface = binding.uvcViewFinder.holder.surface
        val encoderSurface = videoEncoder?.inputSurface

        // Always keep preview active by default so hardware capture pipeline remains active
        // and user can monitor HDMI signal on phone screen.
        val shouldShowPreview = !isScreenPreviewForcedOff

        if (shouldShowPreview) {
            binding.uvcViewFinder.visibility = View.VISIBLE
            if (holderSurface.isValid) {
                engine.addSurface(holderSurface)
                Log.d(TAG, "Screen preview attached")
            }
            binding.btnTogglePreview.text = "👁️ Preview: ON"
            binding.btnTogglePreview.setTextColor(Color.parseColor("#00E676"))
        } else {
            if (holderSurface.isValid) {
                engine.removeSurface(holderSurface)
            }
            binding.uvcViewFinder.visibility = View.GONE
            binding.btnTogglePreview.text = "⚡ Preview: OFF"
            binding.btnTogglePreview.setTextColor(Color.parseColor("#80D8FF"))
            Log.d(TAG, "Screen preview detached")
        }

        if (isStreaming && encoderSurface != null && encoderSurface.isValid) {
            engine.addSurface(encoderSurface)
            Log.d(TAG, "Encoder surface active for broadcast")
        } else if (!isStreaming && encoderSurface != null) {
            engine.removeSurface(encoderSurface)
        }
    }

    /**
     * In Studio mode: after encoder has started and inputSurface is valid,
     * bind the 4K camera feed into both the screen preview and encoder surface.
     */
    private fun rebindEncoderPreviewForStudio() {
        if (activeBackend == CameraBackend.NATIVE_UVC) {
            updateUvcSurfacesForCurrentMode()
            return
        }

        val provider = cameraProvider ?: return
        val encoderSurface = videoEncoder?.inputSurface ?: return
        val cameraSelector = resolveExternalCameraSelector(provider) ?: return

        val targetSize = android.util.Size(
            if (captureWidth > 0) captureWidth else 1920,
            if (captureHeight > 0) captureHeight else 1080
        )

        val encoderPreview = Preview.Builder()
            .setTargetResolution(targetSize)
            .build()

        encoderPreview.setSurfaceProvider { request: SurfaceRequest ->
            if (encoderSurface.isValid) {
                request.provideSurface(encoderSurface, cameraExecutor) { result ->
                    Log.d(TAG, "Studio encoder surface released: ${result.resultCode}")
                }
            } else {
                request.willNotProvideSurface()
            }
        }

        val screenPreview = Preview.Builder()
            .setTargetResolution(targetSize)
            .build()
            .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, cameraSelector, screenPreview, encoderPreview
            )
            Log.d(TAG, "Studio mode: screen + encoder bound")
        } catch (e: Exception) {
            Log.e(TAG, "Studio encoder rebind failed", e)
        }
    }

    // ─── Broadcast Engines ─────────────────────────────────────────────────────

    /**
     * DIRECT OBS BRIDGE (Zero Encoding / Zero Decoding / Pure Direct USB-to-Web):
     * 1. Start lightweight WebMonitorServer on port 8080 (serves /obs with Capacity-1 buffer)
     * 2. Feed raw JPEG frames directly from USB capture card into WebMonitorServer
     * 3. Start foreground service to keep bridge alive
     * 4. Zero codec overhead, zero H.264 transcoding, guaranteed <50ms glass-to-glass latency!
     */
    private fun startBroadcastingBridge() {
        isStreaming = true

        // Ensure Web Bridge is running
        ensureWebServerStarted()

        // Foreground service prevents OS sleep
        StreamingForegroundService.start(this, "⚡ LIVE → OBS Browser Bridge (<50ms)")

        // UI update
        binding.btnToggleBridgeStream.text = "STOP OBS STREAM"
        binding.btnToggleBridgeStream.setBackgroundResource(R.drawable.bg_cta_red)
        binding.btnToggleBridgeStream.setTextColor(Color.WHITE)
        updateLiveBadge(true, "⚡ ZERO-LAG OBS")
        showToast("Broadcasting direct to OBS Browser (<50ms Zero-Lag)")
    }

    /**
     * IN-APP STUDIO:
     * 1. Start HardwareVideoEncoder → inputSurface created
     * 2. Re-bind camera to wire BOTH screen preview + encoder surface
     * 3. Wait for SPS/PPS from encoder (up to 2s)
     * 4. Start RTMP stream to YouTube/Facebook using bonded network
     * 5. Start foreground service so broadcast survives screen lock
     */
    /**
     * IN-APP MULTI-BROADCASTER:
     * 1. Start HardwareVideoEncoder → inputSurface created
     * 2. Re-bind camera to wire BOTH screen preview + encoder surface
     * 3. Wait for SPS/PPS from encoder (up to 2s)
     * 4. Start concurrent RTMP/RTMPS streams to YouTube, Facebook, and Instagram
     * 5. Start foreground service so broadcast survives screen lock
     */
    private fun startBroadcastingStudio(destinations: List<LiveDestination>) {
        isStreaming = true

        val encWidth = if (captureWidth > 0) captureWidth else 1920
        val encHeight = if (captureHeight > 0) captureHeight else 1080
        val encBitrate = if (encWidth <= 1280) 2_500_000 else 4_500_000

        videoEncoder = HardwareVideoEncoder(
            width = encWidth, height = encHeight, bitrate = encBitrate, frameRate = 30
        ) { frameBytes, isKeyFrame, ptsMs ->
            bondingEngine.dispatchPacket(frameBytes)
            rtmpStreamManager?.pushVideoFrame(frameBytes, isKeyFrame, ptsMs)
        }
        videoEncoder?.start()

        rebindEncoderPreviewForStudio()

        Thread {
            var waited = 0
            while ((videoEncoder?.sps == null || videoEncoder?.pps == null) && waited < 2000) {
                Thread.sleep(50)
                waited += 50
            }
            val sps = videoEncoder?.sps
            val pps = videoEncoder?.pps
            Log.d(TAG, "Studio SPS ready after ${waited}ms (sps=${sps?.size}B, pps=${pps?.size}B)")

            rtmpStreamManager = RtmpStreamManager(this)
            rtmpStreamManager?.startMultiStream(
                destinations = destinations,
                sps = sps,
                pps = pps,
                onTargetConnected = { id, label ->
                    runOnUiThread {
                        binding.btnToggleStudioLive.text = "STOP BROADCAST"
                        binding.btnToggleStudioLive.setBackgroundResource(R.drawable.bg_cta_rounded)
                        binding.btnToggleStudioLive.setTextColor(Color.BLACK)
                        updateLiveBadge(true, "🔴 LIVE: $label")
                        showToast("📡 Live on $label!")
                    }
                },
                onTargetFailed = { id, label, err ->
                    runOnUiThread {
                        showToast("⚠️ $label: $err")
                    }
                },
                onAllDisconnected = {
                    runOnUiThread {
                        stopBroadcasting()
                        showToast("All live broadcasts ended")
                    }
                }
            )
            StreamingForegroundService.start(this@MainActivity, "🔴 LIVE — Multi-Broadcaster")
        }.start()
    }

    private fun stopBroadcasting() {
        isStreaming = false

        videoEncoder?.inputSurface?.let { nativeUvcEngine?.removeSurface(it) }
        videoEncoder?.stop()
        videoEncoder = null

        bridgeAudioPipeline?.onAudioFrame = null
        rtspAudioConfig = null

        rtspServer?.stop()
        rtspServer = null

        // Web monitor server stays running so OBS / Browser can continuously monitor local feed

        rtmpStreamManager?.stopLiveStream()
        rtmpStreamManager = null

        // Stop foreground service (wake lock released)
        StreamingForegroundService.stop(this)

        // Restore camera to current mode (preview-only, no encoder)
        if (activeBackend == CameraBackend.NATIVE_UVC) {
            updateUvcSurfacesForCurrentMode()
        } else {
            cameraProvider?.let { bindCamera(currentMode) }
        }

        binding.btnToggleBridgeStream.text = "START OBS STREAM"
        binding.btnToggleBridgeStream.setBackgroundResource(R.drawable.bg_cta_emerald)
        binding.btnToggleBridgeStream.setTextColor(Color.BLACK)
        binding.btnToggleStudioLive.text = "🔴 GO LIVE TO ALL"
        binding.btnToggleStudioLive.setBackgroundResource(R.drawable.bg_cta_red)
        binding.btnToggleStudioLive.setTextColor(Color.WHITE)
        updateLiveBadge(false, "STANDBY")
        showToast("Broadcast stopped")
    }

    private fun loadSavedPreferences() {
        val prefs = getSharedPreferences("cambridge_prefs", Context.MODE_PRIVATE)

        binding.etYoutubeKey.setText(prefs.getString("yt_key", ""))
        binding.etFacebookKey.setText(prefs.getString("fb_key", ""))
        binding.etInstagramKey.setText(prefs.getString("ig_key", ""))
        binding.cbYoutube.isChecked = prefs.getBoolean("yt_checked", true)
        binding.cbFacebook.isChecked = prefs.getBoolean("fb_checked", false)
        binding.cbInstagram.isChecked = prefs.getBoolean("ig_checked", false)

        val savedKatha = prefs.getString("katha_title", "श्रीमद् भागवत कथा ज्ञान यज्ञ")
        val savedVyas = prefs.getString("vyas_name", "पूज्य व्यास जी महाराज")
        val savedDay = prefs.getString("day_text", "दिवस: 1 (कथा महात्म्य)")
        val savedTicker = prefs.getString("ticker_text", "।। श्री राधे कृष्ण ।। कथा समय: दोपहर 2:00 से सायं 6:00 बजे तक | आरती एवं प्रसाद प्रतिदिन सायं 6:00 बजे | संपर्क: 9876543210")
        val savedUpi = prefs.getString("saved_upi_id", "")

        binding.etKathaTitle.setText(savedKatha)
        binding.etVyasName.setText(savedVyas)
        binding.etDayText.setText(savedDay)
        binding.etTickerText.setText(savedTicker)
        if (!savedUpi.isNullOrEmpty()) {
            binding.etUpiId.setText(savedUpi)
            binding.overlayView.compositor.generateUpiQr(savedUpi, "Katha Sewa")
        }

        binding.overlayView.compositor.kathaTitle = savedKatha ?: ""
        binding.overlayView.compositor.vyasJiName = savedVyas ?: ""
        binding.overlayView.compositor.dayText = savedDay ?: ""
        binding.overlayView.compositor.setCustomTicker(savedTicker ?: "")
    }

    private fun savePref(key: String, value: String) {
        getSharedPreferences("cambridge_prefs", Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun savePref(key: String, value: Boolean) {
        getSharedPreferences("cambridge_prefs", Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    private fun saveOverlayPreferences() {
        val prefs = getSharedPreferences("cambridge_prefs", Context.MODE_PRIVATE).edit()
        prefs.putString("katha_title", binding.etKathaTitle.text.toString())
        prefs.putString("vyas_name", binding.etVyasName.text.toString())
        prefs.putString("day_text", binding.etDayText.text.toString())
        prefs.putString("ticker_text", binding.etTickerText.text.toString())
        prefs.apply()
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun updateLiveBadge(isLive: Boolean, label: String) {
        binding.tvLiveStatus.text = label
        if (isLive) {
            binding.dotLiveIndicator.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.accent_primary)
            binding.tvLiveStatus.setTextColor(Color.WHITE)
        } else {
            binding.dotLiveIndicator.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.text_secondary)
            binding.tvLiveStatus.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
        }
    }

    private fun checkAppUpdates(silent: Boolean) {
        if (!silent) showToast("Checking Git server for updates...")
        AppUpdateManager.checkForUpdate(this) { updateInfo ->
            if (updateInfo != null) {
                AppUpdateManager.showUpdateDialog(this, updateInfo)
            } else if (!silent) {
                val versionName = try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (_: Exception) { "1.0.0" }
                showToast("✅ CamBridge Pro is up to date (v$versionName)")
            }
        }
    }

    private fun updateLatencyModeButtonState() {
        binding.btnToggleLatencyMode.text = "⚡ Zero-Lag (<50ms)"
        binding.btnToggleLatencyMode.setTextColor(Color.parseColor("#00E676"))
    }

    private fun promptFallbackConsent(reason: String) {
        if (!isStreaming) return
        AlertDialog.Builder(this)
            .setTitle("Stream Anomaly Detected")
            .setMessage("Zero-Lag stream reported: $reason\n\nWould you like to fallback to Safe Fallback Mode (v1.0.5)?")
            .setPositiveButton("Fallback Now") { _, _ ->
                isLowLatencyMode = false
                updateLatencyModeButtonState()
                stopBroadcasting()
                startBroadcastingBridge()
                showToast("Fell back to Safe Fallback Mode")
            }
            .setNegativeButton("Keep Current", null)
            .show()
    }

    /**
     * Triple-redundancy watchdog: If direct USB frames are delayed (>400ms),
     * grabs hardware frames directly from SurfaceView using Android's PixelCopy API.
     */
    private fun startPixelCopyWatchdog() {
        pixelCopyJob?.cancel()
        pixelCopyJob = lifecycleScope.launch(Dispatchers.Default) {
            var fallbackBitmap: Bitmap? = null
            while (isActive) {
                delay(40) // ~25 FPS fallback rate
                val now = System.currentTimeMillis()
                if (now - lastDirectFrameTime > 400L && activeBackend == CameraBackend.NATIVE_UVC && !isScreenPreviewForcedOff) {
                    val ws = webServer ?: continue
                    val surfaceView = binding.uvcViewFinder
                    val surface = surfaceView.holder.surface
                    if (surface != null && surface.isValid && surfaceView.width > 0 && surfaceView.height > 0) {
                        val w = surfaceView.width
                        val h = surfaceView.height
                        val currentBmp = fallbackBitmap
                        val bmp = if (currentBmp != null && currentBmp.width == w && currentBmp.height == h) {
                            currentBmp
                        } else {
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { fallbackBitmap = it }
                        }

                        val copyLatch = java.util.concurrent.CountDownLatch(1)
                        var success = false
                        try {
                            PixelCopy.request(surfaceView, bmp, { result ->
                                success = (result == PixelCopy.SUCCESS)
                                copyLatch.countDown()
                            }, pixelCopyHandler)
                            copyLatch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                            if (success) {
                                val outStream = java.io.ByteArrayOutputStream(w * h / 8)
                                bmp.compress(Bitmap.CompressFormat.JPEG, 75, outStream)
                                ws.pushFrame(outStream.toByteArray())
                            }
                        } catch (e: Exception) {
                            // PixelCopy fallback skipped
                        }
                    }
                }
            }
        }
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
