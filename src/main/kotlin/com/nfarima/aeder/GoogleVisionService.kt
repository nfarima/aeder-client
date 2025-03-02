package com.nfarima.aeder

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.gson.*
import com.google.auth.oauth2.GoogleCredentials
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class GoogleVisionService {

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 60_000 // 60 seconds
            endpoint {
                connectTimeout = 60_000 // 60 seconds
                socketTimeout = 60_000  // 60 seconds
            }
        }
        install(ContentNegotiation) {
            gson()
        }
    }

    private val gson = Gson()

    // Set Google credentials from JSON file in the current directory
    private val credentialsFile = "credentials/gcv.json"

    // Function to get OAuth 2.0 Bearer Token
    private fun getAccessToken(): String {
        val credentials = GoogleCredentials.fromStream(File(workingDir, credentialsFile).inputStream())
            .createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))

        credentials.refreshIfExpired()
        return credentials.accessToken.tokenValue
    }

    suspend fun analyzeImage(imagePath: String): JsonObject? {
        val base64Image = encodeImageToBase64(imagePath) ?: return null

        val requestBody = mapOf(
            "requests" to listOf(
                mapOf(
                    "image" to mapOf("content" to base64Image),
                    "features" to listOf(
                        mapOf("type" to "TEXT_DETECTION"), // Extracts UI text elements
                        mapOf("type" to "OBJECT_LOCALIZATION"), // Detects UI objects with coordinates
                        mapOf("type" to "LABEL_DETECTION") // Identifies UI components
                    )
                )
            )
        )

        return try {
            val accessToken = getAccessToken() // Get OAuth token
            val response: HttpResponse = client.post("https://vision.googleapis.com/v1/images:annotate") {
                headers {
                    append("Authorization", "Bearer $accessToken")
                    append("Content-Type", "application/json")
                }
                setBody(gson.toJson(requestBody))
            }

            val jsonResponse = response.bodyAsText()
            //log("🔹 Raw Response Received \n $jsonResponse", false) // Debugging

            val parsedResponse = JsonParser.parseString(jsonResponse).asJsonObject
            return extractStructuredJson(parsedResponse)

        } catch (e: Exception) {
            e.printStackTrace(stream)
            log("❌ Error communicating with Google Vision API: ${e.message}", false)
            null
        }
    }

    private fun extractStructuredJson(json: JsonObject): JsonObject {
        val responses = json.getAsJsonArray("responses") ?: return JsonObject()
        val result = JsonObject()

        responses.forEach { response ->
            // Extract UI labels (text elements)
            response.asJsonObject.getAsJsonArray("textAnnotations")?.let { textAnnotations ->
                textAnnotations.drop(1).forEach { text ->  // Drop the first entry (full text block)
                    val textObj = text.asJsonObject
                    val description = textObj.get("description")?.asString?.lowercase() ?: ""

                    // Extract bounding box
                    val boundingBox = textObj.getAsJsonObject("boundingPoly")?.getAsJsonArray("vertices")
                    if (boundingBox != null && boundingBox.size() == 4) {
                        val x1 = boundingBox[0].asJsonObject.get("x")?.asInt ?: 0
                        val y1 = boundingBox[0].asJsonObject.get("y")?.asInt ?: 0
                        val x2 = boundingBox[2].asJsonObject.get("x")?.asInt ?: 0
                        val y2 = boundingBox[2].asJsonObject.get("y")?.asInt ?: 0

                        // Compute center coordinates
                        val centerX = (x1 + x2) / 2
                        val centerY = (y1 + y2) / 2

                        result.addProperty(description, "[$centerX, $centerY]")
                    }
                }
            }

            // Extract UI elements (buttons, input fields, etc.)
            response.asJsonObject.getAsJsonArray("localizedObjectAnnotations")?.let { objects ->
                for (obj in objects) {
                    val objJson = obj.asJsonObject
                    val name = objJson.get("name")?.asString?.lowercase() ?: continue

                    // Get bounding box
                    val boundingBox = objJson.getAsJsonObject("boundingPoly")?.getAsJsonArray("normalizedVertices")
                    if (boundingBox != null && boundingBox.size() == 4) {
                        val x1 = (boundingBox[0].asJsonObject.get("x")?.asDouble ?: 0.0) * 1000
                        val y1 = (boundingBox[0].asJsonObject.get("y")?.asDouble ?: 0.0) * 1000
                        val x2 = (boundingBox[2].asJsonObject.get("x")?.asDouble ?: 0.0) * 1000
                        val y2 = (boundingBox[2].asJsonObject.get("y")?.asDouble ?: 0.0) * 1000

                        // Compute center coordinates
                        val centerX = ((x1 + x2) / 2).toInt()
                        val centerY = ((y1 + y2) / 2).toInt()

                        result.addProperty(name, "[$centerX, $centerY]")
                    }
                }
            }
        }
        return result
    }
}
