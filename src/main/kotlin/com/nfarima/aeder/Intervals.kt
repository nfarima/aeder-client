package com.nfarima.aeder

class Intervals {
    var longDelay = 3000L
    var defaultActionDelay = 1000L
    var shortDelay = 500L

    companion object {
        var intervals by persisted(Intervals())
    }
}
