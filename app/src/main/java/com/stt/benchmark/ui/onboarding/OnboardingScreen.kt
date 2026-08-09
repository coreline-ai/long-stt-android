package com.stt.benchmark.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stt.benchmark.ui.common.CopperThreadArtwork
import com.stt.benchmark.ui.theme.ArchiveCopper
import com.stt.benchmark.ui.theme.ArchiveFog
import com.stt.benchmark.ui.theme.ArchiveInk
import com.stt.benchmark.ui.theme.ArchivePaper

private data class OnboardingPage(
    val eyebrow: String,
    val title: String,
    val body: String,
    val archiveMode: Boolean,
)

private val pages = listOf(
    OnboardingPage(
        eyebrow = "긴 음성 기록",
        title = "말의 흐름을\n놓치지 않습니다",
        body = "가져온 긴 오디오는 기기 안에서 구간별로 전사합니다. 직접 녹음은 화면을 이동해도 이어지고, 확정된 파일만 최근 기록에 남습니다.",
        archiveMode = false,
    ),
    OnboardingPage(
        eyebrow = "기록 보관함",
        title = "음성이 읽을 수 있는\n기록으로 남습니다",
        body = "오디오, 전사 결과, 이후의 요약을 한 보관함에서 이어 봅니다. 외부 요약은 사용자가 선택할 때만 실행합니다.",
        archiveMode = true,
    ),
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pageIndex by remember { mutableIntStateOf(0) }
    val page = pages[pageIndex]
    BackHandler(enabled = pageIndex > 0) { pageIndex -= 1 }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(ArchiveInk, ArchiveInk, MaterialTheme.colorScheme.surface),
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "LONG STT / ARCHIVE",
                    style = MaterialTheme.typography.labelLarge,
                    color = ArchiveCopper,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${pageIndex + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = ArchiveFog,
                )
            }

            Spacer(Modifier.height(28.dp))
            CopperThreadArtwork(
                archiveMode = page.archiveMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(270.dp),
            )
            Spacer(Modifier.height(34.dp))
            Text(
                text = page.eyebrow.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = ArchiveCopper,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineLarge,
                color = ArchivePaper,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = page.body,
                style = MaterialTheme.typography.bodyLarge,
                color = ArchiveFog,
            )
            Spacer(Modifier.height(34.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pageIndex > 0) {
                    TextButton(onClick = { pageIndex -= 1 }) {
                        Text("이전")
                    }
                }
                Button(
                    onClick = {
                        if (pageIndex == pages.lastIndex) onComplete() else pageIndex += 1
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(if (pageIndex == pages.lastIndex) "녹음 화면으로" else "다음")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
