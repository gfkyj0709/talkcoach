package com.talkcoach.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.talkcoach.core.security.KeystoreCipher
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

/**
 * SQLCipher 로 파일 전체를 암호화한 Room DB.
 * Room database whose whole file is encrypted with SQLCipher.
 */
@Database(
    entities = [
        ContactProfileEntity::class, ChatImportEntity::class, ChatMessageEntity::class,
        AnalysisSessionEntity::class, ContextMemoryEntity::class, ReplySuggestionEntity::class,
        UserStyleProfileEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    /** @return contact DAO / 상대 DAO */
    abstract fun contactDao(): ContactDao

    /** @return chat DAO / 대화 DAO */
    abstract fun chatDao(): ChatDao

    /** @return analysis DAO / 분석 DAO */
    abstract fun analysisDao(): AnalysisDao

    companion object {
        private const val PREFS = "db_key_store" // Prefs holding the wrapped passphrase / 감싼 패스프레이즈 보관
        private const val PREF_KEY = "wrapped"   // Pref key / 키 이름

        /**
         * 암호화 DB 를 연다. 패스프레이즈는 Keystore 로 감싸 보관한다.
         * Opens the encrypted DB; the passphrase is stored wrapped by Keystore.
         *
         * @param context application context / 앱 컨텍스트
         * @return database / DB
         */
        fun build(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")                                        // Native lib / 네이티브 라이브러리
            val passphrase = passphrase(context)                                   // DB key / DB 키
            return Room.databaseBuilder(context, AppDatabase::class.java, "talkcoach.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))           // SQLCipher helper / SQLCipher 헬퍼
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.query("PRAGMA secure_delete = ON").close()              // Zero-fill deleted pages / 삭제 페이지 덮어쓰기
                    }
                })
                .build()
        }

        /**
         * 패스프레이즈를 복호화하거나 새로 만든다.
         * Decrypts the passphrase or creates a new one.
         *
         * @param context context / 컨텍스트
         * @return 32-byte passphrase / 32바이트 패스프레이즈
         */
        private fun passphrase(context: Context): ByteArray {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)  // Private prefs / 앱 전용 prefs
            val cipher = KeystoreCipher("talkcoach_db_kek")                       // Key-encryption key / 키 암호화 키
            prefs.getString(PREF_KEY, null)?.let { return cipher.decrypt(it) }    // Existing / 기존 키
            val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }        // New random / 신규 난수
            prefs.edit().putString(PREF_KEY, cipher.encrypt(pass)).commit()       // Persist synchronously / 동기 저장
            return pass
        }
    }
}
