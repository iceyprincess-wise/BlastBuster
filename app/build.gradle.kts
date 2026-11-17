import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.splendor.blastbuster"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.splendor.blastbuster"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    // Permanent release key (v1.3): ONE keystore in the home dir, never in
    // the repo and never in the APK. Credentials live in
    // ~/blastbuster-signing.properties (chmod 600). If that file is absent
    // the build fails loudly instead of silently falling back to a debug
    // key — a silent fallback is how v1.2's key was lost.
    signingConfigs {
        create("release") {
            // user.home is /root for the Gradle JVM in this sandbox, so
            // resolve through $HOME (or BLASTBUSTER_SIGNING_PROPERTIES).
            val signingPropsFile = System.getenv("BLASTBUSTER_SIGNING_PROPERTIES")
                ?.let { File(it) }
                ?: File(
                    System.getenv("HOME") ?: System.getProperty("user.home"),
                    "blastbuster-signing.properties"
                )
            if (!signingPropsFile.exists()) {
                throw GradleException(
                    "Missing ~/blastbuster-signing.properties — " +
                        "release signing key not present; refusing to sign with a debug key."
                )
            }
            val signingProps = Properties().apply {
                load(FileInputStream(signingPropsFile))
            }
            storeFile = file(signingProps.getProperty("storeFile")
                ?: throw GradleException("blastbuster-signing.properties: missing storeFile"))
            storePassword = signingProps.getProperty("storePassword")
                ?: throw GradleException("blastbuster-signing.properties: missing storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
                ?: throw GradleException("blastbuster-signing.properties: missing keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
                ?: throw GradleException("blastbuster-signing.properties: missing keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // Release lint is off: the lint-gradle artifact (31.2.2) is not in the
    // offline Maven repo and Java networking is blocked in this sandbox,
    // so the lintVital task cannot resolve it. This disables only the
    // lint gate, not compiler warnings — the Kotlin/Java compile must
    // still be warning-free. Lint can run wherever the artifact is
    // available (it is not a code finding, it is an unresolvable task).
    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No dependencies on purpose: plain android.app.Activity, no AndroidX,
// so the build needs only the Android SDK + the two Gradle plugins.
