package com.bingwa.mobile

object AppVisibility {
    @Volatile
    var isForeground: Boolean = false
        private set

    fun setForeground(value: Boolean) {
        isForeground = value
    }
}

