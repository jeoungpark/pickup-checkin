import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 서명: 저장소 밖 keystore를 keystore.properties(gitignore)로 지정
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.umosone.pickupcheckin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.umosone.pickupcheckin"
        minSdk = 28          // Pleos 요구사항: API 28 이상
        targetSdk = 34       // Pleos Connect Emulator v2.1.0 = API 34
        versionCode = 1
        versionName = "0.1.0"
        // 탑승자 알림 중계 서버. 데모는 adb reverse tcp:8787 tcp:8787 로 PC 서버에 연결 (에뮬레이터 네트워크가 끊겨도 동작)
        buildConfigField("String", "RELAY_URL", "\"http://127.0.0.1:8787\"")
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
