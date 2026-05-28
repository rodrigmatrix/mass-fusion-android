package com.limelight.utils

import android.Manifest.permission.ACCESS_WIFI_STATE
import android.Manifest.permission.CHANGE_WIFI_STATE
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.WIFI_SERVICE
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.TextUtils
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

object DeviceUtils {

    @JvmStatic
    fun isDeviceRooted(): Boolean {
        val su = "su"
        val locations = arrayOf(
            "/system/bin/", "/system/xbin/", "/sbin/", "/system/sd/xbin/",
            "/system/bin/failsafe/", "/data/local/xbin/", "/data/local/bin/", "/data/local/",
            "/system/sbin/", "/usr/bin/", "/vendor/bin/"
        )
        for (location in locations) {
            if (File(location + su).exists()) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    fun isAdbEnabled(context: Context): Boolean {
        return Settings.Secure.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED, 0
        ) > 0
    }

    @JvmStatic
    fun getSDKVersionName(): String {
        return Build.VERSION.RELEASE
    }

    @JvmStatic
    fun getSDKVersionCode(): Int {
        return Build.VERSION.SDK_INT
    }

    @JvmStatic
    @SuppressLint("HardwareIds")
    fun getAndroidID(context: Context): String {
        val id = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        if ("9774d56d682e549c" == id) return ""
        return id ?: ""
    }

    @JvmStatic
    @RequiresPermission(allOf = [ACCESS_WIFI_STATE, CHANGE_WIFI_STATE])
    fun getMacAddress(context: Context): String {
        val macAddress = getMacAddress(context, *emptyArray<String>())
        if (!TextUtils.isEmpty(macAddress) || getWifiEnabled(context)) return macAddress
        setWifiEnabled(context, true)
        setWifiEnabled(context, false)
        return getMacAddress(context, *emptyArray<String>())
    }

    @JvmStatic
    private fun getWifiEnabled(context: Context): Boolean {
        @SuppressLint("WifiManagerLeak")
        val manager = context.getSystemService(WIFI_SERVICE) as WifiManager? ?: return false
        return manager.isWifiEnabled
    }

    @JvmStatic
    @RequiresPermission(CHANGE_WIFI_STATE)
    private fun setWifiEnabled(context: Context, enabled: Boolean) {
        @SuppressLint("WifiManagerLeak")
        val manager = context.getSystemService(WIFI_SERVICE) as WifiManager? ?: return
        if (enabled == manager.isWifiEnabled) return
        manager.isWifiEnabled = enabled
    }

    @JvmStatic
    @RequiresPermission(allOf = [ACCESS_WIFI_STATE])
    fun getMacAddress(context: Context, vararg excepts: String): String {
        var macAddress = getMacAddressByNetworkInterface()
        if (isAddressNotInExcepts(macAddress, *excepts)) {
            return macAddress
        }
        macAddress = getMacAddressByInetAddress()
        if (isAddressNotInExcepts(macAddress, *excepts)) {
            return macAddress
        }
        macAddress = getMacAddressByWifiInfo(context)
        if (isAddressNotInExcepts(macAddress, *excepts)) {
            return macAddress
        }
        return ""
    }

    @JvmStatic
    private fun isAddressNotInExcepts(address: String?, vararg excepts: String): Boolean {
        if (TextUtils.isEmpty(address)) {
            return false
        }
        if ("02:00:00:00:00:00" == address) {
            return false
        }
        if (excepts.isEmpty()) {
            return true
        }
        for (filter in excepts) {
            if (filter == address) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    @RequiresPermission(ACCESS_WIFI_STATE)
    private fun getMacAddressByWifiInfo(context: Context): String {
        try {
            val wifi = context.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager?
            if (wifi != null) {
                val info = wifi.connectionInfo
                if (info != null) {
                    @SuppressLint("HardwareIds")
                    val macAddress = info.macAddress
                    if (!TextUtils.isEmpty(macAddress)) {
                        return macAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "02:00:00:00:00:00"
    }

    @JvmStatic
    private fun getMacAddressByNetworkInterface(): String {
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (ni == null || !ni.name.equals("wlan0", ignoreCase = true)) continue
                val macBytes = ni.hardwareAddress
                if (macBytes != null && macBytes.isNotEmpty()) {
                    val sb = StringBuilder()
                    for (b in macBytes) {
                        sb.append(String.format("%02x:", b))
                    }
                    return sb.substring(0, sb.length - 1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "02:00:00:00:00:00"
    }

    @JvmStatic
    private fun getMacAddressByInetAddress(): String {
        try {
            val inetAddress = getInetAddress()
            if (inetAddress != null) {
                val ni = NetworkInterface.getByInetAddress(inetAddress)
                if (ni != null) {
                    val macBytes = ni.hardwareAddress
                    if (macBytes != null && macBytes.isNotEmpty()) {
                        val sb = StringBuilder()
                        for (b in macBytes) {
                            sb.append(String.format("%02x:", b))
                        }
                        return sb.substring(0, sb.length - 1)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "02:00:00:00:00:00"
    }

    @JvmStatic
    private fun getInetAddress(): InetAddress? {
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                // To prevent phone of xiaomi return "10.0.2.15"
                if (!ni.isUp) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val inetAddress = addresses.nextElement()
                    if (!inetAddress.isLoopbackAddress) {
                        val hostAddress = inetAddress.hostAddress
                        if (hostAddress?.indexOf(':') ?: -1 < 0) return inetAddress
                    }
                }
            }
        } catch (e: SocketException) {
            e.printStackTrace()
        }
        return null
    }

    @JvmStatic
    fun getManufacturer(): String {
        return Build.MANUFACTURER
    }

    @JvmStatic
    fun getModel(): String {
        var model = Build.MODEL
        model = model?.trim()?.replace("\\s*".toRegex(), "") ?: ""
        return model
    }

    @JvmStatic
    fun getABIs(): Array<String> {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return Build.SUPPORTED_ABIS
        } else {
            @Suppress("DEPRECATION")
            if (!TextUtils.isEmpty(Build.CPU_ABI2)) {
                return arrayOf(Build.CPU_ABI, Build.CPU_ABI2)
            }
            return arrayOf(Build.CPU_ABI)
        }
    }

    @JvmStatic
    fun isTablet(): Boolean {
        return (Resources.getSystem().configuration.screenLayout
                and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
    }

    @JvmStatic
    fun isEmulator(context: Context): Boolean {
        val checkProperty = Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.lowercase().contains("vbox")
                || Build.FINGERPRINT.lowercase().contains("test-keys")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT
        if (checkProperty) return true

        var operatorName = ""
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
        if (tm != null) {
            val name = tm.networkOperatorName
            if (name != null) {
                operatorName = name
            }
        }
        val checkOperatorName = operatorName.lowercase() == "android"
        if (checkOperatorName) return true

        val url = "tel:123456"
        val intent = Intent()
        intent.data = Uri.parse(url)
        intent.action = Intent.ACTION_DIAL
        val checkDial = intent.resolveActivity(context.packageManager) == null
        if (checkDial) return true
        if (isEmulatorByCpu()) return true

        return false
    }

    @JvmStatic
    private fun isEmulatorByCpu(): Boolean {
        val cpuInfo = readCpuInfo()
        return cpuInfo.contains("intel") || cpuInfo.contains("amd")
    }

    @JvmStatic
    fun readCpuInfo(): String {
        var result = ""
        try {
            val args = arrayOf("/system/bin/cat", "/proc/cpuinfo")
            val cmd = ProcessBuilder(*args)
            val process = cmd.start()
            val sb = StringBuilder()
            var readLine: String?
            val responseReader = BufferedReader(InputStreamReader(process.inputStream, "utf-8"))
            while (responseReader.readLine().also { readLine = it } != null) {
                sb.append(readLine)
            }
            responseReader.close()
            result = sb.toString().lowercase()
        } catch (ignored: IOException) {
        }
        return result
    }

    @JvmStatic
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    fun isDevelopmentSettingsEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) > 0
    }
}
