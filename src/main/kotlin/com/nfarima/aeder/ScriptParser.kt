package com.nfarima.aeder

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.nfarima.aeder.Intervals.Companion.intervals
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.gson.*
import kotlinx.coroutines.delay
import java.io.File
import javax.imageio.ImageIO

data class Step(
    val name: String,
    val assertions: List<String>,
    val actions: List<String>
) {
    override fun toString(): String {
        return "name = $name (${assertions.size} assertions, ${actions.size} actions)"
    }
}

// A Task can be a command or a step
sealed class Task {
    data class Install(val command: String) : Task()
    data class Uninstall(val packageName: String) : Task()
    data class ClearData(val packageName: String) : Task()
    data class OpenApp(val packageName: String) : Task()
    data class Back(val notUsed: String) : Task()
    data class Home(val notUsed: String) : Task()
    data class RecentApps(val notUsed: String) : Task()
    data class StepTask(val step: Step) : Task()
    data class RepeatingStep(val step: Step, val maxRepeats: Int = 20) : Task()
    data class Wait(val seconds: Int) : Task()
}

class ScriptParser(
    private val scriptFilename: String,
    private val adb: ADBService,
    private val gptService: GPTService,
    private val visionService: GoogleVisionService
) {
    private val lambdaUrl = "https://5dnw5sw9j1.execute-api.eu-north-1.amazonaws.com/Default/aeder-vision"
    private val apiKey = "6cd251f7-5915-45ac-91ec-f4e981d09c91"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { gson() }
        engine {
            requestTimeout = 60_000
            endpoint {
                connectTimeout = 60_000
                socketTimeout = 60_000
            }
        }
    }

    private val tasks = mutableListOf<Task>()

    fun compile() {
        val scriptFile = File(workingDir, scriptFilename)

        if (!scriptFile.exists()) {
            log("❌ Error: Script file '$scriptFilename' not found.", true)
            return
        }

        log("🔍 Compiling script: $scriptFilename", true)

        val lines = scriptFile.readLines()
        var insideStep = false
        var insideRepeatingStep = false

        val insideAnyStep: () -> Boolean = { insideStep || insideRepeatingStep }

        var hasAssertions = false
        var hasActions = false
        var stepName = ""
        val assertions = mutableListOf<String>()
        val actions = mutableListOf<String>()

        for (line in lines) {
            var command = line.trim()

            if (command.startsWith("#") || command.isEmpty()) continue  // ✅ Ignore comments & empty lines
            command = processExtras(command)
            when {
                !insideAnyStep() && command.equals("install", ignoreCase = true) -> tasks.add(Task.Install(command))

                !insideAnyStep() && command.startsWith(
                    "uninstall ",
                    ignoreCase = true
                ) -> tasks.add(Task.Uninstall(command.removePrefix("uninstall ").trim()))

                !insideAnyStep() && command.startsWith(
                    "cleardata ",
                    ignoreCase = true
                ) -> tasks.add(Task.ClearData(command.removePrefix("cleardata ").trim()))

                !insideAnyStep() && command.startsWith("open ", ignoreCase = true) -> tasks.add(
                    Task.OpenApp(
                        command.removePrefix("open ").trim()
                    )
                )

                !insideAnyStep() && command.trim().equals("back", ignoreCase = true) -> tasks.add(Task.Back(""))

                !insideAnyStep() && command.trim().equals("home", ignoreCase = true) -> tasks.add(Task.Home(""))

                !insideAnyStep() && command.trim().equals("recent", ignoreCase = true) -> tasks.add(Task.RecentApps(""))

                !insideAnyStep() && command.startsWith("wait ", ignoreCase = true) -> {
                    val seconds = command.removePrefix("wait ").trim().toIntOrNull()
                    if (seconds != null) tasks.add(Task.Wait(seconds))
                }

                command.startsWith("step ", ignoreCase = true) -> {
                    if (insideAnyStep()) error("❌ Error: Step '$stepName' is missing 'assertions:' or 'actions:' before 'end'.")
                    insideStep = true
                    stepName = command.removePrefix("step ").trim()
                    assertions.clear()
                    actions.clear()
                    hasAssertions = false
                    hasActions = false
                }

                command.startsWith("recursive step ", ignoreCase = true) -> {
                    if (insideAnyStep()) error("❌ Error: Recursive Step '$stepName' is missing 'assertions:' or 'actions:' before 'end'.")
                    insideRepeatingStep = true
                    stepName = command.removePrefix("recursive step  ").trim()
                    assertions.clear()
                    actions.clear()
                    hasAssertions = false
                    hasActions = false
                }


                command.equals("assertions:", ignoreCase = true) -> {
                    hasAssertions = true
                    hasActions = false
                }

                command.equals("actions:", ignoreCase = true) -> {
                    hasAssertions = false  // Reset assertions flag to avoid mis-adding action lines
                    hasActions = true
                }

                command.equals("end", ignoreCase = true) -> {
                    if (!insideAnyStep()) fail("❌ Error: 'end' found without a matching 'step'.")
                    val task = if (insideRepeatingStep) Task.RepeatingStep(
                        Step(
                            stepName,
                            assertions.toList(),
                            actions.toList()
                        )
                    )
                    else Task.StepTask(Step(stepName, assertions.toList(), actions.toList()))
                    tasks.add(task)
                    insideStep = false
                    insideRepeatingStep = false
                    hasAssertions = false
                    hasActions = false
                }

                hasAssertions -> assertions.add(command)
                hasActions -> actions.add(command)
            }
        }

        log("✅ Script compiled successfully!", true)
    }

    suspend fun start() {
        if (!adb.isDeviceConnected()) {
            fail("❌ No device/emulator detected. Please connect a device.")
            return
        }

        for ((index, task) in tasks.withIndex()) {
            log("🔹 Executing task ${index + 1}/${tasks.size}: $task", true)

            val success = when (task) {
                is Task.Install -> adb.installApk()
                is Task.Uninstall -> adb.uninstallApp(task.packageName)
                is Task.ClearData -> {
                    val result = adb.clearAppData(task.packageName)
                    delay(intervals.shortDelay)
                    result
                }

                is Task.OpenApp -> {
                    val result = adb.openApp(task.packageName)
                    delay(intervals.longDelay)
                    result
                }

                is Task.Back -> {
                    adb.back()
                    delay(intervals.defaultActionDelay)
                    true
                }

                is Task.Home -> {
                    adb.home()
                    delay(intervals.shortDelay)
                    true
                }

                is Task.RecentApps -> {
                    adb.recent()
                    delay(intervals.defaultActionDelay)
                    true
                }

                is Task.Wait -> {
                    delay(task.seconds * 1000L)
                    true
                }

                is Task.StepTask -> {
                    val result = processStep(task.step)
                    val success = result != null
                    success
                }

                is Task.RepeatingStep -> {
                    var stop = false
                    var success = false
                    var counter = task.maxRepeats
                    while (!stop && counter-- > 0) {
                        log("Navigating...  ${task.maxRepeats - counter}/${task.maxRepeats}", true)
                        val result = processStep(task.step, true)
                        //println(result)
                        val failure =
                            result == null || result["status"]?.asString == "failed" || result["navigation"]?.asString == "failed"
                        success = result != null && result["navigation"]?.asString == "success"
                        stop = failure || success
                        result?.get("description")?.asString?.let {
                            log("Description: $it", true)
                        }
                        if (failure) {
                            silentFail("❌ Failure encountered, stopping execution. Check gpt response for more details. (failure=true)")
                            return
                        }
                        if (success) {
                            log("✅ Navigation successful", true)
                        }
                    }
                    success
                }
            }

            if (!success) {
                fail("❌ Failure encountered, stopping execution.")
                return
            }

            delay(intervals.defaultActionDelay)
        }

        log("✅ Script execution completed!", true)
    }

    private fun getImageWidth(imagePath: String): Int? {
        return try {
            val imageFile = File(imagePath)
            val image = ImageIO.read(imageFile)
            image?.width
        } catch (e: Exception) {
            e.printStackTrace(stream)
            fail("❌ Error reading image width for $imagePath: ${e.message}")
            null
        }
    }

    suspend fun processStep(step: Step, creative: Boolean = false): JsonObject? {
        log("Processing screen contents for step: ${step.name}", true)

        val imageFile = adb.screenshot(step) ?: run {
            fail("❌ Failed to retrieve screenshot")
            return null
        }

        log("📡 Encoding screenshot to Base64", false)
        val base64Image = encodeImageToBase64(imageFile) ?: run {
            fail("❌ Failed to encode screenshot")
            return null
        }

        log("📡 Sending image to Aeder Vision Service for step: ${step.name}", false)

        val requestBody = mapOf(
            "image" to base64Image,
            "stepName" to step.name,
            "assertions" to step.assertions,
            "actions" to step.actions,
            "creative" to creative
        )

        val response: JsonObject? = try {
            val httpResponse: HttpResponse = client.post(lambdaUrl) {
                headers {
                    append("x-api-key", "DzynkReDXc6HgGPNu0ZTU8TuWohtJSh38grSvpGd")
                    append("x-client-api-key", "6cd251f7-5915-45ac-91ec-f4e981d09c91")
                    append("Content-Type", "application/json")
                }
                setBody(Gson().toJson(requestBody))
            }

            val jsonResponse = httpResponse.bodyAsText()
            log("🔹 Raw Lambda Response: $jsonResponse", false)

            JsonParser.parseString(jsonResponse).asJsonObject
        } catch (e: Exception) {
            log("❌ Error communicating with AWS Lambda: ${e.message}", true)
            null
        }

        response ?: run {
            fail("❌ Failed to process screen contents for step: ${step.name}")
            return null
        }

        val gptWidth = response["imageWidth"]?.asDouble
        val gptHeight = response["imageHeight"]?.asDouble
        if (gptWidth == null || gptHeight == null) {
            silentFail("❌ Failed to get image dimensions from Lambda response, response can't be trusted")
            return null
        }

        val failedAssertions =
            response.getAsJsonArray("failed_assertions")?.mapNotNull { it.asString } ?: emptyList()

        val passedAssertions =
            response.getAsJsonArray("passed_assertions")?.mapNotNull { it.asString } ?: emptyList()

        if (failedAssertions.isNotEmpty()) {
            log("❌ Step '${step.name}' failed the following assertions:", true)
            failedAssertions.forEach { log("   - $it", true) }
            val allOptional = failedAssertions.all { it.contains("optional", true) }
            if (!allOptional) {
                log("Assertions have failed, will not continue", true)
                return null
            } else {
                log("Will continue execution because only optional assertions failed", true)
            }
        }

        if (passedAssertions.isNotEmpty()) {
            log("✅ Step '${step.name}' passed the following assertions:", true)
            passedAssertions.forEach { log("   - $it", true) }
            log("${passedAssertions.size} of ${step.assertions.size} passed", true)
        }

        val actions = parseActionsFromResponse(response)
        executeActions(actions)

        return response
    }

    private fun parseActionsFromResponse(response: JsonObject): List<String> {
        return try {
            response.getAsJsonArray("actions")?.mapNotNull { it.asString } ?: emptyList()
        } catch (e: JsonSyntaxException) {
            e.printStackTrace(stream)
            silentFail("❌ Error parsing GPT JSON response: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            e.printStackTrace(stream)
            silentFail("❌ Unexpected error while processing GPT response: ${e.message}")
            emptyList()
        }
    }

    private suspend fun executeActions(actions: List<String>) {
        for (action in actions) {
            when {
                action.startsWith("tap ") -> {
                    val coords = action.removePrefix("tap ").trim().split(",").mapNotNull { it.trim().toIntOrNull() }
                    if (coords.size == 2) {
                        log("Tap at ${coords[0]}, ${coords[1]} scale:$scaleFactor", false)
                        adb.tap(coords[0].scaled(), coords[1].scaled())
                    }
                }

                action.trim().equals("home", ignoreCase = true) -> adb.home()
                action.trim().equals("back", ignoreCase = true) -> adb.back()
                action.trim().equals("recent", ignoreCase = true) -> adb.recent()

                action.startsWith("swipe ") -> {
                    val coords = action.removePrefix("swipe ").trim().split(",").mapNotNull { it.trim().toIntOrNull() }
                    if (coords.size == 4) {
                        adb.swipe(
                            coords[0].scaled(),
                            coords[1].scaled(),
                            coords[2].scaled(),
                            coords[3].scaled()
                        )
                    }
                }

                action.startsWith("input ") -> {
                    val inputPattern = Regex("""input (\d+),\s*(\d+) ['"](.*?)['"]""")
                    val match = inputPattern.find(action)

                    if (match != null) {
                        val (x, y, text) = match.destructured
                        val scaledX = x.trim().toInt().scaled()
                        val scaledY = y.trim().toInt().scaled()

                        log("Tap at $scaledX, $scaledY scale:$scaleFactor", false)
                        if (!adb.tap(scaledX, scaledY)) {
                            silentFail("❌ Error tapping at $scaledX, $scaledY")
                        }
                        delay(intervals.defaultActionDelay)
                        log("Input text: $text", false)
                        if (!adb.inputText(text)) {
                            silentFail("❌ Error inputting text: $text")
                        }
                    }
                }

                action == "back" -> adb.back()
            }
            delay(intervals.defaultActionDelay)
        }
    }

    private fun processExtras(command: String): String {
        return replaceRandomNumbers(command)
    }

}
