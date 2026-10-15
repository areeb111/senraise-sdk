plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.esite.senraise.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.esite.senraise.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // The files in /examples are compiled as part of this module. Snippets in a
    // README rot silently; snippets the build compiles cannot. They are not
    // referenced by the sample UI — they exist to be read, and to break the
    // build if the SDK's API ever drifts away from what the docs show.
    sourceSets["main"].kotlin.srcDir(rootProject.file("examples"))

    buildTypes {
        release {
            // Proves the consumer rules in :senraise keep the AIDL stub alive
            // under R8 — the sample is the only place that can prove it.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    implementation(project(":senraise"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
