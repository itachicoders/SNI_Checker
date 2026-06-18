package com.example.snichecker.data

import android.content.Context
import android.os.Environment
import android.webkit.URLUtil
import com.example.snichecker.ProbeResult
import com.example.snichecker.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class SniScanner(
    private val context: Context
) {
    private val stopRequested = AtomicBoolean(false)
    private val activeSockets = ConcurrentHashMap.newKeySet<SSLSocket>()

    private val storageDir: File by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SniChecker"
        )
    }

    private val webCacheDir: File by lazy {
        File(storageDir, "web_cache")
    }

    fun defaultConfig(): ScannerConfig {
        val defaultInputFile = File(storageDir, "sni.txt")
        runCatching {
            ensureWorkingDirs()
            ensureDefaultInputFile(defaultInputFile)
        }
        return ScannerConfig(
            inputSource = defaultInputFile.absolutePath,
            outputDirPath = File(storageDir, "scan_out").absolutePath,
            concurrency = 16,
            timeoutSeconds = 5
        )
    }

    fun requestStop() {
        stopRequested.set(true)
        activeSockets.forEach { socket ->
            runCatching { socket.close() }
        }
    }

    fun resetStopFlag() {
        stopRequested.set(false)
    }

    suspend fun scan(
        config: ScannerConfig,
        onEvent: suspend (ScannerEvent) -> Unit
    ): ScanSummary = withContext(Dispatchers.IO) {
        resetStopFlag()
        ensureWorkingDirs()

        val defaultInputFile = File(storageDir, "sni.txt")
        if (config.inputSource == defaultInputFile.absolutePath) {
            ensureDefaultInputFile(defaultInputFile)
        }

        val inputFile = prepareInputFile(config.inputSource, onEvent)
        val domains = readDomains(inputFile)

        if (domains.isEmpty()) {
            throw IllegalStateException("Список доменов пуст")
        }

        val workerCount = config.concurrency.coerceIn(1, MAX_WORKERS).coerceAtMost(domains.size)
        onEvent(
            ScannerEvent.Message(
                text = "Загружено доменов: ${domains.size}. Параллельность: $workerCount",
                type = LogType.Info
            )
        )
        onEvent(ScannerEvent.DomainsLoaded(total = domains.size, concurrency = workerCount))

        val outputDir = File(config.outputDirPath).apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("Не удалось создать папку результатов: $absolutePath")
            }
        }
        val resultsJsonFile = File(outputDir, "results.json")
        val workingFile = File(outputDir, "working_sni.txt")
        val sortedWorkingFile = File(outputDir, "working_sni_sorted_by_rtt.txt")

        resultsJsonFile.delete()
        workingFile.delete()
        sortedWorkingFile.delete()

        val lock = Any()
        val nextIndex = AtomicInteger(0)
        val workingResults = mutableListOf<WorkingSni>()
        val pendingResults = mutableListOf<ProbeResult>()
        var stats = ScanStats()
        var processed = 0
        var lastBatchAt = System.currentTimeMillis()

        val jsonWriter = BufferedWriter(FileWriter(resultsJsonFile, true), 64 * 1024)
        val txtWriter = BufferedWriter(FileWriter(workingFile, true), 64 * 1024)

        suspend fun emitPending(force: Boolean) {
            val event = synchronized(lock) {
                val now = System.currentTimeMillis()
                val shouldEmit = force || pendingResults.size >= RESULT_BATCH_SIZE || now - lastBatchAt >= RESULT_BATCH_INTERVAL_MS
                if (!shouldEmit || pendingResults.isEmpty()) {
                    null
                } else {
                    val batch = pendingResults.toList()
                    pendingResults.clear()
                    lastBatchAt = now
                    ScannerEvent.ResultBatch(
                        results = batch,
                        processed = processed,
                        total = domains.size,
                        stats = stats
                    )
                }
            }

            if (event != null) {
                onEvent(event)
            }
        }

        try {
            coroutineScope {
                val jobs = List(workerCount) {
                    launch(Dispatchers.IO) {
                        while (isActive && !stopRequested.get()) {
                            val index = nextIndex.getAndIncrement()
                            if (index >= domains.size) break

                            val result = probeSni(domains[index], config.timeoutSeconds)

                            synchronized(lock) {
                                processed += 1
                                stats = when (result.status) {
                                    Status.WORKING -> {
                                        workingResults += WorkingSni(result.domain, result.rttMs)
                                        stats.copy(ok = stats.ok + 1)
                                    }
                                    Status.BLOCKED -> stats.copy(blocked = stats.blocked + 1)
                                    Status.INCONCLUSIVE -> stats.copy(inconclusive = stats.inconclusive + 1)
                                }

                                writeResult(jsonWriter, txtWriter, result)
                                pendingResults += result
                            }

                            emitPending(force = false)
                        }
                    }
                }

                jobs.joinAll()
            }
            emitPending(force = true)
        } finally {
            runCatching { jsonWriter.flush() }
            runCatching { txtWriter.flush() }
            runCatching { jsonWriter.close() }
            runCatching { txtWriter.close() }
        }

        writeSortedResults(sortedWorkingFile, workingResults)

        val summary = ScanSummary(
            ok = stats.ok,
            blocked = stats.blocked,
            inconclusive = stats.inconclusive,
            processed = processed,
            total = domains.size,
            resultsJsonPath = resultsJsonFile.absolutePath,
            workingTxtPath = workingFile.absolutePath,
            sortedTxtPath = sortedWorkingFile.absolutePath,
            wasStopped = stopRequested.get()
        )

        onEvent(ScannerEvent.Completed(summary))
        summary
    }

    private fun ensureWorkingDirs() {
        if (!storageDir.exists() && !storageDir.mkdirs()) {
            throw IllegalStateException("Не удалось создать папку $storageDir. Проверьте разрешение на доступ ко всем файлам.")
        }
        if (!webCacheDir.exists() && !webCacheDir.mkdirs()) {
            throw IllegalStateException("Не удалось создать папку кэша $webCacheDir")
        }
    }

    private fun copyAssetFileToStorage(assetFileName: String, targetFile: File) {
        context.assets.open(assetFileName).use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun ensureDefaultInputFile(defaultInputFile: File) {
        if (!defaultInputFile.exists()) {
            copyAssetFileToStorage("sni.txt", defaultInputFile)
        }
    }

    private suspend fun prepareInputFile(
        source: String,
        onEvent: suspend (ScannerEvent) -> Unit
    ): File {
        if (URLUtil.isNetworkUrl(source)) {
            onEvent(ScannerEvent.Message("Источник определен как ссылка", LogType.Info))
            onEvent(ScannerEvent.Message("Скачивание списка...", LogType.Info))

            val cacheFile = getCacheFileForUrl(source)
            return try {
                downloadFile(source, cacheFile)
                onEvent(ScannerEvent.Message("Список обновлен и сохранен в кэш", LogType.Success))
                cacheFile
            } catch (error: Exception) {
                if (cacheFile.exists()) {
                    onEvent(
                        ScannerEvent.Message(
                            text = "Ошибка загрузки: ${error.message}. Используется кэш ${cacheFile.absolutePath}",
                            type = LogType.Warning
                        )
                    )
                    cacheFile
                } else {
                    throw IllegalStateException("Ошибка загрузки списка: ${error.message}", error)
                }
            }
        }

        val file = File(source)
        if (!file.exists()) {
            throw IllegalStateException("Файл ${file.absolutePath} не найден")
        }

        return try {
            file.inputStream().use { }
            file
        } catch (error: Exception) {
            throw IllegalStateException(
                "Ошибка доступа к файлу: ${error.message}. Проверьте доступ к внешнему хранилищу.",
                error
            )
        }
    }

    private fun getCacheFileForUrl(url: String): File {
        val md5 = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

        return File(webCacheDir, "list_$md5.txt")
    }

    private fun downloadFile(urlStr: String, targetFile: File) {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        connection.connect()

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP Code: ${connection.responseCode}")
            }

            BufferedInputStream(connection.inputStream).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readDomains(inputFile: File): List<String> {
        val domains = LinkedHashSet<String>()

        inputFile.useLines(Charsets.UTF_8) { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith('#')) {
                    val domain = cleanDomain(trimmed)
                    if (domain.isNotEmpty()) {
                        domains += domain
                    }
                }
            }
        }

        return domains.toList()
    }

    private fun cleanDomain(domain: String): String {
        var result = domain.trim()
        val schemeIndex = result.indexOf("://")
        if (schemeIndex >= 0) result = result.substring(schemeIndex + 3)

        val slashIndex = result.indexOf('/')
        if (slashIndex >= 0) result = result.substring(0, slashIndex)

        val portIndex = result.indexOf(':')
        if (portIndex >= 0) result = result.substring(0, portIndex)

        return result.trim()
    }

    private fun writeResult(
        jsonWriter: BufferedWriter,
        txtWriter: BufferedWriter,
        result: ProbeResult
    ) {
        jsonWriter.write(result.toJson())
        jsonWriter.newLine()

        if (result.status == Status.WORKING) {
            txtWriter.write(result.domain)
            txtWriter.newLine()
        }
    }

    private fun writeSortedResults(
        sortedWorkingFile: File,
        workingResults: List<WorkingSni>
    ) {
        if (workingResults.isEmpty()) return

        BufferedWriter(FileWriter(sortedWorkingFile, false), 64 * 1024).use { writer ->
            workingResults
                .sortedWith(compareBy<WorkingSni> { it.rttMs ?: Int.MAX_VALUE }.thenBy { it.domain })
                .forEach { result ->
                    writer.write("${result.domain} (${result.rttMs ?: "N/A"}ms)")
                    writer.newLine()
                }
        }
    }

    private fun probeSni(domain: String, timeoutSeconds: Int): ProbeResult {
        var socket: SSLSocket? = null

        return try {
            val startTs = System.currentTimeMillis()
            socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            activeSockets += socket
            socket.soTimeout = timeoutSeconds * 1_000

            val sslParameters = socket.sslParameters ?: SSLParameters()
            sslParameters.serverNames = listOf(SNIHostName(domain))
            socket.sslParameters = sslParameters

            socket.connect(InetSocketAddress(domain, 443), timeoutSeconds * 1_000)
            socket.startHandshake()

            val rtt = (System.currentTimeMillis() - startTs).toInt()
            val request = "HEAD / HTTP/1.1\r\nHost: $domain\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(request.toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()

            try {
                val bytesRead = socket.inputStream.read(ByteArray(512))
                if (bytesRead > 0) {
                    ProbeResult(domain, Status.WORKING, "Connection OK ${rtt}ms", rtt)
                } else {
                    ProbeResult(domain, Status.INCONCLUSIVE, "TLS OK, no response bytes", rtt)
                }
            } catch (_: SocketTimeoutException) {
                ProbeResult(domain, Status.BLOCKED, "Timeout after HEAD", rtt)
            } catch (_: Exception) {
                ProbeResult(domain, Status.BLOCKED, "Reset after HEAD", rtt)
            }
        } catch (_: SocketTimeoutException) {
            ProbeResult(domain, Status.BLOCKED, "Timeout", null)
        } catch (_: SSLHandshakeException) {
            ProbeResult(domain, Status.BLOCKED, "Handshake Fail", null)
        } catch (_: ConnectException) {
            ProbeResult(domain, Status.BLOCKED, "Conn Refused", null)
        } catch (_: UnknownHostException) {
            ProbeResult(domain, Status.BLOCKED, "DNS Fail", null)
        } catch (error: Exception) {
            ProbeResult(
                domain = domain,
                status = Status.BLOCKED,
                detail = error::class.java.simpleName.ifBlank { "Unknown" },
                rttMs = null
            )
        } finally {
            if (socket != null) {
                activeSockets -= socket
            }
            runCatching { socket?.close() }
        }
    }

    private companion object {
        const val MAX_WORKERS = 32
        const val RESULT_BATCH_SIZE = 200
        const val RESULT_BATCH_INTERVAL_MS = 1_000L
    }
}

