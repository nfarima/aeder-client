package com.nfarima.aeder.config

import com.nfarima.aeder.util.persisted

class AederCredentials {
    val lambdaUrl = "https://staging.aeder.ai/vision/process"
    val apiKey = "general access key here"
    val clientKey = "client key here"

    companion object {
        var saved by persisted(AederCredentials(), "config/credentials.json")
    }
}
