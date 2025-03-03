package com.nfarima.aeder.config

import com.nfarima.aeder.persisted

class Config {
    var longDelay = 3000L
    var defaultActionDelay = 1000L
    var shortDelay = 500L

    val coverMobileStatusBar = true
    val coverMobileStatusBarPercentage = 0.04
    companion object {
        var current by persisted(Config(), "config/intervals.json")
    }
}
