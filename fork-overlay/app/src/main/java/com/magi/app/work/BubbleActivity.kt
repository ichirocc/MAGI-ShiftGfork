package com.magi.app.work

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
// isLaunchedFromBubble: Activity API 31+ (minSdk 36)
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 会話バブル展開ビュー。
 * OptimizationRepository の進捗／結果を購読する表示専用画面。
 */
class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BubbleContent(
                        fromBubble = isLaunchedFromBubble,
                        onDismissBubble = {
                            BubbleSupport.clear(this)
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun BubbleContent(
    fromBubble: Boolean,
    onDismissBubble: () -> Unit,
) {
    val running by OptimizationRepository.running.collectAsStateWithLifecycle()
    val progress by OptimizationRepository.progress.collectAsStateWithLifecycle()
    val result by OptimizationRepository.result.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("勤務表の最適化", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (fromBubble) {
            Text("バブル表示中", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val r = result
        val p = progress
        when {
            r != null && !running -> {
                Text(
                    if (r.report.hard == 0) "配布できます（必須違反 0）"
                    else "未解決 ${r.report.hard} 件",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("合計違反 ${r.report.total}")
                TextButton(onClick = onDismissBubble) { Text("閉じる") }
            }
            p != null -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("計算中 ・ 経過 ${fmtElapsed(p.elapsedMs)}")
                Text("違反 ${p.total}（必須 ${p.hard}）")
            }
            running -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("計算を開始しています…")
            }
            else -> Text("待機中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmtElapsed(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
