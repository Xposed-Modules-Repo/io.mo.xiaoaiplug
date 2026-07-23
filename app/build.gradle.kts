import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.mo.xiaoaiplug"
    // miuix 0.9.x 的 AAR 元数据要求 minCompileSdk=37，低于它 checkDebugAarMetadata 直接失败。
    compileSdk = 37

    defaultConfig {
        applicationId = "io.mo.xiaoaiplug"
        // miuix-blur 要求 minSdk 33（模糊靠 RenderEffect，低版本拿不到「液态玻璃」质感）。
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.2.4"
    }

    buildTypes {
        release {
            // Compose + miuix 全家桶不裁剪的话 dex 有 36MB,包体 23.8MB。
            // 开 R8 之后绝大部分是没用到的库代码(尤其 miuix.icons 那几千个图标常量)。
            // 注意:模块入口和 ModuleStatus 必须有 keep 规则,见 proguard-rules.pro。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    // Kotlin 2.x 起 kotlinOptions.jvmTarget 是硬错误，必须走 compilerOptions DSL
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.xposed.api)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)

    // 纯 JVM 单元测试(src/test)。只有不碰 Android framework 的纯逻辑走这里 ——
    // 目前是 Tools.isReadOnlyShell:它决定模型能不能以 root 执行任意命令,
    // 是全项目最不该被悄悄改坏的一段,必须有回归保护。
    testImplementation(libs.junit)
}
