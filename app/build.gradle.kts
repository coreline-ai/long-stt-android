plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stt.benchmark"
    compileSdk = 34
    // NDK r28+: ELF LOAD 세그먼트 16KB 정렬 기본값
    // (r27 이하는 CMakeLists 의 -Wl,-z,max-page-size=16384 로 보완)
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.stt.benchmark"
        minSdk = 26
        targetSdk = 34
        // largeHeap 는 AndroidManifest.xml (android:largeHeap="true") 에서 설정
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // whisper.cpp용 ABI 필터 (ARM64 우선)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
                // Debug 빌드에서도 Release 최적화 강제 (whisper 성능 필수)
                arguments("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 16KB page size:
        // - useLegacyPackaging=false → .so 를 APK 에 비압축(Stored) 저장
        // - AGP 8.5.1+ 가 비압축 .so 를 16KB zip boundary 로 정렬
        // - AGP < 8.5.1 에서는 zip 정렬 실패 → Play / 16KB 기기 설치 문제
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ============================================================
    // whisper.cpp 연동 - 두 가지 옵션 중 택일
    // ============================================================

    // 옵션 A: JitPack pre-built (가장 간편, 빌드 불필요)
    // 출처: https://github.com/zufuliu/whisper.cpp-android 등 커뮤니티 포크
    // implementation("com.github.zufer-ui.whispercpp-android:whisper:main-SNAPSHOT")

    // 옵션 B: 로컬 AAR (직접 빌드한 경우 libs/ 폴더에 배치)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // 테스트
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")

    // Galaxy S25 AICore 빠른 요약 사전 검증용. 정식 기능 전까지 테스트 APK에만 포함한다.
    androidTestImplementation("com.google.mlkit:genai-summarization:1.0.0-beta1")
}
