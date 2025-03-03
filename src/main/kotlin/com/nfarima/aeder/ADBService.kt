package com.nfarima.aeder

import com.nfarima.aeder.config.Config
import com.nfarima.aeder.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

class ADBService {

    // Executes a generic ADB command and returns the output
    private fun executeAdbCommand(vararg args: String): Pair<Boolean, String> {
        return try {
            val process = ProcessBuilder("adb", *args)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText().trim()
            process.waitFor()

            if (output.contains("error", ignoreCase = true) || output.contains("Failure", ignoreCase = true)) {
                false to output
            } else {
                true to output
            }
        } catch (e: IOException) {
            e.printStackTrace(stream)
            false to "❌ Error: Ensure ADB is installed and accessible."
        }
    }

    // Checks if a device is connected
    fun isDeviceConnected(): Boolean {
        val (success, output) = executeAdbCommand("devices")
        return success && output.lines().any { it.contains("\tdevice") }
    }

    // Installs an APK from the current directory (assumes only one APK is present)
    fun installApk(): Boolean {
        val apkFile = findApkFile() ?: run {
            log("❌ No APK file found in the current directory.", true)
            return false
        }
        log("📲 Installing APK: ${apkFile.name}", true)
        val (success, output) = executeAdbCommand("install", apkFile.absolutePath)
        log(output, false)
        return success
    }

    // Uninstalls an app
    fun uninstallApp(packageName: String): Boolean {
        log("🗑️ Uninstalling: $packageName", true)
        val (success, output) = executeAdbCommand("uninstall", packageName)
        log(output, false)
        return true //because uninstalling if doesn't exist fails
    }

    // Clears app data
    fun clearAppData(packageName: String): Boolean {
        log("🧹 Clearing data for: $packageName", true)
        val (success, output) = executeAdbCommand("shell", "pm clear", packageName)
        log(output, false)
        return success
    }

    // Opens an app using the correct command
    fun openApp(packageName: String): Boolean {
        log("🚀 Opening app: $packageName", true)
        val (success, output) = executeAdbCommand(
            "shell",
            "monkey",
            "-p",
            packageName,
            "-c",
            "android.intent.category.LAUNCHER",
            "1"
        )
        log(output, false)
        return success
    }

    // Simulates a tap event at (x, y) coordinates
    fun tap(x: Int, y: Int): Boolean {
        log("👆 Tapping at: ($x, $y)", false)
        val (success, output) = executeAdbCommand("shell", "input", "tap", x.toString(), y.toString())
        log(output, false)
        return success
    }

    fun back(): Boolean {
        val (success, output) = executeAdbCommand("shell", "input", "keyevent", "4")
        log(output, false)
        return success
    }

    fun home(): Boolean {
        val (success, output) = executeAdbCommand("shell", "input", "keyevent", "3")
        log(output, false)
        return success
    }

    fun recent(): Boolean {
        val (success, output) = executeAdbCommand("shell", "input", "keyevent", "187")
        log(output, false)
        return success
    }

    // Simulates a swipe from (x1, y1) to (x2, y2)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 200): Boolean {
        log("👉 Swiping from ($x1, $y1) to ($x2, $y2) over ${duration}ms", false)
        val (success, output) = executeAdbCommand(
            "shell", "input", "swipe",
            x1.toString(), y1.toString(), x2.toString(), y2.toString(), duration.toString()
        )
        log(output, false)
        return success
    }

    // Inputs text into a text field
    fun inputText(text: String): Boolean {
        val formattedText = text.trim()
        log("⌨️ Inputting text: '$formattedText'", false)
        val (success, output) = executeAdbCommand("shell", "input", "text", formattedText)
        log(output, false)
        return success
    }

    // Inputs text into a text field
    fun inputTextSlowly(text: String): Boolean = runBlocking {
        val formattedText = text.trim()
        log("⌨️ Inputting text slowly: '$formattedText'", false)
        for (char in formattedText) {
            val (success, output) = executeAdbCommand("shell", "input", "text", char.toString())
            if (!success) {
                log(output, false)
                return@runBlocking false
            }
            delay(100)
        }
        return@runBlocking true
    }

    fun screenshot(step: Step): String? {
        val fileName = step.name.replace(" ", "-").lowercase()
        val localFilePath = "$workingDir/images/$fileName.png"
        val remoteFilePath = "/sdcard/screenshot.png"

        log("📸 Taking screenshot...", false)
        val (captureSuccess, captureOutput) = executeAdbCommand("shell", "screencap", "-p", remoteFilePath)
        if (!captureSuccess) {
            log("❌ Failed to take screenshot: $captureOutput", false)
            return null
        }

        log("📥 Pulling screenshot to $localFilePath...", false)
        val (pullSuccess, pullOutput) = executeAdbCommand("pull", remoteFilePath, localFilePath)
        if (!pullSuccess) {
            log("❌ Failed to pull screenshot to local directory: $pullOutput", false)
            return null
        }

        log("🔄 Resizing screenshot...", false)
        if (!resizeImage(localFilePath, localFilePath, 768)) {
            log("❌ Failed to resize screenshot", false)
            return null
        }

        if (Config.current.coverMobileStatusBar) {
            log("🖌️ Applying black bar on the top 4% of the image...", false)
            if (!applyBlackBar(localFilePath)) {
                log("❌ Failed to apply black bar", false)
                return null
            }
        }

        log("✅ Screenshot saved as '$localFilePath'.", false)
        return localFilePath
    }

    fun getCurrentActivity(): String {
        val (success, output) = executeAdbCommand(
            "shell",
            "dumpsys",
            "window",
            "windows",
            "|",
            "grep",
            "-E",
            "'mCurrentFocus'"
        )
        return if (success) output.trim() else "❌ Unable to detect current activity."
    }

    // Finds an APK file in the current directory
    private fun findApkFile(): File? {
        return File(workingDir).listFiles()?.find { it.extension == "apk" }
    }
}
