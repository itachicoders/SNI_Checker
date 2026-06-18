package com.example.snichecker.ui

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.snichecker.ProbeResult
import com.example.snichecker.Status
import com.example.snichecker.data.LogType
import com.example.snichecker.data.ScanStats
import com.example.snichecker.data.ScanSummary
import com.example.snichecker.data.ScannerConfig
import com.example.snichecker.data.ScannerEvent
import com.example.snichecker.data.SniScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class SniCheckerViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = SniScanner(application.applicationContext)
    private val _uiState = MutableStateFlow(SniCheckerUiState())
    val uiState: StateFlow<SniCheckerUiState> = _uiState.asStateFlow()

    private val logIds = AtomicLong(0)
    private var scanJob: Job? = null

    init {
        val defaults = scanner.defaultConfig()
        _uiState.update {
            it.copy(
                inputSource = defaults.inputSource,
                outputDir = defaults.outputDirPath,
                concurrency = defaults.concurrency.toString(),
                timeoutSeconds = defaults.timeoutSeconds.toString(),
                mode = ScanMode.Idle
            )
        }
        appendLog("Приложение готово", LogType.Info)
    }

    fun updateInputSource(value: String) = _uiState.update { it.copy(inputSource = value) }
    fun updateOutputDir(value: String) = _uiState.update { it.copy(outputDir = value) }
    fun updateConcurrency(value: String) = _uiState.update { it.copy(concurrency = value.filter(Char::isDigit)) }
    fun updateTimeout(value: String) = _uiState.update { it.copy(timeoutSeconds = value.filter(Char::isDigit)) }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun stopScan() {
        scanner.requestStop()
        scanJob?.cancel()
        _uiState.update {
            it.copy(
                mode = ScanMode.Stopped,
                isStartEnabled = true,
                isStopEnabled = false
            )
        }
        appendLog("Остановка: активные соединения закрываются", LogType.Warning)
    }

    fun startScan() {
        val current = _uiState.value
        val concurrency = current.concurrency.toIntOrNull()?.coerceIn(1, MAX_CONCURRENCY)
        val timeout = current.timeoutSeconds.toIntOrNull()?.coerceIn(1, 30)

        when {
            current.inputSource.isBlank() -> {
                showError("Укажите путь к файлу или URL списка SNI")
                return
            }
            current.outputDir.isBlank() -> {
                showError("Укажите папку для сохранения результатов")
                return
            }
            concurrency == null -> {
                showError("Параллельность должна быть числом от 1 до $MAX_CONCURRENCY")
                return
            }
            timeout == null -> {
                showError("Таймаут должен быть числом от 1 до 30 секунд")
                return
            }
        }

        scanner.requestStop()
        scanJob?.cancel()

        _uiState.update {
            it.copy(
                progress = 0,
                total = 0,
                stats = ScanStats(),
                logs = emptyList(),
                summary = null,
                errorMessage = null,
                mode = ScanMode.Preparing,
                isStartEnabled = false,
                isStopEnabled = true
            )
        }
        appendLog("Подготовка...", LogType.Info)

        val config = ScannerConfig(
            inputSource = current.inputSource.trim(),
            outputDirPath = current.outputDir.trim(),
            concurrency = concurrency,
            timeoutSeconds = timeout
        )

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                scanner.scan(config, ::handleScannerEvent)
            } catch (_: CancellationException) {
                // Stop/new start cancels the previous job intentionally.
            } catch (error: Exception) {
                val message = error.message ?: "Неизвестная ошибка"
                appendLog(message, LogType.Error)
                _uiState.update {
                    it.copy(
                        mode = ScanMode.Error,
                        errorMessage = message,
                        isStartEnabled = true,
                        isStopEnabled = false
                    )
                }
            }
        }
    }

    private suspend fun handleScannerEvent(event: ScannerEvent) {
        when (event) {
            is ScannerEvent.Message -> appendLog(event.text, event.type)
            is ScannerEvent.DomainsLoaded -> {
                _uiState.update {
                    it.copy(
                        total = event.total,
                        mode = ScanMode.Running
                    )
                }
            }
            is ScannerEvent.ResultReceived -> {
                updateWithResults(
                    results = listOf(event.result),
                    processed = event.processed,
                    total = event.total,
                    stats = event.stats
                )
            }
            is ScannerEvent.ResultBatch -> {
                updateWithResults(
                    results = event.results,
                    processed = event.processed,
                    total = event.total,
                    stats = event.stats
                )
            }
            is ScannerEvent.Completed -> {
                appendSummaryLogs(event.summary)
                _uiState.update {
                    it.copy(
                        summary = event.summary,
                        mode = if (event.summary.wasStopped) ScanMode.Stopped else ScanMode.Completed,
                        isStartEnabled = true,
                        isStopEnabled = false,
                        progress = event.summary.processed,
                        total = event.summary.total,
                        stats = ScanStats(
                            ok = event.summary.ok,
                            blocked = event.summary.blocked,
                            inconclusive = event.summary.inconclusive
                        )
                    )
                }
            }
        }
    }

    private fun appendSummaryLogs(summary: ScanSummary) {
        appendLog(if (summary.wasStopped) "Сканирование остановлено" else "Сканирование завершено", LogType.Info)
        appendLog("Рабочих: ${summary.ok}", LogType.Success)
        appendLog("JSON: ${summary.resultsJsonPath}", LogType.Info)
        appendLog("TXT: ${summary.workingTxtPath}", LogType.Info)
        appendLog("SORTED: ${summary.sortedTxtPath}", LogType.Info)
    }

    private fun updateWithResults(
        results: List<ProbeResult>,
        processed: Int,
        total: Int,
        stats: ScanStats
    ) {
        val items = results
            .asSequence()
            .filter { it.status == Status.WORKING }
            .map { result ->
                LogItem(
                    id = logIds.incrementAndGet(),
                    message = "[WORKING] ${result.domain} - ${result.rttMs ?: "-"} ms",
                    type = LogType.Success,
                    domain = result.domain,
                    rttMs = result.rttMs,
                    isProbe = true
                )
            }
            .toList()

        _uiState.update { state ->
            val serviceLogs = state.logs.filterNot(LogItem::isProbe).takeLast(MAX_SERVICE_LOGS)
            val probeLogs = if (items.isEmpty()) {
                state.logs.filter(LogItem::isProbe)
            } else {
                (state.logs.filter(LogItem::isProbe) + items)
                    .sortedWith(compareBy<LogItem> { it.rttMs ?: Int.MAX_VALUE }.thenBy { it.domain.orEmpty() })
                    .take(MAX_PROBE_LOGS)
            }

            state.copy(
                progress = processed,
                total = total,
                stats = stats,
                logs = serviceLogs + probeLogs,
                mode = if (state.mode == ScanMode.Stopping) ScanMode.Stopping else ScanMode.Running
            )
        }
    }

    private fun appendLog(message: String, type: LogType) {
        _uiState.update { state ->
            val serviceLogs = (state.logs.filterNot(LogItem::isProbe) + LogItem(
                id = logIds.incrementAndGet(),
                message = message,
                type = type
            )).takeLast(MAX_SERVICE_LOGS)
            val probeLogs = state.logs.filter(LogItem::isProbe).take(MAX_PROBE_LOGS)

            state.copy(logs = serviceLogs + probeLogs)
        }
    }

    private fun showError(message: String) {
        appendLog(message, LogType.Error)
        _uiState.update {
            it.copy(
                errorMessage = message,
                mode = ScanMode.Error,
                isStartEnabled = true,
                isStopEnabled = false
            )
        }
    }

    private companion object {
        const val MAX_CONCURRENCY = 32
        const val MAX_SERVICE_LOGS = 20
        const val MAX_PROBE_LOGS = 80
    }
}

@Immutable
data class SniCheckerUiState(
    val inputSource: String = "",
    val outputDir: String = "",
    val concurrency: String = "16",
    val timeoutSeconds: String = "5",
    val progress: Int = 0,
    val total: Int = 0,
    val stats: ScanStats = ScanStats(),
    val logs: List<LogItem> = emptyList(),
    val summary: ScanSummary? = null,
    val errorMessage: String? = null,
    val mode: ScanMode = ScanMode.Idle,
    val isStartEnabled: Boolean = true,
    val isStopEnabled: Boolean = false
) {
    val progressFraction: Float
        get() = if (total == 0) 0f else progress.toFloat() / total.toFloat()
}

@Immutable
data class LogItem(
    val id: Long,
    val message: String,
    val type: LogType,
    val domain: String? = null,
    val rttMs: Int? = null,
    val isProbe: Boolean = false
)

enum class ScanMode {
    Idle,
    Preparing,
    Running,
    Stopping,
    Completed,
    Stopped,
    Error
}
