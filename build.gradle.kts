// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Google Services: reads app/google-services.json and hands the Firebase settings to the app.
    alias(libs.plugins.google.services) apply false
}