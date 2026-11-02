plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chistyl.tvremote"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.chistyl.tvremote"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-dev"
    }
}
