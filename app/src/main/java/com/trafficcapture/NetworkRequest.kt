package com.trafficcapture

data class NetworkRequest(
    val url: String,
    val method: String,
    val timestamp: Long,
    val size: Int,
    val status: String,
    val headers: Map<String, String> = emptyMap(),
    val requestBody: String = "",
    val responseBody: String = ""
) {
    fun getFormattedTimestamp(): String {
        val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
        return format.format(date)
    }
    
    fun getFormattedSize(): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
    }
}
