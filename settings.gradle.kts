pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // whisper.cpp pre-built AAR 배포용 (커뮤니티 mirror)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AndroidSttBenchmark"
include(":app")
