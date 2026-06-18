package com.link2action.bot.common

object UrlExtractor {
    private val URL_REGEX = Regex("""https?://\S+""")

    fun extract(text: String): String? {
        return URL_REGEX
            .find(text)
            ?.value
            ?.trimEnd('.', ',', ')', ']', '}')
    }
}