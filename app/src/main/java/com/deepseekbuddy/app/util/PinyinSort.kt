package com.deepseekbuddy.app.util

import com.github.promeg.pinyinhelper.Pinyin

/** 拼音排序工具（TinyPinyin）：通讯录按姓名首字母排序 */
object PinyinSort {

    /** 姓名的拼音串（小写，无空格），用于排序 */
    fun pinyin(name: String): String = Pinyin.toPinyin(name, "").lowercase()

    /** 姓名首字母（大写）；非汉字/空 → # */
    fun initial(name: String): String {
        val c = name.firstOrNull() ?: return "#"
        if (c.code < 128) return c.uppercaseChar().toString()
        val py = Pinyin.toPinyin(c)
        return if (py.isNullOrEmpty()) "#" else py.first().uppercaseChar().toString()
    }
}
