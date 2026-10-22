import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Same native config path as tvApp: git-ignored secrets.properties; a missing file or key builds with an empty token.
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val tmdbReadAccessToken: String = secrets.getProperty("TMDB_READ_ACCESS_TOKEN", "").trim()

android {
    namespace = "com.techgv.cineverse.mobile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.techgv.cineverse.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "TMDB_READ_ACCESS_TOKEN", "\"$tmdbReadAccessToken\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
}
