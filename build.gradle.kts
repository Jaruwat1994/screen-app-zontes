// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories { google() }
    // R8 9.2.14 (bundled with AGP 9.2.1) wrote a broken classes.dex ("Out-of-order string_ids:
    // ScheduledExecutorService") once MainActivity gained connectSaved(): the app crashed on launch.
    // Same 9.2 line, bug fixes only. Drop this when AGP bundles a newer R8.
    dependencies { classpath("com.android.tools:r8:9.2.39") }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
