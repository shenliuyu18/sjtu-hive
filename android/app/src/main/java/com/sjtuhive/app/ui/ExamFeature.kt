package com.sjtuhive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.ExamApi
import com.sjtuhive.app.data.ExamItem
import java.time.format.DateTimeFormatter

/** 考试查询（教学网考试信息），对应桌面端「考试与日程」里的考试部分。 */
@Composable
fun ExamScreen(context: android.content.Context, onClose: () -> Unit) {
    val api = remember { ExamApi(context) }
    var items by remember { mutableStateOf<List<ExamItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        runCatching { api.upcoming() }
            .onSuccess { items = it }
            .onFailure { error = it.message ?: "读取考试信息失败" }
        loading = false
    }

    androidx.activity.compose.BackHandler { onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("考试", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { reload++ }) { Text("刷新") }
        }

        when {
            loading -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            error != null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                TextButton(onClick = { reload++ }) { Text("重试") }
            }
            items.isEmpty() -> Text(
                "没有查到待考试的安排。考试信息来自教学网，临近考试才会出现。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items) { exam -> ExamCard(exam) }
            }
        }
    }
}

@Composable
private fun ExamCard(exam: ExamItem) {
    val formatter = remember { DateTimeFormatter.ofPattern("M月d日 EEE") }
    val timeText = runCatching {
        exam.startAt?.let { "${it.format(formatter)} ${exam.timeText.ifBlank { it.format(DateTimeFormatter.ofPattern("HH:mm")) }}" }
    }.getOrNull().orEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            exam.courseName.ifBlank { exam.examName },
            style = MaterialTheme.typography.titleSmall,
        )
        if (exam.examName.isNotBlank() && exam.examName != exam.courseName) {
            Text(
                exam.examName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val meta = buildList {
            if (timeText.isNotBlank()) add(timeText)
            if (exam.location.isNotBlank()) add(exam.location)
            if (exam.seat.isNotBlank()) add("座位 ${exam.seat}")
            if (exam.campus.isNotBlank()) add(exam.campus)
        }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall)
        }
        if (exam.form.isNotBlank()) {
            Text(
                "形式：${exam.form}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
