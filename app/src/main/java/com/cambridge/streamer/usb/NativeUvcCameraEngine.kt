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

    /** Optional callback with raw JPEG frame bytes straight from USB capture card for zero-latency direct bridge */
    var onDirectJpegFrame: ((ByteArray) -> Unit)? = null

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
                        // Prioritize MJPEG (type = FRAME_FORMAT_MJPEG = 1) for 1080p 30/60fps without USB 2.0 5fps bottleneck
                        val preferred = sizes?.firstOrNull { it.width == 1920 && it.height == 1080 && it.type == com.serenegiant.usb.UVCCamera.FRAME_FORMAT_MJPEG }
                            ?: sizes?.firstOrNull { it.width == 1920 && it.height == 1080 }
                            ?: sizes?.firstOrNull { it.width == 1280 && it.height == 720 && it.type == com.serenegiant.usb.UVCCamera.FRAME_FORMAT_MJPEG }
                            ?: sizes?.firstOrNull { it.width == 1280 && it.height == 720 }
                            ?: sizes?.firstOrNull()

                        if (preferred != null) {
                            Log.d(TAG, "Opening UVC with resolution: ${preferred.width}x${preferred.height}")
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

                        // Register direct frame callback for zero-latency web bridge
                        try {
                            helper.setFrameCallback({ buffer ->
                                val callback = onDirectJpegFrame ?: return@setFrameCallback
                                if (buffer != null && buffer.remaining() > 4) {
                                    val b0 = buffer.get(0).toInt() and 0xFF
                                    val b1 = buffer.get(1).toInt() and 0xFF
                                    if (b0 == 0xFF && b1 == 0xD8) {
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        callback(bytes)
                                    }
                                }
                            }, com.serenegiant.usb.UVCCamera.PIXEL_FORMAT_RAW)
                        } catch (e: Exception) {
                            Log.w(TAG, "Direct frame callback error", e)
                        }

                        helper.startPreview()

                        val size = helper.previewSize
                        val w = size?.width ?: 1920
                        val h = size?.height ?: 1080

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

    fun addSurface(surface: Surface) {
        synchronized(attachedSurfaces) {
            attachedSurfaces.add(surface)
            if (cameraHelper?.isCameraOpened == true && surface.isValid) {
                try {
                    cameraHelper?.addSurface(surface, false)
                    cameraHelper?.startPreview()
                    Log.d(TAG, "Surface added to running UVC pipeline: hash=${surface.hashCode()}")
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
