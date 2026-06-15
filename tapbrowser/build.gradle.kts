plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.TapLinkX3.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 29

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // Phase 4g — CameraX preview (PreviewView) for the unipanel
    // camera-preview frame. The Service in `app` owns the CameraX
    // pipeline; tapbrowser hosts only the PreviewView's
    // SurfaceProvider, which is passed across the binder.
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-core:1.4.1")

    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SMB/CIFS client (SMB2/3) for LAN network shares. jcifs-ng is the
    // maintained fork with SMB2/3 support; SMB1 stays off (insecure, and
    // disabled on modern NAS by default).
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    // Encrypted storage for SMB share credentials (never plaintext prefs).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ExoPlayer (Media3) for native radio streaming and HEVC gallery playback.
    // MediaPlayer has a tiny fixed buffer that causes ~14s rebuffer stutters on MP3 streams.
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.media3:media3-effect:1.7.1")
    // Prebuilt FFmpeg software decoders for ExoPlayer (AC3/E-AC3/DTS/TrueHD/
    // Vorbis/etc.) so MKV/AVI audio the device MediaCodec can't decode still
    // plays. NextLib versions are "<media3>-<nextlib>", so this MUST match the
    // media3 version above (1.7.1).
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")

    compileOnly(files("libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))
    compileOnly(files("libs/RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
