plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tech.httptoolkit.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.httptoolkit.android.v1"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.kotlin.stdlib.jdk7)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.espresso.core)
}
