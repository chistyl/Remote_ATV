plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "com.chistyl.tvremote"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.chistyl.tvremote"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-dev"
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks {
        all().configureEach {
            builtins {
                id("java") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    testImplementation("junit:junit:4.13.2")
}
