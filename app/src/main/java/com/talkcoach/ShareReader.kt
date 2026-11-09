package com.talkcoach

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 공유 인텐트·파일 URI 에서 대화 텍스트를 꺼낸다. zip 이면 안의 첫 .txt 를 읽는다.
 * Extracts chat text from a share intent or file URI; for zip files, reads the first .txt inside.
 */
object ShareReader {

    private const val MAX_BYTES = 50 * 1024 * 1024 // 50 MB guard / 50MB 상한

    /**
     * 공유 인텐트에서 텍스트를 읽는다.
     * Reads text from a share intent.
     *
     * @param intent   incoming intent / 수신 인텐트
     * @param resolver content resolver / 컨텐트 리졸버
     * @return text or null / 텍스트 또는 null
     */
    fun fromIntent(intent: Intent, resolver: ContentResolver): String? {
        if (intent.action != Intent.ACTION_SEND) return null                               // Only SEND / SEND만 처리
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)               // API 33+ / API 33 이상
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)       // Older / 구버전
        uri?.let { u -> fromUri(u, resolver)?.let { return it } }                           // File first / 파일 우선
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }        // Plain text / 일반 텍스트
    }

    /**
     * 파일 URI 에서 텍스트를 읽는다.
     * Reads text from a file URI.
     *
     * @param uri      file uri / 파일 URI
     * @param resolver content resolver / 컨텐트 리졸버
     * @return text or null / 텍스트 또는 null
     */
    fun fromUri(uri: Uri, resolver: ContentResolver): String? = runCatching {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buf = input.readBytes()                                                     // Read all / 전체 읽기
            require(buf.size <= MAX_BYTES) { "파일이 너무 커요" }                              // Size guard / 크기 제한
            buf
        } ?: return null
        decode(if (isZip(bytes)) firstTxtInZip(bytes) ?: return null else bytes)            // Unzip if needed / 필요 시 압축 해제
    }.getOrNull()

    /**
     * zip 시그니처(PK\x03\x04) 확인.
     * Checks the zip signature.
     *
     * @param b bytes / 바이트
     * @return true if zip / zip이면 true
     */
    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()

    /**
     * zip 안 첫 .txt 파일 바이트.
     * Bytes of the first .txt entry in a zip.
     *
     * @param b zip bytes / zip 바이트
     * @return txt bytes or null / txt 바이트 또는 null
     */
    private fun firstTxtInZip(b: ByteArray): ByteArray? =
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            generateSequence { z.nextEntry }                                                // Iterate entries / 엔트리 순회
                .firstOrNull { !it.isDirectory && it.name.endsWith(".txt", ignoreCase = true) }
                ?.let { z.readBytes() }                                                     // Read that entry / 해당 엔트리 읽기
        }

    /**
     * UTF-8(BOM 포함) 우선, 깨지면 MS949 로 재시도.
     * Decodes UTF-8 (with BOM) first, falling back to MS949 when garbled.
     *
     * @param b bytes / 바이트
     * @return text / 텍스트
     */
    private fun decode(b: ByteArray): String {
        val utf8 = String(b, Charsets.UTF_8).removePrefix("﻿")                         // UTF-8 / UTF-8
        if ('�' !in utf8) return utf8                                                  // Clean / 정상
        return runCatching { String(b, charset("MS949")) }.getOrDefault(utf8)              // Korean Windows / 한글 윈도우 인코딩
    }
}
