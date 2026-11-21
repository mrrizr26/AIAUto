plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions theke pass kora hoy (-PappVersionCode, -PgithubRepo)
val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 100
val githubRepo: String = (project.findProperty("githubRepo") as String?) ?: ""

android {
    namespace = "com.example.autoenhance"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.autoenhance"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.3.${appVersionCode - 100}"
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Shob build same key diye sign hobe, tai update install kaj korbe.
    signingConfigs {
        create("shared") {
            storeFile = file("autoenhance.keystore")
            storePassword = "autoenhance"
            keyAlias = "autoenhance"
            keyPassword = "autoenhance"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
}
