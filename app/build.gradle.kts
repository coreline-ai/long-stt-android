import java.security.MessageDigest
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val assetManifestProvider = providers.gradleProperty("assetManifest")
    .map { rootProject.file(it) }
    .orElse(rootProject.file("config/asset-manifest.tsv"))
val fontNotice = rootProject.file("config/font-notice.md")

val verifyAssetProvenance = tasks.register("verifyAssetProvenance") {
    group = "verification"
    description = "Verifies packaged visual asset paths, sizes, SHA-256 values, and font notice."
    inputs.file(assetManifestProvider)
    inputs.file(fontNotice)
    inputs.files(
        fileTree("src/main/res") {
            include("drawable*/**", "mipmap*/**", "font/**")
            exclude("**/.gitkeep")
        },
    )

    doLast {
        val manifest = assetManifestProvider.get()
        check(manifest.isFile) { "Asset manifest is missing: ${manifest.absolutePath}" }
        check(fontNotice.isFile) { "Font notice is missing: ${fontNotice.absolutePath}" }
        val fontNoticeText = fontNotice.readText()
        check(fontNoticeText.contains("does not package")) {
            "Font notice must state the bundled-font policy"
        }

        data class AssetRecord(val path: String, val bytes: Long, val sha256: String, val source: String)
        val records = manifest.readLines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .mapIndexed { index, line ->
                val columns = line.split('\t')
                check(columns.size == 4) { "${manifest.name}:${index + 1} must contain 4 tab-separated columns" }
                val bytes = columns[1].toLongOrNull()
                    ?: error("${manifest.name}:${index + 1} has an invalid byte count")
                AssetRecord(columns[0], bytes, columns[2], columns[3])
            }
        check(records.isNotEmpty()) { "Asset manifest must not be empty" }
        check(records.map(AssetRecord::path).distinct().size == records.size) { "Asset manifest contains duplicate paths" }

        val manifestPaths = records.map(AssetRecord::path).toSet()
        val packagedAssets = fileTree("src/main/res") {
            include("drawable*/**", "mipmap*/**", "font/**")
            exclude("**/.gitkeep")
        }.files.map { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }.toSet()
        check(manifestPaths == packagedAssets) {
            val missing = packagedAssets - manifestPaths
            val unexpected = manifestPaths - packagedAssets
            "Asset manifest mismatch. missing=$missing unexpected=$unexpected"
        }

        records.forEach { record ->
            val file = rootProject.file(record.path)
            check(file.isFile) { "Manifest asset is missing: ${record.path}" }
            check(record.bytes == file.length()) {
                "Asset size mismatch for ${record.path}: expected=${record.bytes}, actual=${file.length()}"
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(record.sha256.matches(Regex("[0-9a-f]{64}"))) {
                "Invalid SHA-256 in manifest for ${record.path}"
            }
            check(record.sha256 == hash) { "Asset SHA-256 mismatch for ${record.path}" }
            check(record.source.isNotBlank()) { "Asset source/license note is required for ${record.path}" }
        }

        val bundledFonts = fileTree("src/main/res/font") { include("**/*") }.files
        check(bundledFonts.isEmpty() || fontNoticeText.contains("future bundled font")) {
            "Bundled fonts require an explicit redistribution notice"
        }
    }
}

val verifyReleaseSurface = tasks.register("verifyReleaseSurface") {
    group = "verification"
    description = "Verifies that the Release manifest and source keep Debug automation and sensitive diagnostics out."
    dependsOn("processReleaseMainManifest")
    inputs.file("src/main/AndroidManifest.xml")
    inputs.files(fileTree("src/main/java") { include("**/*.kt") })

    doLast {
        val mergedManifest = fileTree(layout.buildDirectory) {
            include("intermediates/**/release/**/AndroidManifest.xml")
        }.files.firstOrNull { file ->
            file.invariantSeparatorsPath.contains("merged_manifest") ||
                file.invariantSeparatorsPath.contains("merged_manifests")
        } ?: error("Release merged manifest was not produced")
        val manifestText = mergedManifest.readText()
        check("android.permission.RECORD_AUDIO" in manifestText) { "Release is missing microphone permission" }
        check("android.permission.FOREGROUND_SERVICE_MICROPHONE" in manifestText) {
            "Release is missing microphone foreground-service permission"
        }
        check("android:required=\"false\"" in manifestText) { "Microphone feature must stay optional" }
        check("android:foregroundServiceType=\"microphone\"" in manifestText) {
            "Recorder foreground-service type is missing"
        }
        check("android:foregroundServiceType=\"dataSync\"" in manifestText) {
            "Transcription foreground-service type is missing"
        }
        check(
                "SummaryProbeActivity" !in manifestText &&
                "DebugSttAuditActivity" !in manifestText &&
                "DebugRecordingAuditActivity" !in manifestText &&
                "DebugLongSingleFileAuditActivity" !in manifestText &&
                "DebugTranscriptChatAuditActivity" !in manifestText &&
                "com.google.android.aicore" !in manifestText,
        ) {
            "Release manifest must not expose Debug-only audit/probe activities"
        }
        // AndroidX profileinstaller legitimately contributes its own receiver. The app's adb
        // automation is identified by this action and must never appear in Release.
        check("com.stt.benchmark.RUN_STT" !in manifestText && "DebugTranscription" !in manifestText) {
            "Release manifest must not declare an automation receiver"
        }

        val directLogs = fileTree("src/main/java") { include("**/*.kt") }.files.filter { file ->
            !file.invariantSeparatorsPath.endsWith("core/AppLog.kt") &&
                ("import android.util.Log" in file.readText() || Regex("\\bLog\\.").containsMatchIn(file.readText()))
        }
        check(directLogs.isEmpty()) {
            "Application logs must use the Debug-only AppLog boundary: ${directLogs.joinToString { it.name }}"
        }
        val mainActivity = file("src/main/java/com/stt/benchmark/MainActivity.kt").readText()
        check("if (BuildConfig.DEBUG)" in mainActivity) { "Automation receiver must remain Debug-gated" }
        check("enabled = BuildConfig.DEBUG" in mainActivity) { "Automation intents must remain Debug-gated" }
    }
}

val verify16KbAlignment = tasks.register("verify16KbAlignment") {
    group = "verification"
    description = "Checks Debug/Release APK ZIP and arm64 ELF LOAD alignment for 16KB page-size compatibility."
    dependsOn("packageDebug", "packageRelease")
    inputs.files(
        file("build/outputs/apk/debug/app-debug.apk"),
        file("build/outputs/apk/release/app-release-unsigned.apk"),
        // Limit task inputs to the two variants that this gate actually reads. A broad
        // merged_native_libs input also captures deviceTest output and makes Gradle require an
        // unrelated mergeDeviceTestNativeLibs dependency whenever all artifacts are built.
        fileTree("build/intermediates/merged_native_libs/debug") { include("**/arm64-v8a/*.so") },
        fileTree("build/intermediates/merged_native_libs/release") { include("**/arm64-v8a/*.so") },
    )

    doLast {
        val sdkPath = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("ANDROID_HOME or ANDROID_SDK_ROOT is required for verify16KbAlignment")
        val sdk = file(sdkPath)
        val zipalign = File(sdk, "build-tools").listFiles()
            ?.sortedBy { it.name }
            ?.lastOrNull { File(it, "zipalign").canExecute() }
            ?.let { File(it, "zipalign") }
            ?: error("Android build-tools zipalign is missing under ${sdk.absolutePath}")
        val readelf = File(sdk, "ndk/28.2.13676358").walkTopDown()
            .firstOrNull { it.name == "llvm-readelf" && it.canExecute() }
            ?: error("NDK llvm-readelf is missing")

        fun runTool(vararg command: String): String {
            val output = ByteArrayOutputStream()
            project.exec {
                commandLine(*command)
                standardOutput = output
                errorOutput = output
            }
            return output.toString(Charsets.UTF_8)
        }

        listOf(
            file("build/outputs/apk/debug/app-debug.apk"),
            file("build/outputs/apk/release/app-release-unsigned.apk"),
        ).forEach { apk ->
            check(apk.isFile) { "APK is missing: ${apk.absolutePath}" }
            runTool(zipalign.absolutePath, "-c", "-P", "16", "-v", "4", apk.absolutePath)
        }

        listOf("debug", "release").forEach { variant ->
            val libraries = fileTree("build/intermediates/merged_native_libs/$variant") {
                include("**/arm64-v8a/*.so")
            }.files
            check(libraries.isNotEmpty()) { "No arm64 libraries found for $variant" }
            libraries.forEach { library ->
                val headers = runTool(readelf.absolutePath, "-l", library.absolutePath)
                val loadAlignments = Regex("""LOAD.*(?:0x([0-9a-fA-F]+)|2\\*\\*(\\d+))""")
                    .findAll(headers)
                    .map { match ->
                        match.groups[1]?.value?.toLong(16)
                            ?: (1L shl match.groups[2]!!.value.toInt())
                    }
                    .toList()
                check(loadAlignments.isNotEmpty()) { "No ELF LOAD headers found: ${library.name}" }
                check(loadAlignments.all { it >= 16L * 1024L }) {
                    "ELF LOAD alignment below 16KB for ${library.name}: $loadAlignments"
                }
            }
        }
    }
}

// Every Android variant runs provenance verification before resource processing/build output.
tasks.configureEach {
    if (name == "preBuild" || (name.startsWith("pre") && name.endsWith("Build"))) {
        dependsOn(verifyAssetProvenance)
    }
    if (name == "assembleRelease") {
        finalizedBy(verifyReleaseSurface)
    }
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
        // Preserve any previously installed test-only package even when its signing key differs.
        testApplicationId = "com.stt.benchmark.p1test"

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
        // Connected tests must never clear the primary com.stt.benchmark package or its long-STT data.
        create("deviceTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".deviceTest"
            versionNameSuffix = "-device-test"
            matchingFallbacks += listOf("debug")
        }
    }

    testBuildType = "deviceTest"

    // deviceTest is a data-safe clone of debug. Build type inheritance does not
    // inherit source-set files, so reuse the opt-in AICore probe activity/manifest.
    sourceSets.getByName("deviceTest") {
        java.srcDir("src/debug/java")
        manifest.srcFile("src/debug/AndroidManifest.xml")
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
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
    // Source-parity Codex OAuth library. App integration remains isolated under summary/.
    implementation(project(":codex-oauth-android"))

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

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
    testImplementation("org.json:json:20240303")
    testImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Galaxy S25 AICore 빠른 요약 사전 검증용. 정식 기능 전까지 테스트 APK에만 포함한다.
    androidTestImplementation("com.google.mlkit:genai-summarization:1.0.0-beta1")
}
