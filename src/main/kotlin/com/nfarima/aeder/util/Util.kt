package com.nfarima.aeder.util

import com.nfarima.aeder.config.AederCredentials
import com.nfarima.aeder.config.Config
import com.nfarima.aeder.workingDir
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.*
import java.util.*
import javax.imageio.ImageIO
import kotlin.random.Random

lateinit var stream: PrintStream

var scaleFactor = 1.0

fun initializeLogging() {
    val currentSessionLogFile = "$workingDir/logs/session-${System.currentTimeMillis()}.log"
    stream = PrintStream(FileOutputStream(currentSessionLogFile, true), true)
    log("Detailed logs available in $currentSessionLogFile", true)
}

fun initializeFiles() {
    File(workingDir, "images").mkdir()
    File(workingDir, "logs").mkdir()
    File(workingDir, "config").mkdir()

    requireFile("$workingDir/config/credentials.json") { AederCredentials.saved = AederCredentials() }
    requireFile("$workingDir/config/intervals.json") { Config.current = Config() }

    requireFile("$workingDir/script.txt")
}

fun requireFile(fileName: String, create: (() -> Unit)? = { File(fileName).createNewFile() }) {
    if (!File(fileName).exists()) {
        create?.invoke()
    }

    if (!File(fileName).exists()) {
        throw IllegalStateException("File $fileName does not exist.")
    }
}

fun encodeImageToBase64(imagePath: String): String? {
    return try {
        val file = File(imagePath)
        if (!file.exists()) {
            log("❌ Error: Image file '$imagePath' not found.", false)
            return null
        }
        val bytes = file.readBytes()
        Base64.getEncoder().encodeToString(bytes)
    } catch (e: Exception) {
        e.printStackTrace(stream)
        log("❌ Error encoding image to Base64: ${e.message}", false)
        null
    }
}

fun Int.scaled(): Int {
    return (this / scaleFactor).toInt()
}


fun replaceRandomNumbers(input: String): String {
    val regex = Regex("%randomNumber(\\d+)%")
    return regex.replace(input) { match ->
        val length = match.groupValues[1].toInt()
        generateRandomNumber(length)
    }
}

fun generateRandomNumber(length: Int): String {
    return (1..length).map { Random.nextInt(0, 10) }.joinToString("")
}

fun log(message: String, toScreen: Boolean) {
    if (toScreen) {
        println(message)
    }
    stream.append("$message\n")
}

fun fail(message: String) {
    log("❌ $message", true)
    throw Exception(message)
}

fun silentFail(message: String) {
    log("❌ An error has occurred", true)
    log("❌ $message", false)
    throw Exception(message)
}


fun resizeImage(inputPath: String, outputPath: String, minSize: Int): Boolean {
    return try {
        val originalImage: BufferedImage = ImageIO.read(File(inputPath))
        val width = originalImage.width
        val height = originalImage.height

        scaleFactor = minSize.toDouble() / minOf(width, height)
        val newWidth = (width * scaleFactor).toInt()
        val newHeight = (height * scaleFactor).toInt()

        val resizedImage = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = resizedImage.createGraphics()
        graphics.drawImage(originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null)
        graphics.dispose()

        ImageIO.write(resizedImage, "png", File(outputPath))
        true
    } catch (e: Exception) {
        log("Error resizing image: ${e.message}", false)
        false
    }
}

fun applyBlackBar(imagePath: String, percentage: Double = Config.current.coverMobileStatusBarPercentage): Boolean {
    return try {
        val imageFile = File(imagePath)
        val image = ImageIO.read(imageFile)
        val heightToCover = (image.height * percentage).toInt()

        val graphics: Graphics2D = image.createGraphics()
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, image.width, heightToCover)
        graphics.dispose()

        ImageIO.write(image, "png", imageFile)
        true
    } catch (e: Exception) {
        log("❌ Error applying black bar: ${e.message}", false)
        false
    }
}
