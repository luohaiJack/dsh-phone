plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dsh.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dsh.phone"
        minSdk = 26
        targetSdk = 37
        // versionCode 每次发版 +1：**用浏览器/文件管理器装 APK 时，
        // 系统要求新包的 versionCode 不低于已装的**，否则会报
        // 应用未安装/包无效。adb install -r 比较宽容，但用户自己点安装时不行。
        versionCode = 4
        versionName = "1.3"
        resourceConfigurations += listOf("zh", "en")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 个人自用工具：直接用 debug 签名，省掉生成 keystore 的一步。
            // 要正式分发时换成自己的 keystore 即可。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

// AGP 9 内置 Kotlin：jvmTarget 默认取 android.compileOptions.targetCompatibility，
// 所以这里不需要再显式声明 kotlin.compilerOptions.jvmTarget。

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // material3 在 Compose 1.12 起不再传递引入图标核心包，显式声明（版本由 BOM 钉住）
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
