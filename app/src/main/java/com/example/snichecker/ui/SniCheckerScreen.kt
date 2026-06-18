package com.example.snichecker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.snichecker.data.LogType
import com.example.snichecker.data.ScanStats
import com.example.snichecker.data.ScanSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SniCheckerScreen(
    state: SniCheckerUiState,
    onInputSourceChange: (String) -> Unit,
    onOutputDirChange: (String) -> Unit,
    onConcurrencyChange: (String) -> Unit,
    onTimeoutChange: (String) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onDismissError: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            onDismissError()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "header", contentType = "section") {
                Header(mode = state.mode)
            }

            item(key = "config", contentType = "section") {
                ConfigSection(
                    inputSource = state.inputSource,
                    outputDir = state.outputDir,
                    concurrency = state.concurrency,
                    timeoutSeconds = state.timeoutSeconds,
                    isEnabled = state.isStartEnabled,
                    onInputSourceChange = onInputSourceChange,
                    onOutputDirChange = onOutputDirChange,
                    onConcurrencyChange = onConcurrencyChange,
                    onTimeoutChange = onTimeoutChange
                )
            }

            item(key = "controls", contentType = "section") {
                ControlsSection(
                    isStartEnabled = state.isStartEnabled,
                    isStopEnabled = state.isStopEnabled,
                    onStartClick = onStartClick,
                    onStopClick = onStopClick
                )
            }

            item(key = "progress", contentType = "section") {
                ProgressSection(
                    mode = state.mode,
                    progress = state.progress,
                    total = state.total,
                    progressFraction = state.progressFraction,
                    stats = state.stats,
                    summary = state.summary
                )
            }

            item(key = "logs-title", contentType = "section") {
                LogsTitle(logCount = state.logs.size)
            }

            if (state.logs.isEmpty()) {
                item(key = "logs-empty", contentType = "empty") {
                    EmptyLogs(mode = state.mode)
                }
            } else {
                items(
                    items = state.logs,
                    key = { item -> item.id },
                    contentType = { item -> item.type }
                ) { item ->
                    LogRow(item)
                }
            }
        }
    }
}

@Composable
private fun Header(mode: ScanMode) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SNI Checker",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            AssistChip(
                onClick = {},
                label = { Text(modeTitle(mode)) }
            )
        }
        Text(
            text = "Download/SniChecker. Логи ниже отсортированы по пингу.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConfigSection(
    inputSource: String,
    outputDir: String,
    concurrency: String,
    timeoutSeconds: String,
    isEnabled: Boolean,
    onInputSourceChange: (String) -> Unit,
    onOutputDirChange: (String) -> Unit,
    onConcurrencyChange: (String) -> Unit,
    onTimeoutChange: (String) -> Unit
) {
    Section(title = "Параметры") {
        OutlinedTextField(
            value = inputSource,
            onValueChange = onInputSourceChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = isEnabled,
            singleLine = true,
            label = { Text("SNI список") },
            placeholder = { Text("/sdcard/Download/SniChecker/sni.txt") }
        )

        OutlinedTextField(
            value = outputDir,
            onValueChange = onOutputDirChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = isEnabled,
            singleLine = true,
            label = { Text("Папка результатов") },
            placeholder = { Text("/sdcard/Download/SniChecker/scan_out") }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = concurrency,
                onValueChange = onConcurrencyChange,
                modifier = Modifier.weight(1f),
                enabled = isEnabled,
                singleLine = true,
                label = { Text("Потоки") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = timeoutSeconds,
                onValueChange = onTimeoutChange,
                modifier = Modifier.weight(1f),
                enabled = isEnabled,
                singleLine = true,
                label = { Text("Таймаут") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}

@Composable
private fun ControlsSection(
    isStartEnabled: Boolean,
    isStopEnabled: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onStartClick,
            modifier = Modifier.weight(1f),
            enabled = isStartEnabled,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Старт")
        }

        OutlinedButton(
            onClick = onStopClick,
            modifier = Modifier.weight(1f),
            enabled = isStopEnabled,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Стоп")
        }
    }
}

@Composable
private fun ProgressSection(
    mode: ScanMode,
    progress: Int,
    total: Int,
    progressFraction: Float,
    stats: ScanStats,
    summary: ScanSummary?
) {
    Section(title = "Прогресс") {
        Text(
            text = when {
                total > 0 -> "$progress / $total"
                mode == ScanMode.Preparing -> "Подготовка"
                else -> "Ожидание запуска"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LinearProgressIndicator(
            progress = { if (mode == ScanMode.Preparing) 0f else progressFraction },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatChip(title = "OK", value = stats.ok.toString())
            StatChip(title = "BLOCKED", value = stats.blocked.toString())
            StatChip(title = "INC", value = stats.inconclusive.toString())
        }

        summary?.let {
            DividerLine()
            Text(
                text = if (it.wasStopped) "Остановлено вручную" else "Завершено",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            PathText(it.resultsJsonPath)
            PathText(it.workingTxtPath)
            PathText(it.sortedTxtPath)
        }
    }
}

@Composable
private fun LogsTitle(logCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Терминал SNI",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "$logCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LogRow(item: LogItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(logColor(item.type))
        )
        Text(
            text = item.message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyLogs(mode: ScanMode) {
    Text(
        text = if (mode == ScanMode.Preparing) "Подготовка..." else "Логов пока нет",
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        content()
    }
}

@Composable
private fun StatChip(title: String, value: String) {
    AssistChip(
        onClick = {},
        label = { Text("$title: $value") }
    )
}

@Composable
private fun PathText(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun DividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun logColor(type: LogType): Color = when (type) {
    LogType.Info -> MaterialTheme.colorScheme.primary
    LogType.Success -> MaterialTheme.colorScheme.tertiary
    LogType.Warning -> MaterialTheme.colorScheme.secondary
    LogType.Error -> MaterialTheme.colorScheme.error
}

private fun modeTitle(mode: ScanMode): String = when (mode) {
    ScanMode.Idle -> "Готов"
    ScanMode.Preparing -> "Подготовка"
    ScanMode.Running -> "Сканирование"
    ScanMode.Stopping -> "Остановка"
    ScanMode.Completed -> "Готово"
    ScanMode.Stopped -> "Остановлено"
    ScanMode.Error -> "Ошибка"
}
