package com.nfarima.aeder.script

import java.io.File

class MacroParser {
    private val macros = mutableMapOf<String, List<String>>()

    fun parse(file: File) {
        macros.clear()
        val lines = file.readLines()
        var currentMacroName: String? = null
        val currentMacroContent = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> continue
                trimmed.startsWith("macro ") -> {
                    if (currentMacroName != null) {
                        throw IllegalArgumentException("Nested or unclosed macro detected: $currentMacroName")
                    }
                    currentMacroName = trimmed.removePrefix("macro ").trim()
                }
                trimmed == "endMacro" -> {
                    if (currentMacroName == null) {
                        throw IllegalArgumentException("'endMacro' found without matching 'macro'")
                    }
                    macros[currentMacroName] = currentMacroContent.toList()
                    currentMacroName = null
                    currentMacroContent.clear()
                }
                currentMacroName != null -> currentMacroContent.add(trimmed)
                else -> continue // Ignore lines outside macro blocks
            }
        }

        if (currentMacroName != null) {
            throw IllegalArgumentException("Unclosed macro detected: $currentMacroName")
        }
    }

    fun getMacro(name: String): List<String>? {
        return macros[name]
    }

    fun expandMacros(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("m ")) {
                val macroName = trimmed.removePrefix("m ").trim()
                val macroContent = macros[macroName]
                if (macroContent != null) {
                    result.addAll(macroContent)
                } else {
                    throw IllegalArgumentException("Undefined macro: $macroName")
                }
            } else {
                result.add(line)
            }
        }
        return result
    }
}
