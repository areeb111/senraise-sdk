plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val sdkVersion = "1.0.0"

android {
    namespace = "com.esite.senraise"
    compileSdk = 36

    defaultConfig {
        // 24 is what the terminals in the field run at the oldest, and it is the
        // floor for StaticLayout.Builder, which the receipt renderer needs for
        // Arabic shaping and for wrap-then-ellipsise.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        // The whole point of the module: the vendor's PrinterInterface.aidl is
        // compiled here so consumers never have to carry it themselves.
        aidl = true
        buildConfig = false
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        // Sources go in the artifact: this SDK's value is as much in why it does
        // things as in what it does, and a consumer debugging a print at 2am
        // should be able to step into it.
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

/**
 * Publishing.
 *
 * `./gradlew :senraise:publishToMavenLocal` is the everyday route — it makes the
 * AAR resolvable as `com.esite.senraise:senraise-printer:<version>` from any
 * project on the machine that has `mavenLocal()` in its repositories.
 *
 * GitHub Packages is wired up but only appears when credentials are in the
 * environment, so a clone with no secrets still configures and builds.
 */
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.esite.senraise"
            artifactId = "senraise-printer"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Senraise POS Printer SDK")
                description.set(
                    "Kotlin SDK for the print head built into Senraise Android POS terminals, " +
                        "with an Arabic-capable receipt renderer.",
                )
            }
        }
    }
    repositories {
        val githubActor = providers.environmentVariable("GITHUB_ACTOR").orNull
        val githubToken = providers.environmentVariable("GITHUB_TOKEN").orNull
        if (githubActor != null && githubToken != null) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/areeb111/senraise-sdk")
                credentials {
                    username = githubActor
                    password = githubToken
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
    // Only the QR encoder is used, and only by ReceiptRenderer. Printing a
    // bitmap or driving the AIDL directly does not touch it.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
