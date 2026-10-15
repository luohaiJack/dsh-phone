package com.dsh.phone

/**
 * 终端文本的**样式**层。
 *
 * 屏幕模型与转义解析在 TermScreen.kt；这里只放"一段同样式的文本"这个数据结构
 * 与配色表 —— 渲染层（Compose）和屏幕模型都依赖它，但不互相依赖。
 */

/** 一段同样式的文本。null 表示"用主题默认色"。 */
data class AnsiSpan(
    val text: String,
    val fg: Int? = null,
    val bg: Int? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
)
