package com.nfarima.aeder.script

import com.nfarima.aeder.service.VisionResponse
import com.nfarima.aeder.service.VisionService
import com.nfarima.aeder.config.Config.Companion.current
import com.nfarima.aeder.uibridge.ADBService
import com.nfarima.aeder.util.*
import com.nfarima.aeder.workingDir
import kotlinx.coroutines.delay
import java.io.File

data class Step(
    val name: String,
    val assertions: List<String>,
    val actions: List<String>,
    var isLastStep: Boolean = false,
) {
    override fun toString(): String {
        return "name = $name (${assertions.size} assertions, ${actions.size} actions)"
    }
}

sealed class Task {
    data class Install(val command: String) : Task()
    data class Uninstall(val packageName: String) : Task()
    data class ClearData(val packageName: String) : Task()
    data class OpenApp(val packageName: String) : Task()
    data class Back(val notUsed: String) : Task()
    data class Home(val notUsed: String) : Task()
    data class RecentApps(val notUsed: String) : Task()
    data class StepTask(
        val step: Step,
        val type: StepType = StepType.NORMAL,
        val maxRepeats: Int = 10,
    ) : Task()

    data class Wait(val seconds: Int) : Task()
    data class Description(val description: String) : Task()
}

enum class StepSection {
    ASSERTIONS,
    ACTIONS,
    MEMORIES,
    NONE,
}

enum class StepType {
    NORMAL,
    REPEATING,
    CREATIVE,
    NONE
}

class ScriptParser(
    private val adb: ADBService,
    private val visionService: VisionService
) {
    private val scriptFileName = "script.txt"
    private val macrosFileName = "macros.txt"

    private val scriptFile = File(workingDir, scriptFileName)
    private val macrosFile = File(workingDir, macrosFileName)

    private val tasks = mutableListOf<Task>()

    private var previousRequestId: String? = null

    fun compile() {
        if (!scriptFile.exists()) {
            log("❌ Error: Script file '$scriptFileName' not found.", true)
            return
        }

        log("🔍 Compiling script: $scriptFileName", true)

        var lines = scriptFile.readLines()
        val macroParser = MacroParser()
        macroParser.parse(macrosFile)
        lines = macroParser.expandMacros(lines)

        var stepType = StepType.NONE

        val insideAnyStep: () -> Boolean = { stepType != StepType.NONE }

        var currentStepSubSection: StepSection = StepSection.NONE

        var stepName = ""
        val assertions = mutableListOf<String>()
        val actions = mutableListOf<String>()
        val memories = mutableListOf<String>()

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
                    stepType = StepType.NORMAL
                    stepName = command.removePrefix("step ").trim()
                    assertions.clear()
                    actions.clear()
                    currentStepSubSection = StepSection.NONE
                }

                command.startsWith("repeating step ", ignoreCase = true) -> {
                    if (insideAnyStep()) error("❌ Error: Step '$stepName' is missing 'assertions:' or 'actions:' before 'end'.")
                    stepType = StepType.REPEATING
                    stepName = command.removePrefix("repeating step ").trim()
                    assertions.clear()
                    actions.clear()
                    currentStepSubSection = StepSection.NONE
                }

                command.startsWith("creative step ", ignoreCase = true) -> {
                    if (insideAnyStep()) error("❌ Error: Step '$stepName' is missing 'assertions:' or 'actions:' before 'end'.")
                    stepType = StepType.CREATIVE
                    stepName = command.removePrefix("creative step ").trim()
                    assertions.clear()
                    actions.clear()
                    currentStepSubSection = StepSection.NONE
                }


                command.equals("assertions:", ignoreCase = true) -> {
                    currentStepSubSection = StepSection.ASSERTIONS
                }

                command.equals("actions:", ignoreCase = true) -> {
                    currentStepSubSection = StepSection.ACTIONS
                }

                command.equals("remember ", ignoreCase = true) -> {
                    currentStepSubSection = StepSection.MEMORIES

                }

                command.equals("end", ignoreCase = true) -> {
                    if (!insideAnyStep()) fail("❌ Error: 'end' found without a matching 'step'.")
                    val task = Task.StepTask(Step(stepName, assertions.toList(), actions.toList()), stepType)
                    tasks.add(task)
                    stepType = StepType.NONE
                    currentStepSubSection = StepSection.NONE
                }

                currentStepSubSection == StepSection.ASSERTIONS -> assertions.add(command)
                currentStepSubSection == StepSection.ACTIONS -> actions.add(command)
                currentStepSubSection == StepSection.MEMORIES -> memories.add(command)
            }
        }

        tasks.filterIsInstance<Task.StepTask>().lastOrNull()?.step?.isLastStep = true
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
                    if (task.type == StepType.NORMAL || task.type == StepType.CREATIVE) {
                        val result = processStep(task)
                        result != null
                    } else {
                        var stop = false
                        var counter = task.maxRepeats
                        var success = false
                        while (!stop) {
                            val result = processStep(task)
                            if (result == null) {
                                success = false
                                break
                            }

                            success = result.actions?.any {
                                it.contains("stop", ignoreCase = true)
                            } ?: false

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
        val summary = visionService.requestSummary(scriptFile.readLines())
        if (summary != null) {
            log("Status is: ${summary.status}", true)
            log("Summary: ${summary.summary}", true)
        }
        log("✅ Script execution completed!", true)
    }

    private suspend fun processStep(task: Task.StepTask): VisionResponse? {
        val step = task.step
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

        val temperature = if (task.type == StepType.CREATIVE) 0.9 else 0.1
        val response = visionService.processStep(step, base64Image, temperature, previousRequestId)

        previousRequestId = response?.requestId

        response ?: run {
            fail("❌ Failed to process screen contents for step: ${step.name}")
            return null
        }

        response.actions ?: kotlin.run {
            log("No actions found in response. Should probably fail?", true)
            return null
        }

        val failedAssertions = response.failedAssertions

        val passedAssertions = response.passedAssertions

        if (!failedAssertions.isNullOrEmpty()) {
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

        if (passedAssertions?.isNotEmpty() == true) {
            log("✅ Step '${step.name}' passed the following assertions:", true)
            passedAssertions.forEach { log("   - $it", true) }
            log("${passedAssertions.size} of ${step.assertions.size} passed", true)
        }

        val actions = response.actions ?: emptyList()
        log(message = "🔹Actions: ${actions.joinToString(separator = ",")}", toScreen = true)
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
                        if (!adb.inputTextSlowly(text)) {
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
