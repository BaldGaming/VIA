import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

// AUTO-VERSIONING SCRIPT
// UPDATE THIS MANUALLY!!!!!!!
val majorVersion = 3

val versionPropsFile = file("version.properties")
val versionProps = Properties()

// Auto-creates the tracking file if it doesn't exist yet
if (!versionPropsFile.exists()) {
    versionProps["MINOR"] = "0"
    versionProps["PATCH"] = "0"
    versionProps.store(FileOutputStream(versionPropsFile), null)
}

versionProps.load(FileInputStream(versionPropsFile))

var minorCount = versionProps["MINOR"].toString().toInt()
var patchCount = versionProps["PATCH"].toString().toInt()

// The lock: Identifies if an actual compilation task is running.
// Uses taskNames to correctly catch the "Generate Signed Bundle / APK" wizard.
val activeTasks = gradle.startParameter.taskNames.toString().lowercase()
val isBuildingAPK = activeTasks.contains("assemble") || activeTasks.contains("bundle")
val isRunButton = project.hasProperty("android.injected.build.abi")

if (isBuildingAPK && !isRunButton) {
    patchCount++

    // Shifts the patch to minor if it hits 10 (e.g., 3.0.9 -> 3.1.0)
    if (patchCount > 9) {
        patchCount = 0
        minorCount++
    }

    // Saves the new numbers back to the file
    versionProps["MINOR"] = minorCount.toString()
    versionProps["PATCH"] = patchCount.toString()
    versionProps.store(FileOutputStream(versionPropsFile), null)
}

// Combines the manual Major with the auto Minor/Patch
val computedVersionName = "$majorVersion.$minorCount.$patchCount"

// Safely generates a unique whole number for Google Play (Multiplied by 100,000 so the Minor version can go up to 999 safely)
val computedVersionCode = (majorVersion * 100000) + (minorCount * 100) + patchCount
// Ends here!

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
}

// Google's official, crash-proof way to name APKs in modern AGP.
// Will output as: via_v3.0.2-debug.apk or via_v3.0.2-release.apk
base {
    archivesName.set("via_v${computedVersionName}")
}

android {
    namespace = "com.example.via"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.via"
        minSdk = 26
        targetSdk = 36

        // Auto-injected versioning
        versionCode = computedVersionCode
        versionName = computedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Opens the properties file
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        }

        // Gets the permanent keys from the file
        val clientId = localProperties.getProperty("DROPBOX_CLIENT_ID") ?: ""
        val clientSecret = localProperties.getProperty("DROPBOX_CLIENT_SECRET") ?: ""
        val refreshToken = localProperties.getProperty("DROPBOX_REFRESH_TOKEN") ?: ""
        val azureKey = localProperties.getProperty("AZURE_TTS_KEY") ?: ""
        val azureRegion = localProperties.getProperty("AZURE_TTS_REGION") ?: ""

        // Creates the fields for BuildConfig
        buildConfigField("String", "DROPBOX_CLIENT_ID", "\"$clientId\"")
        buildConfigField("String", "DROPBOX_CLIENT_SECRET", "\"$clientSecret\"")
        buildConfigField("String", "DROPBOX_REFRESH_TOKEN", "\"$refreshToken\"")
        buildConfigField("String", "AZURE_TTS_KEY", "\"$azureKey\"")
        buildConfigField("String", "AZURE_TTS_REGION", "\"$azureRegion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Dropbox API fetching
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    // JSON translator
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")

    implementation("androidx.media3:media3-session:1.9.2")
    implementation("androidx.media3:media3-exoplayer:1.9.2") // media player shit
    implementation("androidx.media3:media3-ui:1.9.2") // media player shit
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2") // for the suspend keyword found in ApiServices.kt
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.firebase.database)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}