package io.legado.app

/**
 * 适配层：玻璃组件按 io.legado.app.R 引用字符串资源，
 * 这里把 id 指向本应用生成的 R，避免改动组件源码。
 */
object R {
    object string {
        val home_capsule_restore_navigation: Int = com.sjtuhive.app.R.string.home_capsule_restore_navigation
    }
}
