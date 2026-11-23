package com.example.healthapp.data.protocol

/**
 * 极简 JSON 解析器（纯 JVM，无依赖）。
 *
 * 只做「把 JSON 文本变成 Map / List / String / Double / Long / Boolean / null」这一件事，
 * 不做序列化、不支持注解。之所以不引第三方库：`protocol/` 层要保持零依赖可单测，
 * 而小米云返回的 JSON 结构简单且固定。
 *
 * 数字统一解析成 [Long]（没有小数）或 [Double]；取值请用 [asMap] / [str] / [int] 等便捷函数。
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.readValue()
        p.skipWhitespace()
        require(p.atEnd()) { "JSON 末尾有多余内容，位置 ${p.pos}" }
        return value
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(!atEnd()) { "JSON 意外结束" }
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) {
                    readNumber()
                } else {
                    throw IllegalArgumentException("非法字符 '$c'，位置 $pos")
                }
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "期望 $word，位置 $pos" }
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(!atEnd() && s[pos] == ':') { "对象缺少冒号，位置 $pos" }
                pos++
                map[key] = readValue()
                skipWhitespace()
                require(!atEnd()) { "对象未闭合" }
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> error("对象里出现意外字符 '${s[pos]}'，位置 $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                require(!atEnd()) { "数组未闭合" }
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> error("数组里出现意外字符 '${s[pos]}'，位置 $pos")
                }
            }
        }

        private fun readString(): String {
            require(!atEnd() && s[pos] == '"') { "期望字符串，位置 $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                require(!atEnd()) { "字符串未闭合" }
                when (val c = s[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        require(!atEnd()) { "转义符后意外结束" }
                        when (val e = s[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 < s.length) { "\\u 转义不完整" }
                                sb.append(s.substring(pos + 1, pos + 5).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("未知转义 \\$e")
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (!atEnd() && s[pos] == '-') pos++
            while (!atEnd() && s[pos].isDigit()) pos++
            var isDouble = false
            if (!atEnd() && s[pos] == '.') {
                isDouble = true; pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true; pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            val raw = s.substring(start, pos)
            return if (isDouble) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
        }
    }

    // ---------------------------------------------------------------- 取值便捷函数

    @Suppress("UNCHECKED_CAST")
    fun asMap(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun asList(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

    fun str(map: Map<String, Any?>, key: String): String = when (val v = map[key]) {
        null -> ""
        is String -> v
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    fun int(map: Map<String, Any?>, key: String): Int = when (val v = map[key]) {
        is Long -> v.toInt()
        is Double -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    fun long(map: Map<String, Any?>, key: String): Long = when (val v = map[key]) {
        is Long -> v
        is Double -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }
}
