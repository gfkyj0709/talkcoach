package com.talkcoach.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 상대 프로필 DAO.
 * Contact profile DAO.
 */
@Dao
interface ContactDao {

    /**
     * 프로필 목록을 최근 활동순으로 관찰한다.
     * Observes profiles ordered by recent activity.
     *
     * @return flow of profiles / 프로필 목록 Flow
     */
    @Query("SELECT * FROM contact_profile ORDER BY COALESCE(last_analyzed_at, updated_at) DESC")
    fun observeAll(): Flow<List<ContactProfileEntity>>

    /**
     * 프로필 1건을 관찰한다.
     * Observes one profile.
     *
     * @param id contact id / 상대 id
     * @return flow of profile or null / 프로필 Flow
     */
    @Query("SELECT * FROM contact_profile WHERE id = :id")
    fun observe(id: Long): Flow<ContactProfileEntity?>

    /**
     * 프로필 1건 조회.
     * Gets one profile.
     *
     * @param id contact id / 상대 id
     * @return profile or null / 프로필 또는 null
     */
    @Query("SELECT * FROM contact_profile WHERE id = :id")
    suspend fun get(id: Long): ContactProfileEntity?

    /**
     * 전체 프로필 조회 (가져오기 대상 선택용).
     * All profiles (for import target selection).
     *
     * @return profiles / 프로필 목록
     */
    @Query("SELECT * FROM contact_profile ORDER BY updated_at DESC")
    suspend fun all(): List<ContactProfileEntity>

    /**
     * 프로필 저장.
     * Inserts a profile.
     *
     * @param e entity / 엔티티
     * @return new id / 생성 id
     */
    @Insert
    suspend fun insert(e: ContactProfileEntity): Long

    /**
     * 프로필 수정.
     * Updates a profile.
     *
     * @param e entity / 엔티티
     * @return affected rows / 영향 행 수
     */
    @Update
    suspend fun update(e: ContactProfileEntity): Int

    /**
     * 프로필과 모든 하위 데이터(CASCADE)를 삭제한다.
     * Deletes a profile and all child rows via CASCADE.
     *
     * @param id contact id / 상대 id
     * @return affected rows / 영향 행 수
     */
    @Query("DELETE FROM contact_profile WHERE id = :id")
    suspend fun deleteWithAllData(id: Long): Int

    /**
     * 전체 데이터 삭제.
     * Deletes every profile (and all data via CASCADE).
     *
     * @return affected rows / 영향 행 수
     */
    @Query("DELETE FROM contact_profile")
    suspend fun deleteAll(): Int
}

/**
 * 대화 원문 DAO.
 * Chat message DAO.
 */
@Dao
interface ChatDao {

    /**
     * 가져오기 이력 저장. 동일 파일이면 -1.
     * Inserts import history; -1 when the same file already exists.
     *
     * @param e entity / 엔티티
     * @return new id or -1 / 생성 id 또는 -1
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImport(e: ChatImportEntity): Long

    /**
     * 메시지 일괄 저장. 중복(dedup_key)은 무시.
     * Bulk insert; duplicates (dedup_key) are ignored.
     *
     * @param list entities / 엔티티 목록
     * @return row ids (-1 for skipped) / 행 id (건너뛰면 -1)
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessages(list: List<ChatMessageEntity>): List<Long>

    /**
     * 최근 N개 메시지를 시간순으로 반환.
     * Most recent N messages in chronological order.
     *
     * @param contactId contact id / 상대 id
     * @param limit     N / 개수
     * @return messages / 메시지
     */
    @Query(
        """SELECT * FROM (SELECT * FROM chat_message WHERE contact_id = :contactId
           ORDER BY sent_at DESC, id DESC LIMIT :limit) ORDER BY sent_at ASC, id ASC"""
    )
    suspend fun recent(contactId: Long, limit: Int): List<ChatMessageEntity>

    /**
     * 기간 메시지 (from 포함, to 포함).
     * Messages within [from, to] inclusive.
     *
     * @param contactId contact id / 상대 id
     * @param from      start millis / 시작
     * @param to        end millis / 끝
     * @return messages / 메시지
     */
    @Query("SELECT * FROM chat_message WHERE contact_id = :contactId AND sent_at >= :from AND sent_at <= :to ORDER BY sent_at, id")
    suspend fun range(contactId: Long, from: Long, to: Long): List<ChatMessageEntity>

