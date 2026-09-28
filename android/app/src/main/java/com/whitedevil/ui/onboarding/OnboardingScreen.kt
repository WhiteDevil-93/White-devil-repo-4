package com.whitedevil.ui.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.ui.theme.WdColors
import kotlinx.coroutines.launch

private data class OnboardingPage(val title: String, val body: String, val kicker: String)

private val pages = listOf(
    OnboardingPage(
        kicker = "WHITEDEVIL",
        title = "Venice on your phone",
        body = "Chat with the native Venice agent, run tools against your workspace, relay, and laptop — without opening a browser.",
    ),
    OnboardingPage(
        kicker = "ATTACH & SHARE",
        title = "Bring files into the chat",
        body = "Use Attach on the composer or Share from Photos, Files, or any app into WhiteDevil. Paste from the clipboard when you need snippets fast.",
    ),
    OnboardingPage(
        kicker = "SETUP",
        title = "Add your API key once",
        body = "Open the You tab → Settings to save your Venice key and relay credentials. Connection health checks help you verify everything.",
    ),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF141210), Color(0xFF0B0B0C), Color(0xFF080809)),
                ),
            )
            .padding(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                OnboardingPageContent(pages[page])
            }
            Spacer(Modifier.height(16.dp))
            RowDots(pagerState.currentPage, pages.size)
            Spacer(Modifier.height(20.dp))
            if (pagerState.currentPage == pages.lastIndex) {
                Button(
                    onClick = onFinished,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WdColors.accent, contentColor = Color(0xFF111111)),
                ) {
                    Text("Get started", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                Button(
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WdColors.accent, contentColor = Color(0xFF111111)),
                ) {
                    Text("Next", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                TextButton(onClick = onFinished, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Skip", color = WdColors.muted)
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(page.kicker, color = WdColors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text(page.title, color = WdColors.strong, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
        Spacer(Modifier.height(16.dp))
        Text(page.body, color = WdColors.muted, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Start)
    }
}

@Composable
private fun RowDots(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(28.dp)
                    .height(6.dp)
                    .background(
                        if (i == current) WdColors.accent else Color(0x33FFFFFF),
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}
