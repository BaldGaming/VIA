import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.example.via"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.via"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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

        // Creates the fields for BuildConfig
        buildConfigField("String", "DROPBOX_CLIENT_ID", "\"$clientId\"")
        buildConfigField("String", "DROPBOX_CLIENT_SECRET", "\"$clientSecret\"")
        buildConfigField("String", "DROPBOX_REFRESH_TOKEN", "\"$refreshToken\"")
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