    /**
     * 말투 분석용 내 텍스트 메시지 최근 500건 (최신순).
     * The user's latest 500 text messages (newest first) for style analysis.
     *
     * @param contactId contact id / 상대 id
     * @return messages / 메시지
     */
    @Query("SELECT * FROM chat_message WHERE contact_id = :contactId AND speaker_role = 'ME' AND msg_type = 'TEXT' ORDER BY sent_at DESC LIMIT 500")
    suspend fun myRecentTexts(contactId: Long): List<ChatMessageEntity>

    /**
     * 메시지 수를 관찰.
     * Observes the message count.
     *
     * @param contactId contact id / 상대 id
     * @return count flow / 개수 Flow
     */
    @Query("SELECT COUNT(*) FROM chat_message WHERE contact_id = :contactId")
    fun observeCount(contactId: Long): Flow<Int>

    /**
     * 마지막 메시지 시각.
     * Latest message time.
     *
     * @param contactId contact id / 상대 id
     * @return millis or null / 시각 또는 null
     */
    @Query("SELECT MAX(sent_at) FROM chat_message WHERE contact_id = :contactId")
    suspend fun lastSentAt(contactId: Long): Long?

    /**
     * 프로필은 두고 대화 원문만 삭제 (메시지는 import CASCADE).
     * Deletes raw conversation only, keeping the profile (messages via import CASCADE).
     *
     * @param contactId contact id / 상대 id
     * @return affected rows / 영향 행 수
     */
    @Query("DELETE FROM chat_import WHERE contact_id = :contactId")
    suspend fun deleteConversation(contactId: Long): Int
}

/**
 * 분석·맥락·추천 DAO.
 * Analysis, memory and suggestion DAO.
 */
@Dao
abstract class AnalysisDao {

    /**
     * 프롬프트 주입용 활성 맥락 (고정 → 중요도 → 최신).
     * Active memories for prompt injection (pinned → importance → recency).
     *
     * @param contactId contact id / 상대 id
     * @param now       current millis / 현재 시각
     * @param limit     max rows / 최대 건수
     * @return memories / 맥락
     */
    @Query(
        """SELECT * FROM context_memory
           WHERE contact_id = :contactId AND is_active = 1
             AND (expires_at IS NULL OR expires_at > :now OR is_pinned = 1)
           ORDER BY is_pinned DESC, importance DESC, updated_at DESC LIMIT :limit"""
    )
    abstract suspend fun activeMemories(contactId: Long, now: Long, limit: Int): List<ContextMemoryEntity>

    /**
     * 프로필 화면용 전체 맥락 관찰.
     * Observes all memories for the profile screen.
     *
     * @param contactId contact id / 상대 id
     * @return memories flow / 맥락 Flow
     */
    @Query("SELECT * FROM context_memory WHERE contact_id = :contactId ORDER BY is_active DESC, is_pinned DESC, importance DESC, updated_at DESC")
    abstract fun observeMemories(contactId: Long): Flow<List<ContextMemoryEntity>>

    /**
     * 맥락 고정 여부 변경.
     * Sets the pinned flag.
     *
     * @param id     memory id / 맥락 id
     * @param pinned pinned / 고정 여부
     * @param now    update time / 갱신 시각
     */
    @Query("UPDATE context_memory SET is_pinned = :pinned, updated_at = :now WHERE id = :id")
    abstract suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    /**
     * 맥락 활성 여부 변경.
     * Sets the active flag.
     *
     * @param id     memory id / 맥락 id
     * @param active active / 활성 여부
     * @param now    update time / 갱신 시각
     */
    @Query("UPDATE context_memory SET is_active = :active, updated_at = :now WHERE id = :id")
    abstract suspend fun setActive(id: Long, active: Boolean, now: Long)

    /**
     * 맥락 삭제.
     * Deletes a memory.
     *
     * @param id memory id / 맥락 id
     */
    @Query("DELETE FROM context_memory WHERE id = :id")
    abstract suspend fun deleteMemory(id: Long)

    /**
     * 사용자가 직접 맥락 추가.
     * Inserts a user-written memory.
     *
     * @param e entity / 엔티티
     * @return id / id
     */
    @Insert
    abstract suspend fun insertMemory(e: ContextMemoryEntity): Long

    /**
     * 세션 저장.
     * Inserts a session.
     *
     * @param e entity / 엔티티
     * @return id / id
     */
    @Insert
    abstract suspend fun insertSession(e: AnalysisSessionEntity): Long

    /**
     * 세션 목록 관찰 (최신순).
     * Observes sessions, newest first.
     *
     * @param contactId contact id / 상대 id
     * @return sessions flow / 세션 Flow
     */
    @Query("SELECT * FROM analysis_session WHERE contact_id = :contactId ORDER BY created_at DESC")
    abstract fun observeSessions(contactId: Long): Flow<List<AnalysisSessionEntity>>

