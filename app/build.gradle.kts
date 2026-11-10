plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// 署名はシリーズ共通鍵（Theta.Hatch.Series/secrets/README.md、別名 thetahatch）。
// CI は Secret（ANDROID_KEYSTORE_B64 など 4 つ）から鍵ファイルを作り、ANDROID_KEYSTORE_FILE で渡す。
// 鍵が無い環境（手元の試し）ではデバッグ鍵で署名する。
val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.theta.camera.hatch"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.thetahatch.camera"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "0.11.0"
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (ksFile != null) create("thetahatch") {
            storeFile = ksFile
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (ksFile != null) "thetahatch" else "debug")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    val camera = "1.6.2"
    implementation("androidx.camera:camera-camera2:$camera")
    implementation("androidx.camera:camera-lifecycle:$camera")
    implementation("androidx.camera:camera-view:$camera")
    implementation("androidx.camera:camera-video:$camera")
    implementation("androidx.camera:camera-extensions:$camera")
    implementation("androidx.window:window:1.4.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
}
