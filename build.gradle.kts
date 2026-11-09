// Root build: plugin versions only (same set proven in salary-ticker) / 루트 빌드: 플러그인 버전만 선언 (salary-ticker에서 검증된 조합)
plugins {
    id("com.android.application") version "8.5.2" apply false             // Android Gradle Plugin / 안드로이드 그래들 플러그인
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false        // Kotlin Android / 코틀린 안드로이드
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false // Compose compiler / 컴포즈 컴파일러
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false      // KSP for Room / Room용 KSP
}
