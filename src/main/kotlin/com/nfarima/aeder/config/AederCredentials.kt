package com.nfarima.aeder.config

import com.nfarima.aeder.persisted

class AederCredentials {
    val lambdaUrl = "https://5dnw5sw9j1.execute-api.eu-north-1.amazonaws.com/Default/aeder-vision"
    val apiKey = "general access key here"
    val clientKey = "client key here"

    companion object {
        var saved by persisted(AederCredentials(), "config/credentials.json")
    }
}
