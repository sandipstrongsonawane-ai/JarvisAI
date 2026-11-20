import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Provider secrets are entered in Settings and stored with Android Keystore encryption.

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    setProperty("archivesBaseName", "jarvis")

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // This APK targets the user's ARM64 Android phone; avoid bundling unused ABIs.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Compatibility constants are empty; provider secrets are loaded from encrypted runtime storage.
        buildConfigField("String", "GEMINI_API_KEY", "\"\"")
        buildConfigField("String", "OPENWEATHER_API_KEY", "\"\"")
        buildConfigField("String", "GROQ_API_KEY", "\"\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"\"")
        // Optional: Voices → copy ID from https://elevenlabs.io/app/voice-library
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "onnx"
    }
    packaging {
        jniLibs {
            // sherpa-onnx uses ONNX Runtime in the APK.
            // Keep one shared runtime copy instead of failing native merge.
            pickFirsts += "**/libonnxruntime.so"
        }
        resources {
            // The sherpa AAR also ships desktop ONNX runtimes; Android never loads these.
            excludes += "sherpa-onnx/native/**"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }
    composeOptions {
        // Compatible with Kotlin 1.9.24 (root build.gradle.kts)
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("xyz.rementia:openwakeword:0.1.5")
    implementation("ai.picovoice:porcupine-android:3.0.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    implementation("com.github.k2-fsa:sherpa-onnx:v2.0.1") {
        // The Android AAR already contains the sherpa JVM API classes.
        // Exclude the separately published JVM JAR to prevent duplicate classes.
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
    }
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")


    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:object-detection:17.0.1")
    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Jetpack Compose (used by com.jarvis.ai armor suit UI)
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Output APK always named jarvis.apk
android.applicationVariants.configureEach {
    outputs.configureEach {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
            .outputFileName = "jarvis.apk"
    }
}


// Bundle the pinned offline speaker model into the APK; no model download is needed at runtime.
val speakerModel = layout.projectDirectory.file(
    "src/main/assets/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
)
val downloadSpeakerModel by tasks.registering {
    outputs.file(speakerModel)
    doLast {
        val file = speakerModel.asFile
        val expectedSize = 29596979L
        val expectedSha256 = "98daf9bcf1c8abf3db374fec4c27a5eda7c58705bb3f180d057b085a72a88bb5"
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        if (!file.exists() || file.length() != expectedSize || sha256(file.readBytes()) != expectedSha256) {
            file.parentFile.mkdirs()
            val temporary = file.resolveSibling(file.name + ".part")
            try {
                URL(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
                ).openStream().use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.length() == expectedSize) { "Speaker model size verification failed" }
                check(sha256(temporary.readBytes()) == expectedSha256) { "Speaker model SHA-256 verification failed" }
                check(temporary.renameTo(file)) { "Could not install verified speaker model asset" }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }
}

// Pinned offline English ASR model used only for the one-use voice-auth challenge.
// The int8 encoder is ~43 MB; the model is verified before extraction and never
// downloaded at runtime. The model is Apache-2.0 licensed.
val voiceChallengeModelDir = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
val voiceChallengeArchive = layout.buildDirectory.file(
    "downloads/$voiceChallengeModelDir.tar.bz2"
)
val voiceChallengeAssets = layout.projectDirectory.dir("src/main/assets/voice_challenge")
val downloadVoiceChallengeAsrModel by tasks.registering {
    outputs.dir(voiceChallengeAssets)
    outputs.upToDateWhen { false }
    doLast {
        val archive = voiceChallengeArchive.get().asFile
        val expectedSize = 127887156L
        val expectedSha256 = "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9"
        fun sha256(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        archive.parentFile.mkdirs()
        if (!archive.exists() || archive.length() != expectedSize || sha256(archive) != expectedSha256) {
            val temporary = archive.resolveSibling(archive.name + ".part")
            try {
                URL(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$voiceChallengeModelDir.tar.bz2"
                ).openStream().use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.length() == expectedSize) { "Voice challenge ASR archive size verification failed" }
                check(sha256(temporary) == expectedSha256) { "Voice challenge ASR archive SHA-256 verification failed" }
                check(temporary.renameTo(archive)) { "Could not install verified voice challenge ASR archive" }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }

        val outputDir = voiceChallengeAssets.asFile
        outputDir.mkdirs()
        val requiredFiles = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"
        )
        val ready = requiredFiles.all { name ->
            val file = outputDir.resolve(name)
            file.exists() && file.length() > if (name == "tokens.txt") 1024L else 100_000L
        }
        if (!ready) {
            requiredFiles.forEach { outputDir.resolve(it).delete() }
            copy {
                from(tarTree(resources.bzip2(archive))) {
                    include("$voiceChallengeModelDir/encoder-epoch-99-avg-1.int8.onnx")
                    include("$voiceChallengeModelDir/decoder-epoch-99-avg-1.onnx")
                    include("$voiceChallengeModelDir/joiner-epoch-99-avg-1.int8.onnx")
                    include("$voiceChallengeModelDir/tokens.txt")
                    eachFile { path = name }
                    includeEmptyDirs = false
                }
                into(outputDir)
            }
        }
        requiredFiles.forEach { name ->
            val file = outputDir.resolve(name)
            check(file.exists() && file.length() > if (name == "tokens.txt") 1024L else 100_000L) {
                "Voice challenge ASR model asset missing or truncated: $name"
            }
        }
    }
}


tasks.named("preBuild").configure {
    dependsOn(downloadSpeakerModel)
    dependsOn(downloadVoiceChallengeAsrModel)
}

