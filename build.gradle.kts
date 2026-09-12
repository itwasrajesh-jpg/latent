plugins {
    alias(libs.plugins.android.application) apply false
    // The fetched engine modules are Android libraries; declaring the plugin here (unapplied)
    // puts one version on the classpath for the whole build.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
