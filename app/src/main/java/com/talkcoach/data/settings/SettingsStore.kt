package com.talkcoach.data.settings

import android.content.Context
import com.talkcoach.core.security.KeystoreCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LLM 공급사.
 * LLM provider.
 *
 * @property label UI label / 표시명
 * @property defaultModel default model id (editable) / 기본 모델 (수정 가능)
 */
enum class Provider(val label: String, val defaultModel: String) {
    GEMINI("Gemini", "gemini-2.5-flash"),
    OPENAI("OpenAI", "gpt-4.1-mini")
}

/**
 * 설정 스냅샷 (UI 바인딩용).
 * Settings snapshot for UI binding.
 *
 * @property provider     selected provider / 선택 공급사
 * @property geminiKey    decrypted Gemini key ("" if none) / Gemini 키
 * @property openAiKey    decrypted OpenAI key ("" if none) / OpenAI 키
 * @property geminiModel  Gemini model id / Gemini 모델
 * @property openAiModel  OpenAI model id / OpenAI 모델
 * @property labeledMask  true → "[전화번호***]", false → "***" / 마스킹 표기
 */
data class Settings(
    val provider: Provider,
    val geminiKey: String,
    val openAiKey: String,
    val geminiModel: String,
    val openAiModel: String,
    val labeledMask: Boolean
) {
    /** Key of the selected provider / 선택 공급사의 키 */
    val activeKey: String get() = if (provider == Provider.GEMINI) geminiKey else openAiKey

    /** Model of the selected provider / 선택 공급사의 모델 */
    val activeModel: String get() = if (provider == Provider.GEMINI) geminiModel else openAiModel
}

/**
 * 설정 저장소. API 키는 Keystore 로 암호화해 SharedPreferences 에 저장한다.
 * Settings store; API keys are Keystore-encrypted in SharedPreferences.
 *
 * @param context application context / 앱 컨텍스트
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE) // Prefs file / 설정 파일
    private val cipher = KeystoreCipher("talkcoach_api_kek")                          // API key cipher / API 키 암호기
    private val state = MutableStateFlow(load())                                      // Current snapshot / 현재 값

    /** Observable settings / 관찰 가능한 설정 */
    val settings: StateFlow<Settings> = state.asStateFlow()

    /**
     * 설정을 저장하고 스냅샷을 갱신한다.
     * Saves settings and refreshes the snapshot.
     *
     * @param s new settings / 새 설정
     */
    fun save(s: Settings) {
        prefs.edit()
            .putString("provider", s.provider.name)                                              // Provider / 공급사
            .putString("gemini_key", if (s.geminiKey.isBlank()) null else cipher.encryptString(s.geminiKey.trim())) // Encrypted / 암호화
            .putString("openai_key", if (s.openAiKey.isBlank()) null else cipher.encryptString(s.openAiKey.trim())) // Encrypted / 암호화
            .putString("gemini_model", s.geminiModel.trim().ifEmpty { Provider.GEMINI.defaultModel }) // Model / 모델
            .putString("openai_model", s.openAiModel.trim().ifEmpty { Provider.OPENAI.defaultModel }) // Model / 모델
            .putBoolean("labeled_mask", s.labeledMask)                                           // Mask style / 마스킹 표기
            .apply()
        state.value = load()                                                                     // Refresh / 갱신
    }

    /**
     * SharedPreferences 에서 읽는다.
     * Reads from SharedPreferences.
     *
     * @return snapshot / 스냅샷
     */
    private fun load(): Settings = Settings(
        provider = runCatching { Provider.valueOf(prefs.getString("provider", null) ?: "GEMINI") }.getOrDefault(Provider.GEMINI),
        geminiKey = prefs.getString("gemini_key", null)?.let { cipher.decryptString(it) } ?: "",
        openAiKey = prefs.getString("openai_key", null)?.let { cipher.decryptString(it) } ?: "",
        geminiModel = prefs.getString("gemini_model", null) ?: Provider.GEMINI.defaultModel,
        openAiModel = prefs.getString("openai_model", null) ?: Provider.OPENAI.defaultModel,
        labeledMask = prefs.getBoolean("labeled_mask", true)
    )
}
