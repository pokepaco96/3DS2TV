plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.threedstwo.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.threedstwo.tv"
        // Chromecast con Google TV 4K (2020) salió con Android TV 10 (API 29) y hoy usa
        // Android TV 12/14. API 24 es suficiente para todas las APIs usadas y deja la
        // APK instalable en más televisores Android TV.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // JVM unit tests only use pure logic and compile-time Android constants.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi",
            // Single-language (Spanish) TV UI built in code; generated icons.
            "SetTextI18n", "IconLauncherShape", "IconMissingDensityFolder")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

/** Commit embedded in the APK for diagnostics: GITHUB_SHA in CI, `git rev-parse` locally, "unknown" otherwise. */
fun gitCommit(): String {
    System.getenv("GITHUB_SHA")?.takeIf { it.length >= 7 }?.let { return it.take(12) }
    return try {
        val p = ProcessBuilder("git", "rev-parse", "--short=12", "HEAD").directory(rootDir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.matches(Regex("[0-9a-f]{7,40}"))) out else "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
