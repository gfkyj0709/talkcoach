package com.talkcoach

import android.app.Application
import com.talkcoach.data.TalkRepository
import com.talkcoach.data.llm.LlmClient
import com.talkcoach.data.local.AppDatabase
import com.talkcoach.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 앱 전역 객체: 저장소 싱글턴과 공유로 들어온 대기 텍스트를 보관한다 (간단한 수동 DI).
 * Application object holding the repository singleton and pending shared text (simple manual DI).
 */
class TalkCoachApp : Application() {

    /** Repository, created lazily on first use / 첫 사용 시 생성되는 저장소 */
    val repo: TalkRepository by lazy {
        TalkRepository(AppDatabase.build(this), SettingsStore(this), LlmClient())
    }

    /** Text received from the share sheet, waiting for the import screen / 공유로 받아 가져오기 화면을 기다리는 텍스트 */
    val pendingImport = MutableStateFlow<String?>(null)
}
