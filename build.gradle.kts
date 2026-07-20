plugins {
    // AGP 9 起内置 Kotlin 支持，不再需要（也不允许）单独应用 org.jetbrains.kotlin.android。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
