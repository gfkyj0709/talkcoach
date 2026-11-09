// Plugin repositories / 플러그인 저장소
pluginManagement {
    repositories {
        google()             // Google Maven (AGP, AndroidX) / 구글 메이븐
        mavenCentral()       // Maven Central / 메이븐 센트럴
        gradlePluginPortal() // Gradle plugin portal / 그래들 플러그인 포털
    }
}

// Dependency repositories / 의존성 저장소
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) // Only repos declared here / 여기 선언한 저장소만 사용
    repositories {
        google()       // Google Maven / 구글 메이븐
        mavenCentral() // Maven Central (SQLCipher) / 메이븐 센트럴 (SQLCipher)
    }
}

rootProject.name = "TalkCoach" // Project name / 프로젝트 이름
include(":app")                // App module / 앱 모듈
