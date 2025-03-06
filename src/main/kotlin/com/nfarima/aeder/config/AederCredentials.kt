package com.nfarima.aeder.config

import com.nfarima.aeder.util.persisted

class AederCredentials {
    val lambdaUrl = "https://dkon4qobe5.execute-api.eu-north-1.amazonaws.com/prod/process"
    val apiKey = "general access key here"
    val clientKey = "client key here"

    companion object {
        var saved by persisted(AederCredentials(), "config/credentials.json")
    }
}
