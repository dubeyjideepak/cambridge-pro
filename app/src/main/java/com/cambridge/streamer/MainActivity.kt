package com.cambridge.streamer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.cambridge.streamer.stream.RtmpStreamManager
import com.cambridge.streamer.stream.RtspServerManager
import com.cambridge.streamer.stream.WebMonitorServer
import com.cambridge.streamer.update.AppUpdateManager
import com.cambridge.streamer.usb.NativeUvcCameraEngine
import com.cambridge.streamer.usb.UsbDeviceManager
import com.cambridge.streamer.utils.NetworkUtils
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
            setupListeners()
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
        stopBroadcasting()
        nativeUvcEngine?.stop()
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
                    binding.tvUsbStatus.text = "✅ HDMI: ${device.productName ?: "4K Card"} (${width}x${height})"
                    binding.tvUsbStatus.setTextColor(Color.parseColor("#00E676"))
                    binding.layoutHdmiStandby.visibility = View.GONE
                    binding.viewFinder.visibility = View.GONE
                    binding.uvcViewFinder.visibility = View.VISIBLE
                    updateUvcSurfacesForCurrentMode()
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
                    }
                }
            }

            override fun onUvcError(device: UsbDevice?, error: Exception) {
                Log.e(TAG, "Native UVC engine error: ${error.message}", error)
            }
        })
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
        binding.tvObsUrl.text = "rtsp://$localIp:8554/live"
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

        // Copy OBS URL
        binding.btnCopyUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OBS URL", binding.tvObsUrl.text))
            showToast("Copied OBS URL!")
        }

        // In-App Git Auto-Updater
        binding.btnCheckUpdate.setOnClickListener {
            checkAppUpdates(silent = false)
        }

        // Bridge mode CTA
        binding.btnToggleBridgeStream.setOnClickListener {
            if (isStreaming) stopBroadcasting() else startBroadcastingBridge()
        }

        // Studio: Apply overlays
        binding.btnUpdateOverlays.setOnClickListener {
            binding.etVyasName.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
                binding.overlayView.compositor.vyasJiName = it
            }
            binding.etTickerText.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
                binding.overlayView.compositor.tickerText = it
            }
            binding.overlayView.invalidate()
            showToast("Overlays & Ticker Updated!")
        }

        // Jhanki Presets & Custom Gallery Photo Picker
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

        // Studio: Go Live
        binding.btnToggleStudioLive.setOnClickListener {
            if (isStreaming) {
                stopBroadcasting()
            } else {
                val key = binding.etStreamKey.text.toString().trim()
                if (key.isEmpty()) {
                    showToast("Please paste YouTube / Facebook Stream Key")
                    return@setOnClickListener
                }
                startBroadcastingStudio(key)
            }
        }
    }

    // ─── Camera Binding (Mode-Aware) ───────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            initCameraProvider()
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

            val screenPreview = Preview.Builder()
                .setTargetResolution(android.util.Size(1920, 1080))
                .build()
                .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

            binding.viewFinder.visibility = View.VISIBLE
            binding.uvcViewFinder.visibility = View.GONE
            binding.layoutHdmiStandby.visibility = View.GONE
            useCases.add(screenPreview)

            val encoderSurface = videoEncoder?.inputSurface
            if (isStreaming && encoderSurface != null && encoderSurface.isValid) {
                val encoderPreview = Preview.Builder()
                    .setTargetResolution(android.util.Size(1920, 1080))
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
        engine.clearSurfaces()

        binding.viewFinder.visibility = View.GONE
        binding.uvcViewFinder.visibility = View.VISIBLE
        binding.layoutHdmiStandby.visibility = View.GONE

        // 1. Always attach phone screen preview so operator sees the live 4K feed
        val holderSurface = binding.uvcViewFinder.holder.surface
        if (holderSurface.isValid) {
            engine.addSurface(holderSurface)
            Log.d(TAG, "Attached screen viewfinder surface to UVC engine")
        }

        // 2. Attach video encoder input surface when broadcasting (OBS Bridge or Studio Live)
        val encoderSurface = videoEncoder?.inputSurface
        if (isStreaming && encoderSurface != null && encoderSurface.isValid) {
            engine.addSurface(encoderSurface)
            Log.d(TAG, "Attached encoder input surface to UVC engine for streaming")
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

        val encoderPreview = Preview.Builder()
            .setTargetResolution(android.util.Size(1920, 1080))
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
            .setTargetResolution(android.util.Size(1920, 1080))
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
     * BRIDGE TO OBS:
     * 1. Start HardwareVideoEncoder → inputSurface created
     * 2. Re-bind camera to wire frames INTO the encoder surface
     * 3. Start RTSP server AFTER encoder has SPS/PPS (waits up to 2s)
     * 4. Start lightweight WebMonitorServer for browser fallback
     */
    private fun startBroadcastingBridge() {
        isStreaming = true

        // Step 1: Start encoder first — creates the inputSurface
        videoEncoder = HardwareVideoEncoder(
            width = 1920, height = 1080, bitrate = 4_500_000, frameRate = 30
        ) { frameBytes, isKeyFrame, ptsMs ->
            bondingEngine.dispatchPacket(frameBytes)
            rtspServer?.broadcastH264Packet(frameBytes, isKeyFrame, ptsMs)
        }
        videoEncoder?.start()

        // Step 2: Start HDMI Audio Capture Pipeline (UAC capture, 48kHz broadcast standard)
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
                rtspServer?.broadcastAudioPacket(rawAac, dtsMs)
            }
        )
        bridgeAudioPipeline?.start()

        // Steps 3 & 4 on a background thread:
        // - Wait for encoder inputSurface to be valid (MediaCodec creates it synchronously in start(),
        //   but the drain thread must begin draining before SPS/PPS is emitted)
        // - THEN wire camera → encoder surface
        // - THEN wait for SPS/PPS and start RTSP
        Thread {
            // Brief stabilization: let encoder drain thread start running
            Thread.sleep(100)

            // Step 3: Wire camera → encoder surface (Bridge = no preview on phone)
            runOnUiThread {
                if (activeBackend == CameraBackend.NATIVE_UVC) {
                    updateUvcSurfacesForCurrentMode()
                } else {
                    bindCamera(ProductionMode.BRIDGE_TO_OBS)
                }
            }

            // Step 4: Wait for encoder to emit SPS/PPS before starting RTSP
            // (OBS DESCRIBE response needs valid sprop-parameter-sets)
            var waited = 0
            while (videoEncoder?.sps == null && waited < 3000) {
                Thread.sleep(50)
                waited += 50
            }
            Log.d(TAG, "SPS ready after ${waited}ms — starting RTSP server")

            rtspServer = RtspServerManager(
                port = 8554,
                getSpsPps = { Pair(videoEncoder?.sps, videoEncoder?.pps) },
                getAudioConfig = { rtspAudioConfig },
                onRequestKeyFrame = { videoEncoder?.requestSyncFrame() }
            )
            rtspServer?.start()

            // Step 5: Web browser monitor
            webServer = WebMonitorServer(port = 8080) {
                null
            }
            webServer?.isStreaming = true
            try { webServer?.start() } catch (_: Exception) {}
        }.start()

        // Step 6: Start Foreground Service to acquire partial wake-lock and prevent Android from killing RTSP on screen lock
        StreamingForegroundService.start(this, "🔴 LIVE → OBS Bridge (RTSP)")

        // UI update
        binding.btnToggleBridgeStream.text = "STOP OBS STREAM"
        binding.btnToggleBridgeStream.setBackgroundResource(R.drawable.bg_cta_red)
        binding.btnToggleBridgeStream.setTextColor(Color.WHITE)
        updateLiveBadge(true, "🔴 LIVE → OBS")
        showToast("Broadcasting to OBS Studio (Live Preview Active)")
    }

    /**
     * IN-APP STUDIO:
     * 1. Start HardwareVideoEncoder → inputSurface created
     * 2. Re-bind camera to wire BOTH screen preview + encoder surface
     * 3. Wait for SPS/PPS from encoder (up to 2s)
     * 4. Start RTMP stream to YouTube/Facebook using bonded network
     * 5. Start foreground service so broadcast survives screen lock
     */
    private fun startBroadcastingStudio(streamKey: String) {
        isStreaming = true

        // Determine full RTMP URL from platform selector in UI
        val rtmpUrl = buildRtmpUrl(streamKey)
        Log.d(TAG, "Studio RTMP target: ${rtmpUrl.take(45)}…")

        // Step 1: Start encoder — creates MediaCodec input surface
        videoEncoder = HardwareVideoEncoder(
            width = 1920, height = 1080, bitrate = 4_500_000, frameRate = 30
        ) { frameBytes, isKeyFrame, ptsMs ->
            bondingEngine.dispatchPacket(frameBytes)
            rtmpStreamManager?.pushVideoFrame(frameBytes, isKeyFrame, ptsMs)
        }
        videoEncoder?.start()

        // Step 2: Wire screen preview + encoder surface
        rebindEncoderPreviewForStudio()

        // Step 3 & 4: Wait for SPS/PPS then open RTMP — on background thread
        Thread {
            // Wait up to 2s for encoder to emit SPS/PPS
            var waited = 0
            while ((videoEncoder?.sps == null || videoEncoder?.pps == null) && waited < 2000) {
                Thread.sleep(50)
                waited += 50
            }
            val sps = videoEncoder?.sps
            val pps = videoEncoder?.pps
            Log.d(TAG, "Studio SPS ready after ${waited}ms (sps=${sps?.size}B, pps=${pps?.size}B)")

            // Step 4: Connect RTMP with AVC header ready (passing context for USB audio routing)
            rtmpStreamManager = RtmpStreamManager(this)
            rtmpStreamManager?.startLiveStream(
                rtmpUrl = rtmpUrl,
                sps = sps,
                pps = pps,
                onConnected = {
                    runOnUiThread {
                        binding.btnToggleStudioLive.text = "STOP LIVE"
                        binding.btnToggleStudioLive.setBackgroundResource(R.drawable.bg_cta_rounded)
                        binding.btnToggleStudioLive.setTextColor(Color.BLACK)
                        updateLiveBadge(true, "🔴 LIVE (BONDED)")
                        showToast("📡 Live! Overlays & signal bonding active")
                    }
                    // Step 5: Foreground service keeps us alive on screen lock
                    StreamingForegroundService.start(this@MainActivity, "🔴 LIVE — Studio Broadcast")
                },
                onError = { errMsg ->
                    runOnUiThread {
                        stopBroadcasting()
                        showToast("⚠️ Stream Error: $errMsg")
                    }
                }
            )
        }.start()
    }

    /**
     * Resolve the RTMP endpoint from UI platform chips + stream key.
     * Uses secure RTMPS (port 443 with TLS) for Facebook and Instagram Live.
     * If the key already starts with "rtmp://" or "rtmps://" it is used verbatim (custom RTMP).
     */
    private fun buildRtmpUrl(streamKey: String): String {
        if (streamKey.startsWith("rtmp://") || streamKey.startsWith("rtmps://")) {
            return streamKey
        }
        return when {
            binding.chipYoutube.isChecked   -> "rtmp://a.rtmp.youtube.com/live2/$streamKey"
            binding.chipFacebook.isChecked  -> "rtmps://live-api-s.facebook.com:443/rtmp/$streamKey"
            binding.chipInstagram.isChecked -> "rtmps://live-upload.instagram.com:443/rtmp/$streamKey"
            else                            -> "rtmp://a.rtmp.youtube.com/live2/$streamKey"
        }
    }

    private fun stopBroadcasting() {
        isStreaming = false

        videoEncoder?.inputSurface?.let { nativeUvcEngine?.removeSurface(it) }
        videoEncoder?.stop()
        videoEncoder = null

        bridgeAudioPipeline?.stop()
        bridgeAudioPipeline = null
        rtspAudioConfig = null

        rtspServer?.stop()
        rtspServer = null

        webServer?.isStreaming = false
        webServer?.stop()
        webServer = null

        rtmpStreamManager?.stopLiveStream()
        rtmpStreamManager = null

        // Stop foreground service (wake lock released)
        StreamingForegroundService.stop(this)

        // Restore camera to current mode (preview-only, no encoder)
        if (activeBackend == CameraBackend.NATIVE_UVC) {
            updateUvcSurfacesForCurrentMode()
        } else {
            bindCamera(currentMode)
        }

        binding.btnToggleBridgeStream.text = "START OBS STREAM"
        binding.btnToggleBridgeStream.setBackgroundResource(R.drawable.bg_cta_rounded)
        binding.btnToggleBridgeStream.setTextColor(Color.BLACK)
        binding.btnToggleStudioLive.text = "GO LIVE (BONDED)"
        binding.btnToggleStudioLive.setBackgroundResource(R.drawable.bg_cta_red)
        binding.btnToggleStudioLive.setTextColor(Color.WHITE)
        updateLiveBadge(false, "STANDBY")
        showToast("Broadcast stopped")
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

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
