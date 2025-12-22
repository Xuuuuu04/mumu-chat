package mumu.xsy.mumuchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import mumu.xsy.mumuchat.ui.theme.MuMuChatTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MuMuChatTheme {
                // 用于追踪开屏是否完成的状态
                var isSplashDone by remember { mutableStateOf(false) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 使用 Crossfade 实现平滑的页面切换
                    Crossfade(
                        targetState = isSplashDone,
                        animationSpec = tween(1000),
                        label = "main_transition"
                    ) { done ->
                        if (!done) {
                            SplashScreen(onAnimationFinished = { isSplashDone = true })
                        } else {
                            ChatScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}
