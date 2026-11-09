// App module plugins / 앱 모듈 플러그인
plugins {
    id("com.android.application")             // Android app / 안드로이드 앱
    id("org.jetbrains.kotlin.android")        // Kotlin / 코틀린
    id("org.jetbrains.kotlin.plugin.compose") // Compose compiler / 컴포즈 컴파일러
    id("com.google.devtools.ksp")             // Annotation processing for Room / Room 어노테이션 처리
}

android {
    namespace = "com.talkcoach" // Package namespace / 패키지 네임스페이스
    compileSdk = 34             // Compile against API 34 / API 34 기준 컴파일

    defaultConfig {
        applicationId = "com.talkcoach" // App ID / 앱 ID
        minSdk = 26                     // Android 8.0+ / 안드로이드 8.0 이상
        targetSdk = 34                  // Target API / 타겟 API
        versionCode = 1                 // Internal version / 내부 버전
        versionName = "0.1.0"           // Display version / 표시 버전
    }

    // Fixed key so updates install over the previous build / 고정 키 → 업데이트 시 덮어쓰기 설치 가능
    signingConfigs {
        create("fixed") {
            storeFile = file("talkcoach.jks")  // Keystore in repo (private repo, personal use) / 저장소 내 키스토어 (개인용 비공개 저장소)
            storePassword = "talkcoach123"     // Store password / 스토어 비밀번호
            keyAlias = "talkcoach"             // Key alias / 키 별칭
            keyPassword = "talkcoach123"       // Key password / 키 비밀번호
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false                           // No shrinking for MVP / MVP라 난독화 안 함
            signingConfig = signingConfigs.getByName("fixed") // Sign with fixed key / 고정 키로 서명
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed") // Same key for debug / 디버그도 같은 키
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 // Java 17 source / 자바 17 소스
        targetCompatibility = JavaVersion.VERSION_17 // Java 17 bytecode / 자바 17 바이트코드
    }

    kotlinOptions {
        jvmTarget = "17" // Kotlin JVM target / 코틀린 JVM 타겟
    }

    buildFeatures {
        compose = true // Enable Compose / 컴포즈 활성화
    }
}

// Export Room schema for future migrations / 향후 마이그레이션 대비 Room 스키마 내보내기
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00") // Compose version set / 컴포즈 버전 묶음
    implementation(composeBom)                                            // Apply BOM / BOM 적용

    implementation("androidx.core:core-ktx:1.13.1")                        // Kotlin extensions / 코틀린 확장
    implementation("androidx.activity:activity-compose:1.9.2")             // setContent {} / 컴포즈 액티비티
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6") // viewModel() / 뷰모델
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")   // collectAsStateWithLifecycle / 생명주기 수집
    implementation("androidx.navigation:navigation-compose:2.8.0")         // Screen navigation / 화면 이동
    implementation("androidx.compose.ui:ui")                               // Compose UI / 컴포즈 UI
    implementation("androidx.compose.material3:material3")                 // Material 3 / 머티리얼3
    implementation("androidx.compose.material:material-icons-core")        // Basic icons / 기본 아이콘

    implementation("androidx.room:room-runtime:2.6.1")                     // Room runtime / Room 런타임
    implementation("androidx.room:room-ktx:2.6.1")                         // Coroutines + Flow / 코루틴·Flow 지원
    ksp("androidx.room:room-compiler:2.6.1")                               // Room code generator / Room 코드 생성기
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")              // Encrypted SQLite / 암호화 SQLite
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")                     // SupportSQLite API / SupportSQLite API

    testImplementation("junit:junit:4.13.2")                               // Unit tests / 단위테스트
}
