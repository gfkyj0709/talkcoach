package com.talkcoach.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 의 추출 불가 AES-256-GCM 키로 작은 비밀값(DB 패스프레이즈, API 키)을 암·복호화한다.
 * Encrypts/decrypts small secrets (DB passphrase, API keys) with a non-exportable AES-256-GCM key in Android Keystore.
 *
 * 저장 포맷: "Base64(IV):Base64(ciphertext)"
 * Stored format: "Base64(IV):Base64(ciphertext)"
 *
 * @property alias Keystore key alias / Keystore 키 별칭
 */
class KeystoreCipher(private val alias: String) {

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding" // Cipher spec / 암호 방식
        const val PROVIDER = "AndroidKeyStore"    // Keystore provider / 키스토어 제공자
    }

    /**
     * 바이트 배열을 암호화한다.
     * Encrypts bytes.
     *
     * @param plain plaintext / 평문
     * @return "iv:ciphertext" in Base64 / Base64 "iv:암호문"
     */
    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) } // Random IV by Keystore / IV는 키스토어가 생성
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)                            // Encode IV / IV 인코딩
        val ct = Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)                // Encrypt / 암호화
        return "$iv:$ct"
    }

    /**
     * 암호문을 복호화한다.
     * Decrypts a stored value.
     *
     * @param stored "iv:ciphertext" / 저장된 "iv:암호문"
     * @return plaintext bytes / 평문 바이트
     */
    fun decrypt(stored: String): ByteArray {
        val (iv, ct) = stored.split(':', limit = 2)                                           // Split parts / 분리
        val cipher = Cipher.getInstance(TRANSFORM)                                            // Cipher / 암호기
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP))                              // Decrypt / 복호화
    }

    /**
     * 문자열 암호화 편의 함수.
     * Convenience: encrypt a string.
     *
     * @param plain plaintext string / 평문 문자열
     * @return stored form / 저장 형식
     */
    fun encryptString(plain: String): String = encrypt(plain.toByteArray(Charsets.UTF_8))

    /**
     * 문자열 복호화 편의 함수. 실패 시 null.
     * Convenience: decrypt to string; null on failure.
     *
     * @param stored stored form / 저장 형식
     * @return plaintext or null / 평문 또는 null
     */
    fun decryptString(stored: String): String? =
        runCatching { String(decrypt(stored), Charsets.UTF_8) }.getOrNull()

    /**
     * 키를 가져오거나 없으면 생성한다.
     * Gets the key or creates it.
     *
     * @return secret key / 비밀키
     */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }                           // Open keystore / 키스토어 열기
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }    // Existing / 기존 키
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)          // Generator / 생성기
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)                                   // GCM / GCM 모드
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)                  // No padding / 패딩 없음
                .setKeySize(256)                                                               // AES-256
                .build()
        )
        return gen.generateKey()                                                               // Create / 생성
    }
}