    /**
     * 세션 1건.
     * One session.
     *
     * @param id session id / 세션 id
     * @return session or null / 세션 또는 null
     */
    @Query("SELECT * FROM analysis_session WHERE id = :id")
    abstract suspend fun session(id: Long): AnalysisSessionEntity?

    /**
     * 세션의 추천 답장.
     * Suggestions of a session.
     *
     * @param sessionId session id / 세션 id
     * @return suggestions / 추천 답장
     */
    @Query("SELECT * FROM reply_suggestion WHERE session_id = :sessionId ORDER BY id")
    abstract suspend fun suggestions(sessionId: Long): List<ReplySuggestionEntity>

    /**
     * 추천 답장 복사/피드백 기록.
     * Records copy / feedback on a suggestion.
     *
     * @param id       suggestion id / 추천 id
     * @param copied   copied flag / 복사 여부
     * @param feedback GOOD, BAD or null / 피드백
     */
    @Query("UPDATE reply_suggestion SET is_copied = :copied, feedback = :feedback WHERE id = :id")
    abstract suspend fun markSuggestion(id: Long, copied: Boolean, feedback: String?)

    /**
     * 추천 답장 저장.
     * Inserts suggestions.
     *
     * @param list entities / 엔티티
     * @return ids / id
     */
    @Insert
    abstract suspend fun insertSuggestions(list: List<ReplySuggestionEntity>): List<Long>

    /**
     * 맥락 일괄 저장.
     * Inserts memories.
     *
     * @param list entities / 엔티티
     * @return ids / id
     */
    @Insert
    abstract suspend fun insertMemories(list: List<ContextMemoryEntity>): List<Long>

    /**
     * 모델이 해결로 판단한 맥락 비활성화 (해당 상대 + 미고정만).
     * Deactivates memories the model marked resolved (this contact, unpinned only).
     *
     * @param contactId contact guard / 상대 가드
     * @param ids       memory ids / 맥락 id
     * @param now       update time / 갱신 시각
     * @return affected rows / 영향 행 수
     */
    @Query("UPDATE context_memory SET is_active = 0, updated_at = :now WHERE contact_id = :contactId AND id IN (:ids) AND is_pinned = 0")
    abstract suspend fun deactivateMemories(contactId: Long, ids: List<Long>, now: Long): Int

    /**
     * 말투 프로필 upsert.
     * Upserts a style profile.
     *
     * @param e entity / 엔티티
     */
    @Upsert
    abstract suspend fun upsertStyle(e: UserStyleProfileEntity)

    /**
     * 말투 프로필 조회.
     * Gets the style profile.
     *
     * @param contactId contact id / 상대 id
     * @return profile or null / 프로필 또는 null
     */
    @Query("SELECT * FROM user_style_profile WHERE contact_id = :contactId")
    abstract suspend fun style(contactId: Long): UserStyleProfileEntity?

    /**
     * 말투 프로필 관찰.
     * Observes the style profile.
     *
     * @param contactId contact id / 상대 id
     * @return flow / Flow
     */
    @Query("SELECT * FROM user_style_profile WHERE contact_id = :contactId")
    abstract fun observeStyle(contactId: Long): Flow<UserStyleProfileEntity?>

    /**
     * 분석 결과를 원자적으로 저장: 세션 → 추천 → 맥락 비활성화 → 신규 맥락.
     * Atomically saves an analysis: session → suggestions → deactivations → new memories.
     *
     * @param session       session / 세션
     * @param suggestions   suggestions (sessionId filled here) / 추천 답장
     * @param newMemories   new memories (sessionId filled here) / 신규 맥락
     * @param deactivateIds ids to deactivate / 비활성화 id
     * @return session id / 세션 id
     */
    @Transaction
    open suspend fun saveAnalysis(
        session: AnalysisSessionEntity,
        suggestions: List<ReplySuggestionEntity>,
        newMemories: List<ContextMemoryEntity>,
        deactivateIds: List<Long>
    ): Long {
        val sid = insertSession(session)                                          // Session / 세션
        insertSuggestions(suggestions.map { it.copy(sessionId = sid) })           // Link suggestions / 추천 연결
        if (deactivateIds.isNotEmpty())                                           // Old memories / 기존 맥락
            deactivateMemories(session.contactId, deactivateIds, session.createdAt)
        insertMemories(newMemories.map { it.copy(sourceSessionId = sid) })        // New memories / 신규 맥락
        return sid
    }
}
