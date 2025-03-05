package com.nfarima.aeder

import com.nfarima.aeder.config.AederCredentials
import com.nfarima.aeder.script.ScriptParser
import com.nfarima.aeder.service.VisionService
import com.nfarima.aeder.uibridge.ADBService
import com.nfarima.aeder.util.initializeFiles
import com.nfarima.aeder.util.initializeLogging
import com.nfarima.aeder.util.log
import kotlinx.coroutines.runBlocking

var workingDir: String = System.getProperty("user.dir") + "/dist"


fun main(args: Array<String>) = runBlocking {
    println("Waking up Aeder...")
    if (args.isNotEmpty()) {
        workingDir = args[0]
        println("Working dir: $workingDir")
    }
    initializeFiles()
    initializeLogging()

    val adb = ADBService()
    log("ADB initialized.", true)

    val credentials = AederCredentials.saved
    val visionService = VisionService(credentials.lambdaUrl, credentials.apiKey, credentials.clientKey)
    log("Vision service initialized.", true)

    val scriptParser = ScriptParser(adb, visionService)
    log("Script parser initialized.", true)

    scriptParser.compile()
    scriptParser.start()
}
