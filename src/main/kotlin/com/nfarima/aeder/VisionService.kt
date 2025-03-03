package com.nfarima.aeder

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nfarima.aeder.util.log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.gson.*
import io.ktor.util.*

class VisionService(private val lambdaUrl: String, private val apiKey: String, private val clientKey: String) {
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

    private var description: String = ""
    var accumulatedContext = mutableListOf<String>()
    private val gson = Gson()

    private fun normalizeJson(json: String): String {
        val jsonObject = JsonParser.parseString(json).asJsonObject
        return Gson().toJson(jsonObject) // Ensures sorted keys
    }

    fun updateDescription(description: String) {
        this.description = description
        accumulatedContext.clear()
        accumulatedContext.add(description)
    }

    fun dumpContext() {
        accumulatedContext.forEach {
            log("🔹 Context: $it", true)
        }
    }

    suspend fun requestSummary(lines: List<String>): SummaryResponse? {
        val requestBody = mapOf(
            "action" to "summary",
            "script" to lines.joinToString(separator = "\n"),
            "context" to accumulatedContext.joinToString(separator = "\n")
        )
        val requestBodyJson = normalizeJson(gson.toJson(requestBody))

        return try {
            val httpResponse: HttpResponse = makeRequest(requestBodyJson)

            val jsonResponse = httpResponse.bodyAsText()
//            log("🔹 Raw Response: $jsonResponse", true)

            val result = Gson().fromJson(jsonResponse, SummaryResponse::class.java)
            result
        } catch (e: Exception) {
            println("❌ Error communicating with Vision service: ${e.message}")
            null
        }
    }

    suspend fun processStep(step: Step, base64Image: String, creative: Boolean = false): VisionResponse? {
        val requestBody = mapOf(
            "image" to base64Image,
            "stepName" to step.name,
            "description" to description,
            "assertions" to step.assertions,
            "actions" to step.actions,
            "creative" to creative,
            "context" to accumulatedContext.joinToString(separator = "\n")
        )
        val requestBodyJson = normalizeJson(gson.toJson(requestBody))


        return try {
            val httpResponse: HttpResponse = makeRequest(requestBodyJson)
            val headers = httpResponse.headers
//            log("🔹 Headers: $headers", true)

            val jsonResponse = httpResponse.bodyAsText()
            log(
                "🔹 ${httpResponse.headers["X-Cache-Status"]?.first()} Raw Vision Response: $jsonResponse",
                true
            )

            val result = Gson().fromJson(jsonResponse, VisionResponse::class.java)
            accumulatedContext.add(result.context)
            result
        } catch (e: Exception) {
            println("❌ Error communicating with Vision service: ${e.message}")
            null
        }
    }

    private suspend fun makeRequest(requestBodyJson: String): HttpResponse {
        val httpResponse: HttpResponse = client.post(lambdaUrl) {
            headers {
                append("x-api-key", apiKey)
                append("x-client-api-key", clientKey)
                append("Content-Type", "application/json")
            }
            setBody(requestBodyJson)
        }
        return httpResponse
    }

}

data class SummaryResponse(
    val status: String,
    val summary: String
)

data class VisionResponse(
    val status: String,
    val failedAssertions: List<String> = emptyList(),
    val passedAssertions: List<String> = emptyList(),
    val imageWidth: Int,
    val imageHeight: Int,
    val actions: List<String> = emptyList(),
    val width: Double,
    val height: Double,
    val context: String
)
