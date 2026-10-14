// FastPlay for Android. The windows are Kotlin (src/main/java); the engine is the
// same C++ as on the desktop and the iPhone, built by src/main/cpp/CMakeLists.txt
// with FFmpeg for Android from ffmpeg-android/ at the repository's top.
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// The version is the engine's (include/fastplay/version.h); the build number is the
// count of commits, so each build is higher than the last, as on the iPhone.
val appVersion: String = rootProject.file("../include/fastplay/version.h").readLines()
    .firstOrNull { it.startsWith("#define APP_VERSION ") }
    ?.substringAfter('"')?.substringBefore('"') ?: "0.0.0"
val buildNumber: Int = try {
    val out = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        standardOutput = out
    }
    out.toString().trim().toInt()
} catch (e: Exception) {
    1
}

fun secret(name: String): String? = (project.findProperty(name) as String?) ?: System.getenv(name)

android {
    namespace = "me.masonasons.fastplay"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "me.masonasons.fastplay"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = appVersion

        // Phones (arm64) and the emulator (x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // 16 KB pages, as newer phones have, as well as 4 KB
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The release keystore and its passwords come from Gradle properties or the
    // environment (CI's secrets), never the repository. Without them a release
    // build is signed with the debug key: fine to try, not to publish.
    signingConfigs {
        create("release") {
            val store = secret("ANDROID_KEYSTORE_FILE")
            val storePass = secret("ANDROID_KEYSTORE_PASSWORD")
            val alias = secret("ANDROID_KEY_ALIAS")
            val keyPass = secret("ANDROID_KEY_PASSWORD")
            if (store != null && storePass != null && alias != null && keyPass != null) {
                storeFile = file(store)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Programs run as files (yt-dlp's JavaScript runtime) must be unpacked
            useLegacyPackaging = true
        }
        // JSch and Bouncy Castle (the servers' libraries) both carry this, which no one reads
        resources {
            excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        }
    }
}

// ---- YouTube: yt-dlp, in Python inside the app (Chaquopy) ----
// The APK carries this yt-dlp (and yt-dlp-ejs, the scripts that answer YouTube's
// challenges, run by QuickJS: src/main/cpp/CMakeLists.txt); FastPlay downloads a
// newer release at run time (src/main/python/fastplay_youtube.py). Building needs
// Python 3.13 (Windows: py -3.13; elsewhere: python3.13).
chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("yt-dlp==2026.8.19")
            install("yt-dlp-ejs==0.8.0")
        }
    }
}

// The APK as FastPlay.apk, for the release page
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "FastPlay.apk"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // The lock screen, notification and headphone controls (MediaSessionCompat)
    implementation("androidx.media:media:1.7.0")
    // Signing in to Dropbox in a browser tab
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Servers (remote/): SMB through smbj, a server's list of shares through its
    // DCE/RPC companion (SRVSVC), SFTP through JSch (the maintained fork). smbj
    // brings Bouncy Castle (bcprov), which it calls directly rather than as a
    // security provider, so it never meets Android's own "BC" provider; JSch is
    // pointed at the same classes for Ed25519 and Curve25519 (SFTPSource).
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("com.rapid7.client:dcerpc:0.12.13")
    implementation("com.github.mwiede:jsch:2.28.7")
}
