plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    implementation(project(":core"))
}

application {
    mainClass.set("com.threedstwo.tv.mock.MockSenderMainKt")
    applicationName = "mock-3ds-sender"
}

dependencies {
    testImplementation(libs.junit)
}
