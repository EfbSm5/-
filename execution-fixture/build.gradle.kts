plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.rootpilot.fixture"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.rootpilot.fixture"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// This app is a device acceptance fixture, never a distributable product variant.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}
