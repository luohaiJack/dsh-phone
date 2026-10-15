// 顶层构建脚本：只声明插件版本，不在此处 apply。
//
// 注意 AGP 9 起内置了 Kotlin 支持，`org.jetbrains.kotlin.android` 不再需要，
// 应用它反而会直接报错（官方迁移指引：
// https://developer.android.com/build/migrate-to-built-in-kotlin ）。
// 因此这里只留 Compose 编译器插件。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
