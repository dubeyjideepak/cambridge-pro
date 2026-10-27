package com.cambridge.streamer.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.utils.UVCUtils

/**
 * Universal Native UVC Camera Engine
 *
 * Provides direct USB Host access to HDMI capture cards (MS2109, MS2130, Elgato Cam Link, etc.)
 * for Android devices where vendor ROMs (OnePlus, Xiaomi/MIUI, Vivo, Oppo, Realme) disable or
 * omit Camera2's LENS_FACING_EXTERNAL in external_camera_config.xml.
 *
 * Bridges the UVC video stream directly to:
 * - Bridge Mode: HardwareVideoEncoder input surface (for zero-latency OBS RTSP streaming)
 * - Studio Mode: SurfaceView screen preview + HardwareVideoEncoder input surface (for RTMP live)
 */
class NativeUvcCameraEngine(
    private val context: Context,
    private val listener: UvcCameraListener
) {
    interface UvcCameraListener {
        fun onUvcAttached(device: UsbDevice)
        fun onUvcOpened(device: UsbDevice, width: Int, height: Int)
        fun onUvcClosed(device: UsbDevice)
        fun onUvcError(device: UsbDevice?, error: Exception)
        fun onVideoFrameStats(fps: Float, totalFrames: Long, bytesPerSec: Long) {}
    }

    private val TAG = "NativeUvcCameraEngine"
    private var cameraHelper: ICameraHelper? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeDevice: UsbDevice? = null
    private val attachedSurfaces = mutableSetOf<Surface>()

    var currentWidth: Int = 1920
    var currentHeight: Int = 1080

    private var reusableNv21Buffer: ByteArray? = null
    private val reusableJpegStream = java.io.ByteArrayOutputStream(1024 * 512)
    private val isCompressingFrame = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Optional callback with raw JPEG frame bytes straight from USB capture card for legacy MJPEG stream */
    var onDirectJpegFrame: ((ByteArray) -> Unit)? = null
        set(value) {
            field = value
            updateFrameCallbackState()
        }

    fun updateFrameCallbackState() {
        val helper = cameraHelper ?: return
        val callback = onDirectJpegFrame
        if (callback != null) {
            try {
                helper.setFrameCallback({ buffer ->
                    val cb = onDirectJpegFrame ?: return@setFrameCallback
                    if (buffer == null || buffer.remaining() <= 4) return@setFrameCallback

                    if (!isCompressingFrame.compareAndSet(false, true)) {
                        return@setFrameCallback
                    }

                    try {
                        val b0 = buffer.get(0).toInt() and 0xFF
                        val b1 = buffer.get(1).toInt() and 0xFF
                        if (b0 == 0xFF && b1 == 0xD8) {
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            buffer.rewind()
                            cb(bytes)
                        } else {
                            val curW = if (currentWidth > 0) currentWidth else 1920
                            val curH = if (currentHeight > 0) currentHeight else 1080
                            val remaining = buffer.remaining()
                            if (reusableNv21Buffer == null || reusableNv21Buffer!!.size != remaining) {
                                reusableNv21Buffer = ByteArray(remaining)
                            }
                            val bytes = reusableNv21Buffer!!
                            buffer.get(bytes)
                            buffer.rewind()
                            try {
                                val expectedNv21 = (curW * curH * 3) / 2
                                val fmt = if (remaining == expectedNv21) android.graphics.ImageFormat.NV21 else android.graphics.ImageFormat.YUY2
                                val yuvImage = android.graphics.YuvImage(bytes, fmt, curW, curH, null)
                                synchronized(reusableJpegStream) {
                                    reusableJpegStream.reset()
                                    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, curW, curH), 60, reusableJpegStream)
                                    cb(reusableJpegStream.toByteArray())
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "YUV frame conversion to JPEG error", e)
                            }
                        }
                    } finally {
                        isCompressingFrame.set(false)
                    }
                }, com.serenegiant.usb.UVCCamera.PIXEL_FORMAT_NV21)
                Log.d(TAG, "UVC frame callback ENABLED (legacy MJPEG client active)")
            } catch (e: Exception) {
                Log.w(TAG, "Error enabling frame callback", e)
            }
        } else {
            try {
                helper.setFrameCallback(null, 0)
                Log.d(TAG, "UVC frame callback DISABLED (Zero CPU, pure GPU ANativeWindow mode active)")
            } catch (e: Exception) {
                Log.w(TAG, "Error disabling frame callback", e)
            }
        }
    }

    init {
        try {
            UVCUtils.init(context)
        } catch (e: Exception) {
            Log.e(TAG, "UVCUtils init error", e)
        }
    }

    fun start() {
        if (cameraHelper != null) return

        try {
            val helper = CameraHelper()
            helper.setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice) {
                    Log.d(TAG, "UVC device attached: ${device.productName ?: device.deviceName}")
                    mainHandler.post {
                        listener.onUvcAttached(device)
                        try {
                            helper.selectDevice(device)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to select UVC device", e)
                        }
                    }
                }

                override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                    Log.d(TAG, "UVC onDeviceOpen: ${device.productName}, first: $isFirstOpen")
                    try {
                        val sizes = helper.supportedSizeList
                        Log.d(TAG, "Supported UVC sizes count: ${sizes?.size ?: 0}")
                        sizes?.forEach { s ->
                            Log.d(TAG, "Available UVC mode: ${s.width}x${s.height} @ ${s.fps} FPS, type=${s.type}, fpsList=${s.fpsList}")
                        }

                        // Prioritize MJPEG (type = FRAME_FORMAT_MJPEG = 1) for 1080p 60fps without USB 2.0 5fps bottleneck
                        val mjpegSizes = sizes?.filter { it.type == com.serenegiant.usb.UVCCamera.FRAME_FORMAT_MJPEG } ?: emptyList()
                        val pool = if (mjpegSizes.isNotEmpty()) mjpegSizes else (sizes ?: emptyList())

                        val preferred = pool.firstOrNull { it.width == 1920 && it.height == 1080 }
                            ?: pool.firstOrNull { it.width == 1280 && it.height == 720 }
                            ?: pool.firstOrNull()

                        if (preferred != null) {
                            // Force highest supported hardware framerate (60 FPS or 50/30 FPS)
                            val maxFps = preferred.fpsList?.maxOrNull() ?: 60
                            val targetFps = if (maxFps > 0) maxFps else 60
                            preferred.fps = targetFps

                            currentWidth = preferred.width
                            currentHeight = preferred.height
                            Log.d(TAG, "⚡ Opening UVC hardware: ${preferred.width}x${preferred.height} @ ${preferred.fps} FPS (type=${preferred.type})")
                            helper.openCamera(preferred)
                        } else {
                            helper.openCamera()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening UVC camera", e)
                        mainHandler.post { listener.onUvcError(device, e) }
                    }
                }

                override fun onCameraOpen(device: UsbDevice) {
                    Log.d(TAG, "UVC Camera opened successfully: ${device.productName}")
                    activeDevice = device

                    val size = helper.previewSize
                    if (size != null && size.width > 0) {
                        currentWidth = size.width
                        currentHeight = size.height
                    }
                    val w = currentWidth
                    val h = currentHeight

                    try {
                        // Attach all valid surfaces BEFORE starting preview to guarantee frame routing
                        synchronized(attachedSurfaces) {
                            for (surface in attachedSurfaces) {
                                if (surface.isValid) {
                                    helper.addSurface(surface, false)
                                    Log.d(TAG, "Attached surface to UVC: hash=${surface.hashCode()}")
                                }
                            }
                        }

                        // Only register CPU frame callback if an MJPEG client is actively requesting it.
                        // In normal GPU streaming (HTTP-FLV / OBS), frame callback is DISABLED to ensure 0.0% CPU usage
                        // and let USB packets flow straight into MediaCodec's ANativeWindow surface!
                        updateFrameCallbackState()

                        helper.startPreview()

                        mainHandler.post {
                            listener.onUvcOpened(device, w, h)
                        }

                        // Verify camera is active
                        mainHandler.postDelayed({
                            if (!helper.isCameraOpened) {
                                listener.onVideoFrameStats(0f, 0L, 0L)
                            }
                        }, 2500)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting UVC preview", e)
                        mainHandler.post { listener.onUvcError(device, e) }
                    }
                }

                override fun onCameraClose(device: UsbDevice) {
                    Log.d(TAG, "UVC Camera closed: ${device.productName}")
                    mainHandler.post {
                        listener.onUvcClosed(device)
                    }
                }

                override fun onDeviceClose(device: UsbDevice) {
                    Log.d(TAG, "UVC Device closed: ${device.productName}")
                }

                override fun onDetach(device: UsbDevice) {
                    Log.d(TAG, "UVC Detached: ${device.productName}")
                    if (activeDevice == device) {
                        activeDevice = null
                    }
                    mainHandler.post {
                        listener.onUvcClosed(device)
                    }
                }

                override fun onCancel(device: UsbDevice) {
                    Log.d(TAG, "UVC Permission cancelled by user")
                }
            })

            cameraHelper = helper
            try {
                helper.registerCallback()
            } catch (e: Exception) {
                Log.w(TAG, "registerCallback error", e)
            }

            // Check if device is already connected at launch
            val devices = helper.deviceList
            if (!devices.isNullOrEmpty()) {
                val uvcDevice = devices[0]
                Log.d(TAG, "Existing UVC device found at launch: ${uvcDevice.productName}")
                helper.selectDevice(uvcDevice)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NativeUvcCameraEngine", e)
            listener.onUvcError(null, e)
        }
    }

    fun selectDevice(device: UsbDevice) {
        start()
        val helper = cameraHelper ?: return
        try {
            Log.d(TAG, "⚡ Explicit selectDevice called for: ${device.productName ?: device.deviceName}")
            helper.selectDevice(device)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select device: ${device.productName}", e)
        }
    }

    fun checkConnectedDevices() {
        start()
        val helper = cameraHelper ?: return
        try {
            val devices = helper.deviceList
            if (!devices.isNullOrEmpty()) {
                val uvcDevice = devices[0]
                Log.d(TAG, "⚡ Found attached UVC device: ${uvcDevice.productName ?: uvcDevice.deviceName}")
                helper.selectDevice(uvcDevice)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking connected devices", e)
        }
    }

    fun addSurface(surface: Surface) {
        synchronized(attachedSurfaces) {
            val isNew = attachedSurfaces.add(surface)
            if (cameraHelper?.isCameraOpened == true && surface.isValid) {
                try {
                    cameraHelper?.addSurface(surface, false)
                    Log.d(TAG, "Surface added to running UVC pipeline (isNew=$isNew): hash=${surface.hashCode()}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add surface to UVC pipeline", e)
                }
            }
        }
    }

    fun removeSurface(surface: Surface) {
        synchronized(attachedSurfaces) {
            attachedSurfaces.remove(surface)
            if (cameraHelper?.isCameraOpened == true) {
                try {
                    cameraHelper?.removeSurface(surface)
                    Log.d(TAG, "Surface removed from UVC pipeline: hash=${surface.hashCode()}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove surface from UVC pipeline", e)
                }
            }
        }
    }

    fun clearSurfaces() {
        synchronized(attachedSurfaces) {
            if (cameraHelper?.isCameraOpened == true) {
                for (surface in attachedSurfaces) {
                    try {
                        cameraHelper?.removeSurface(surface)
                    } catch (_: Exception) {}
                }
            }
            attachedSurfaces.clear()
        }
    }

    fun isCameraOpened(): Boolean = cameraHelper?.isCameraOpened == true

    fun stop() {
        clearSurfaces()
        try {
            cameraHelper?.stopPreview()
            cameraHelper?.closeCamera()
            cameraHelper?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error during stop()", e)
        } finally {
            cameraHelper = null
            activeDevice = null
        }
    }
}
