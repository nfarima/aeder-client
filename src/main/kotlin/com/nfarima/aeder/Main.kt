package com.nfarima.aeder

import kotlinx.coroutines.runBlocking

var workingDir: String = System.getProperty("user.dir") + "/dist"

fun main(args: Array<String>) = runBlocking {
    println("Waking up Eder...")
    if (args.isNotEmpty()) {
        workingDir = args[0]
        println("Working dir: $workingDir")
    }
    initializeFiles()
    initializeLogging()


    val adb = ADBService()
    log("ADB initialized.", false)
    val gptService = GPTService()
    log("GPT service initialized.", false)
    val visionService = GoogleVisionService()
    log("Vision service initialized.", false)

    val scriptParser = ScriptParser("script.txt", adb, gptService, visionService)
    log("Script parser initialized.", false)
    scriptParser.compile()
    scriptParser.start()
}
