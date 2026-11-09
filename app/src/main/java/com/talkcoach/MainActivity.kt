package com.talkcoach

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.talkcoach.ui.AppNav
import com.talkcoach.ui.theme.TalkCoachTheme

/**
 * 단일 액티비티. 공유 인텐트를 받으면 텍스트를 대기열에 넣고 가져오기 화면으로 보낸다.
 * Single activity; on a share intent it queues the text and routes to the import screen.
 */
class MainActivity : ComponentActivity() {

    /**
     * 화면 구성 + 최초 인텐트 처리.
     * Sets content and handles the launch intent.
     *
     * @param savedInstanceState saved state / 저장 상태
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)                       // Base / 기본 처리
        enableEdgeToEdge()                                       // Draw behind bars / 시스템 바 영역까지 그리기
        if (savedInstanceState == null) handleShare(intent)      // First launch only / 최초 실행 시만
        setContent { TalkCoachTheme { AppNav(app = application as TalkCoachApp) } }
    }

    /**
     * 실행 중 공유가 다시 들어온 경우 (singleTask).
     * Handles a share while already running (singleTask).
     *
     * @param intent new intent / 새 인텐트
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)                                // Base / 기본 처리
        setIntent(intent)                                        // Keep latest / 최신 인텐트 유지
        handleShare(intent)                                      // Queue text / 텍스트 대기열
    }

    /**
     * 공유 텍스트를 앱 대기열에 넣는다.
     * Puts shared text into the app queue.
     *
     * @param intent intent / 인텐트
     */
    private fun handleShare(intent: Intent?) {
        intent ?: return                                         // Nothing / 없음
        ShareReader.fromIntent(intent, contentResolver)?.let {   // Extract / 추출
            (application as TalkCoachApp).pendingImport.value = it
        }
    }
}
