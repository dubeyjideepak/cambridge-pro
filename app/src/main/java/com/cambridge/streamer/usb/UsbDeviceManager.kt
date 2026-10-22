package com.cambridge.streamer.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbDeviceManager(
    private val context: Context,
    private val onDeviceStateChanged: (Boolean, String) -> Unit
) {
    private val TAG = "UsbDeviceManager"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    companion object {
        const val USB_PERMISSION_ACTION = "com.cambridge.streamer.USB_PERMISSION"
    }

    fun checkForConnectedCaptureCard(): Boolean {
        try {
            val deviceList: HashMap<String, UsbDevice> = usbManager.deviceList
            Log.d(TAG, "Scanning USB Devices. Count: ${deviceList.size}")

            for ((_, device) in deviceList) {
                Log.d(TAG, "USB Device: ${device.deviceName}, Class: ${device.deviceClass}")

                var isVideoDevice = (device.deviceClass == UsbConstants.USB_CLASS_VIDEO)
                if (!isVideoDevice) {
                    for (i in 0 until device.interfaceCount) {
                        if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                            isVideoDevice = true
                            break
                        }
                    }
                }

                if (isVideoDevice) {
                    val name = device.productName ?: "USB HDMI Capture Card"
                    Log.d(TAG, "UVC Device Found: $name")

                    // Notify device found. NativeUvcCameraEngine (CameraHelper) owns the USB permission lifecycle.
                    onDeviceStateChanged(true, name)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking connected capture card", e)
        }

        onDeviceStateChanged(false, "No HDMI Capture Card detected")
        return false
    }

    /**
     * Called by MainActivity's BroadcastReceiver once the user grants USB permission.
     *
     * NOTE: We do NOT claim the interface here. NativeUvcCameraEngine uses libusb (inside
     * UVCAndroid) which will claim and own the UVC interface exclusively via USB Host bulk
     * transfer. A parallel claimInterface() from Java would conflict with libusb and break
     * the UVC stream on OnePlus/Xiaomi/Vivo/Oppo/Realme devices.
     *
     * All we need to do here is update the UI status — libusb handles the rest.
     */
    fun onPermissionGranted(device: UsbDevice) {
        val name = device.productName ?: "USB HDMI Capture Card"
        Log.d(TAG, "USB permission granted for $name — libusb will claim the UVC interface")
        onDeviceStateChanged(true, name)
    }
}
