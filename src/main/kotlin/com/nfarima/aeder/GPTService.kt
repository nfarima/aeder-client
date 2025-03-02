package com.nfarima.aeder

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.gson.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import io.ktor.client.request.headers
import java.io.File

class GPTService {
    private val apiKey: String = File(workingDir,"credentials/gpt.key").readText().trim()

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

//    "details": "This is a login screen with a blue "Login" button at 320, 850. A Username input field at 120, 100. A Password input field at 120, 200.",

    private val strictPrompt = """
    You are EDER, a UI test bot. Your job is to analyze UI screenshots and return a **structured JSON response**.

    Your JSON response **must** always include:
    - `"status"`: `"success"` or `"failed"`
    - `"failed_assertions"`: A list of assertions that did not pass. Some assertions could be marked with "(optional)", include the full assertion name in the response
    - `"passed_assertions"`: A list of assertions that passed.
    - `"imageWidth"`: The width of the processed image.
    - `"imageHeight"`: The height of the processed image.
    - "details": "<FULL COMPLETE details about the screen, buttons, colors, exact coordinates of elements, deep understanding of the screen purpose. Use the received description to help you with that>",
   
    - `"actions"`: A list of UI actions to be performed, based on the received input actions. Each as a string.

    You will receive an image description, containing the center point coordinates of all UI elements to help you better understand the image.
    Use the coordinates to return the correct actions to be performed on the UI elements.
    When a scroll action is requested, return "swipe startX, startY, endX, endY". 
    To scroll down, start with a higher Y value and end with a lower Y value. To scroll up, start with a lower Y value and end with a higher Y value.
    Make sure you start and end within the bounds of the scrollable content. Do not start or end scrolls on bottom toolbars or top toolbars or any other non-scrollable areas.
    Double check the scrolling start point to ensure it is within the scrollable area.
    you can also invoke actions like "home", "back", "recent" if on an android device (they will be executed remotely)
    When a click/press/tap action is requested, return "tap X, Y"
    When an input action is requested, return "input X, Y 'text to input'"
    ### Example of a correct JSON response:
    ```json
    {
        "status": "success",
        "failed_assertions": ["Assertion 1 failed"],
        "passed_assertions": ["Assertion 2 passed"],
        "imageWidth": 768,
        "imageHeight": 1705,
        "actions": ["tap 320,850", "input 120,100 'hello'", "swipe 100,200,300,400", "home","back"]
    }
    ```

    Always return a **valid JSON object** without extra text or explanations.
    
""".trimIndent()


    private val creativePrompt = """
    You are EDER, a UI navigation bot. Your job is to analyze UI screenshots and return navigation actions as a **structured JSON response**.
    you will receive one or more assertions to make sure you are on the right track.
    you will receive one or more actions to perform.
    
    you will return a list of actions to navigate to the target screen.
        
    When asked to navigate on your own or find your own way to something, take it one step at a time and navigate  by returning one or more of these actions:
    - "tap X, Y"
    - "input X, Y 'text to input'"
    - "swipe startX,startY,endX,endY"
    
    You can scroll, click or search your way to the target screen. Be creative. 
    For example, when in the settings screen, you can search for "dark mode" or "theme" to find the dark mode settings.
    To search just return an input action with the search query: "input x,y 'dark mode'". where x,y are the screen coordinates of the search bar.
     Or you can swipe to scroll down and find the desired content. Remember that a lot of times you can scroll down to find more content.
     You can scroll once or multiple times to find the desired content.
    
    Return a few actions at a time based on the current received screen. For example if you are on the home screen, 
    return 1-5 scroll/tap actions to navigate towards the desired screen. 
    Do not return multiple actions that would cause the screen contents to change more than once, you will receive screen updates as you progress.
    If you made a screen change, wait for the next screenshot to arrive before returning more actions.
    
    If you are stuck, add a {"navigation": "failed", "reason":"reason here"} to the root of the response body along with other failed assertions.
    If you arrived to the target screen, add {"navigation": "success"} to the root of the response body
    For intermediate steps add a {"description": "description of current screen"} to the root of the response body.
    Make sure to always return an action to navigate further or declare that navigation has failed.
    
        ### Example of a correct JSON response for a navigation task:
        ```json
        {
            "status": "success|failure",
            "navigation": "ongoing|success|failed",
            "failed_assertions": ["<the received assertions that failed>"],
            "passed_assertions": ["<the received assertions that passed>"],
            "imageWidth": <received image width>,
            "imageHeight": <received image height>,
            "actions": ["tap 320,850", "input 120,100 'hello'", "swipe 100,200,300,400"]
            "description": "<where you currently are and what you do next>"
        }
        ```
    
        
    """.trimIndent()


    // Data class for request structure
    data class Message(
        val role: String,
        val content: List<Map<String, Any>>
    )

    data class RequestBody(
        val model: String,
        val messages: List<Message>,
        val temperature: Double,
        @SerializedName("max_tokens")
        val maxTokens: Int = 3000,
    )


    suspend fun sendRequest(
        imageFileName: String,
        description: String,
        assertions: String,
        action: String,
        creative: Boolean = false
    ): JsonObject? {
        val base64Image = encodeImageToBase64(imageFileName) ?: return null

        return try {
            val requestBody = RequestBody(
                model = "gpt-4o",
                messages = listOf(
                    Message(
                        "system",
                        listOf(mapOf("type" to "text", "text" to (if (creative) creativePrompt else strictPrompt)))
                    ),
                    Message(
                        "user",
                        listOf(
                            mapOf("type" to "text", "text" to "Analyze this image for UI correctness."),
                            mapOf(
                                "type" to "image_url",
                                "image_url" to mapOf(
                                    "url" to "data:image/png;base64,$base64Image",
                                    "detail" to "high"
                                )
                            )
                        )
                    ),
                    Message(
                        "user",
                        listOf(
                            mapOf(
                                "type" to "text",
                                "text" to "Description:\n $description\n Assertions:\n $assertions.\n\n Actions:\n $action."
                            )
                        )
                    )
                ),

                temperature = 0.0
            )

            val response: HttpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                headers {
                    append("Authorization", "Bearer $apiKey")
                    append("Content-Type", "application/json")
                }
                setBody(gson.toJson(requestBody))
            }

            val jsonResponse = response.bodyAsText()
            log("🔹 Raw Response: $jsonResponse", false)

            JsonParser.parseString(jsonResponse).asJsonObject
        } catch (e: JsonSyntaxException) {
            e.printStackTrace(stream)
            log("❌ Error parsing GPT JSON response: ${e.message}", false)
            null
        } catch (e: Exception) {
            e.printStackTrace(stream)
            log("❌ Error communicating with GPT: ${e.message}", false)
            null
        }
    }
}
