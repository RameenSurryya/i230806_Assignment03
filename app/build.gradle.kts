plugins {
    alias(libs.plugins.android.application)
    // Reads app/google-services.json (project ID, API key, database URL) at build time.
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.rameen.i230806"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Must match the package name registered in the Firebase console.
        applicationId = "com.rameen.i230806"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Agora ships native code for four CPU types. Keep the two we run on:
        // arm64-v8a = real phones, x86_64 = the emulator. Makes the APK much smaller.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // Turns off emulator animations while Espresso tests run, so taps never land mid-transition.
        animationsDisabled = true
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)

    // Firebase: the BoM sets the versions, the libraries below use them.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)       // accounts: sign up, log in, log out
    implementation(libs.firebase.database)   // Realtime Database: users, posts, chats ...
    implementation(libs.firebase.messaging)  // push notifications (FCM)

    // CameraX: camera screen (09) for stories
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // Agora: voice and video calls
    implementation(libs.agora.rtc)

    testImplementation(libs.junit)
    // Espresso UI tests (app/src/androidTest)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}