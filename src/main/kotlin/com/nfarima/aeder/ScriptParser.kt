package com.nfarima.aeder

import com.nfarima.aeder.config.Config.Companion.current
import com.nfarima.aeder.util.*
import kotlinx.coroutines.delay
import java.io.File

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
    data class StepTask(val step: Step, val repeating: Boolean = false, val maxRepeats: Int = 20) : Task()
    data class Wait(val seconds: Int) : Task()
    data class Description(val description: String) : Task()
}

class ScriptParser(
    private val scriptFilename: String,
    private val adb: ADBService,
    private val visionService: VisionService
) {
    private val scriptFile = File(workingDir, scriptFilename)

    private val tasks = mutableListOf<Task>()

    fun compile() {
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

            if (command.startsWith("#") || command.isEmpty()) continue
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

                !insideAnyStep() && command.startsWith("description:", ignoreCase = true) -> {
                    tasks.add(Task.Description(command.removePrefix("description:").trim()))
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

                command.startsWith("repeating step ", ignoreCase = true) -> {
                    if (insideAnyStep()) error("❌ Error: Step '$stepName' is missing 'assertions:' or 'actions:' before 'end'.")
                    insideRepeatingStep = true
                    stepName = command.removePrefix("repeating step ").trim()
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
                    hasAssertions = false
                    hasActions = true
                }

                command.equals("end", ignoreCase = true) -> {
                    if (!insideAnyStep()) fail("❌ Error: 'end' found without a matching 'step'.")
                    val task = Task.StepTask(Step(stepName, assertions.toList(), actions.toList()), insideRepeatingStep)
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

        log("🚀 Starting user script execution...", true)


        for ((index, task) in tasks.withIndex()) {
            log("🔹 Executing task ${index + 1}/${tasks.size}: $task", true)

            val success = when (task) {

                is Task.Description -> {
                    visionService.updateDescription(task.description)
                    true
                }

                is Task.Install -> adb.installApk()
                is Task.Uninstall -> adb.uninstallApp(task.packageName)
                is Task.ClearData -> {
                    val result = adb.clearAppData(task.packageName)
                    delay(current.shortDelay)
                    result
                }

                is Task.OpenApp -> {
                    val result = adb.openApp(task.packageName)
                    delay(current.longDelay)
                    result
                }

                is Task.Back -> {
                    adb.back()
                    delay(current.defaultActionDelay)
                    true
                }

                is Task.Home -> {
                    adb.home()
                    delay(current.shortDelay)
                    true
                }

                is Task.RecentApps -> {
                    adb.recent()
                    delay(current.defaultActionDelay)
                    true
                }

                is Task.Wait -> {
                    delay(task.seconds * 1000L)
                    true
                }

                is Task.StepTask -> {
                    if (!task.repeating) {
                        val result = processStep(task.step)
                        result != null
                    } else {
                        var stop = false
                        var counter = task.maxRepeats
                        var success = false
                        while (!stop) {
                            val result = processStep(task.step)
                            if (result == null) {
                                success = false
                                break
                            }

                            success = result.actions.any {
                                it.contains("stop", ignoreCase = true)
                            }

                            stop = counter-- <= 0 || success
                        }
                        success
                    }
                }

            }

            if (!success) {
                fail("❌ Failure encountered, stopping execution.")
                return
            }

            delay(current.defaultActionDelay)
            visionService.dumpContext()
        }
        val summary = visionService.requestSummary(scriptFile.readLines(),)
        if (summary != null) {
            log("Status is: ${summary.status}", true)
            log("Summary: ${summary.summary}", true)
        }
        log("✅ Script execution completed!", true)
    }

    private suspend fun processStep(step: Step, creative: Boolean = false): VisionResponse? {
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

        log("📡 Sending image to Aeder Vision Service for step: ${step.name}", true)

        val response = visionService.processStep(step, base64Image, creative)

        response ?: run {
            fail("❌ Failed to process screen contents for step: ${step.name}")
            return null
        }

        val failedAssertions = response.failedAssertions

        val passedAssertions = response.passedAssertions

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

        val actions = response.actions
        log("🔹Actions: ${actions.joinToString(separator = ",")}", true)
        executeActions(actions)

        return response
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
                        delay(current.defaultActionDelay)
                        log("Input text: $text", false)
                        if (!adb.inputText(text)) {
                            silentFail("❌ Error inputting text: $text")
                        }
                    }
                }

                action == "back" -> adb.back()
            }
            delay(current.defaultActionDelay)
        }
    }

    private fun processExtras(command: String): String {
        return replaceRandomNumbers(command)
    }

}
