package com.talkcoach.data.llm

import com.talkcoach.core.prompt.LlmRequest
import com.talkcoach.data.settings.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * LLM 호출 실패.
 * LLM call failure.
 *
 * @param message user-facing Korean message / 사용자용 한글 메시지
 */
class LlmException(message: String) : Exception(message)

/**
 * Gemini / OpenAI REST 클라이언트. 외부 라이브러리 없이 HttpURLConnection + org.json 사용.
 * Gemini / OpenAI REST client using HttpURLConnection + org.json (no extra libraries).
 */
class LlmClient {

    /**
     * 요청을 보내고 모델의 텍스트(JSON 문자열)를 반환한다.
     * Sends the request and returns the model text (a JSON string).
     *
     * @param provider provider / 공급사
     * @param apiKey   API key / API 키
     * @param model    model id / 모델명
     * @param req      prompt payload / 프롬프트
     * @return raw model text / 모델 응답 텍스트
     */
    suspend fun complete(provider: Provider, apiKey: String, model: String, req: LlmRequest): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw LlmException("설정에서 ${provider.label} API 키를 먼저 입력해 주세요.")
            when (provider) {
                Provider.GEMINI -> gemini(apiKey, model, req)   // Gemini / 제미나이
                Provider.OPENAI -> openAi(apiKey, model, req)   // OpenAI / 오픈AI
            }
        }

    /**
     * Gemini generateContent 호출 (JSON 응답 모드).
     * Calls Gemini generateContent in JSON response mode.
     *
     * @param key   API key / API 키
     * @param model model id / 모델
     * @param req   prompt / 프롬프트
     * @return model text / 응답 텍스트
     */
    private fun gemini(key: String, model: String, req: LlmRequest): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent"                          // Endpoint / 엔드포인트
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", req.systemPrompt))))
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", req.userPrompt)))
            ))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")                                  // Force JSON / JSON 강제
                .put("temperature", 0.6))                                                     // Moderate creativity / 적당한 다양성
        val res = JSONObject(post(url, mapOf("x-goog-api-key" to key), body.toString()))      // Call / 호출
        val cand = res.optJSONArray("candidates")?.optJSONObject(0)                           // First candidate / 첫 후보
            ?: throw LlmException("Gemini 응답이 비어 있어요. (안전 필터 차단 가능성) ${res.optJSONObject("promptFeedback") ?: ""}")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")                      // Parts / 파트
            ?: throw LlmException("Gemini 응답 형식 오류: finishReason=${cand.optString("finishReason")}")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") } // Join text / 텍스트 합치기
    }

    /**
     * OpenAI Chat Completions 호출 (json_object 모드). temperature 는 일부 모델이 거부하므로 생략.
     * Calls OpenAI Chat Completions in json_object mode; temperature omitted since some models reject it.
     *
     * @param key   API key / API 키
     * @param model model id / 모델
     * @param req   prompt / 프롬프트
     * @return model text / 응답 텍스트
     */
    private fun openAi(key: String, model: String, req: LlmRequest): String {
        val body = JSONObject()
            .put("model", model)                                                              // Model / 모델
            .put("response_format", JSONObject().put("type", "json_object"))                  // Force JSON / JSON 강제
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", req.systemPrompt))     // System / 시스템
                .put(JSONObject().put("role", "user").put("content", req.userPrompt)))        // User / 사용자
        val res = JSONObject(post("https://api.openai.com/v1/chat/completions", mapOf("Authorization" to "Bearer $key"), body.toString()))
        return res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: throw LlmException("OpenAI 응답 형식 오류")
    }

    /**
     * JSON POST 공통 처리. 2xx 가 아니면 상태코드별 안내 메시지로 예외.
     * Shared JSON POST; non-2xx throws with a status-specific message.
     *
     * @param url     endpoint / 주소
     * @param headers extra headers / 추가 헤더
     * @param body    JSON body / 본문
     * @return response body / 응답 본문
     */
    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"                                                 // POST
            connectTimeout = 20_000                                                // 20s connect / 연결 20초
            readTimeout = 120_000                                                  // 120s read (long analyses) / 읽기 120초
            doOutput = true                                                        // Has body / 본문 있음
            setRequestProperty("Content-Type", "application/json; charset=utf-8")  // JSON
            headers.forEach { (k, v) -> setRequestProperty(k, v) }                 // Auth headers / 인증 헤더
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }   // Send / 전송
            val code = conn.responseCode                                           // Status / 상태코드
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val reason = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                    ?: text.take(300)                                              // Error detail / 오류 상세
                throw LlmException(
                    when (code) {
                        400 -> "요청 오류(400): 모델명을 확인해 주세요. $reason"
                        401, 403 -> "API 키가 올바르지 않거나 권한이 없어요($code). $reason"
                        404 -> "모델을 찾을 수 없어요(404). 설정의 모델명을 확인해 주세요. $reason"
                        429 -> "사용 한도를 초과했어요(429). 잠시 후 다시 시도해 주세요. $reason"
                        else -> "AI 서버 오류($code). $reason"
                    }
                )
            }
            return text
        } catch (e: LlmException) {
            throw e                                                                // Pass through / 그대로 전달
        } catch (e: Exception) {
            throw LlmException("네트워크 오류: ${e.message}")                        // Network failure / 네트워크 실패
        } finally {
            conn.disconnect()                                                      // Release / 연결 해제
        }
    }
}
