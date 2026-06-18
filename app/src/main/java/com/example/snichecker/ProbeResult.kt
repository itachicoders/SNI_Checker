package com.example.snichecker

data class ProbeResult(
    val domain: String,
    val status: Status,
    val detail: String,
    val rttMs: Int?
) {
    fun toJson(): String {
        val rttPart = rttMs?.let { "\"rtt_ms\":$it," } ?: ""
        return buildString {
            append("{\"domain\":\"")
            append(domain.escapeJson())
            append("\",\"status\":\"")
            append(status.name)
            append("\",\"detail\":\"")
            append(detail.escapeJson())
            append("\",")
            append(rttPart)
            append("\"ts\":")
            append(System.currentTimeMillis())
            append('}')
        }
    }

    private fun String.escapeJson(): String = buildString(length + 8) {
        for (ch in this@escapeJson) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
