package com.nfarima.aeder

import java.io.*
import java.util.*
import kotlin.random.Random

lateinit var stream: PrintStream

var scaleFactor = 1.0

fun initializeLogging() {
    val currentSessionLogFile = "${workingDir}/logs/session-${System.currentTimeMillis()}.log"
    stream = PrintStream(FileOutputStream(currentSessionLogFile, true), true)
    log("Logging to $currentSessionLogFile", true)
}

fun initializeFiles() {
    File(workingDir, "images").mkdir()
    File(workingDir, "logs").mkdir()
    checkFile("${workingDir}/credentials/gpt.key")
    checkFile("${workingDir}/credentials/gcv.json")
    checkFile("${workingDir}/script.txt")
}

fun checkFile(fileName: String) {
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

fun extractJson(content: String): String {
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    return if (start != -1 && end != -1 && start < end) {
        content.substring(start, end + 1).trim()
    } else {
        log("❌ Could not extract valid JSON from response.", false)
        "{}"
    }
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

// Logs to file by default, but can also log to screen
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
