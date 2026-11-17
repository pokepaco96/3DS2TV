plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Benchmarks only run with ./gradlew :core:test -Pbench (never in normal test runs).
    systemProperty("bench", if (project.hasProperty("bench")) "true" else "false")
    testLogging { showStandardStreams = project.hasProperty("bench") }
}