private data class WorkingSni(
    val domain: String,
    val rttMs: Int?
)

data class ScannerConfig(
    val inputSource: String,
    val outputDirPath: String,
    val concurrency: Int,
    val timeoutSeconds: Int
)

data class ScanStats(
    val ok: Int = 0,
    val blocked: Int = 0,
    val inconclusive: Int = 0
)

data class ScanSummary(
    val ok: Int,
    val blocked: Int,
    val inconclusive: Int,
    val processed: Int,
    val total: Int,
    val resultsJsonPath: String,
    val workingTxtPath: String,
    val sortedTxtPath: String,
    val wasStopped: Boolean
)

sealed interface ScannerEvent {
    data class Message(val text: String, val type: LogType) : ScannerEvent
    data class DomainsLoaded(val total: Int, val concurrency: Int) : ScannerEvent
    data class ResultReceived(
        val result: ProbeResult,
        val processed: Int,
        val total: Int,
        val stats: ScanStats
    ) : ScannerEvent

    data class ResultBatch(
        val results: List<ProbeResult>,
        val processed: Int,
        val total: Int,
        val stats: ScanStats
    ) : ScannerEvent

    data class Completed(val summary: ScanSummary) : ScannerEvent
}

enum class LogType {
    Info,
    Success,
    Warning,
    Error
}
