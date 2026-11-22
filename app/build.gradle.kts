plugins {
    id("com.android.application")
}
android {
    namespace = "com.shinpstudio.igoreview"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.shinpstudio.igoreview"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
