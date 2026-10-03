package com.example.jellybrowser

import android.content.res.AssetManager
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

object AdBlocker {

    // 広告ブロックON/OFF
    var enabled: Boolean = true

    // ブロック対象ドメイン
    private val blockedDomains =
        mutableSetOf<String>()

    /**
     * assets/adblock_domains.txt を読み込む
     */
    fun init(
        assets: AssetManager
    ) {

        blockedDomains.clear()

        try {

            assets.open(
                "adblock_domains.txt"
            ).bufferedReader().useLines { lines ->

                lines.forEach { line ->

                    val domain =
                        line.trim()

                    // 空行・コメントは無視
                    if (
                        domain.isBlank() ||
                        domain.startsWith("#")
                    ) {
                        return@forEach
                    }

                    blockedDomains.add(
                        domain
                            .lowercase(Locale.ROOT)
                            .trimEnd('.')
                    )
                }
            }

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }

    /**
     * 指定されたホストがブロック対象か判定
     *
     * 例：
     *
     * bance.jp
     *
     * ↓
     * img.dsp.bance.jp
     * js.ssp.bance.jp
     *
     * もブロック対象になる
     */
    fun isBlockedHost(
        host: String
    ): Boolean {

        var current =
            host
                .lowercase(Locale.ROOT)
                .trimEnd('.')

        while (true) {

            if (
                blockedDomains.contains(current)
            ) {
                return true
            }

            val dot =
                current.indexOf('.')

            if (dot == -1) {
                break
            }

            current =
                current.substring(
                    dot + 1
                )
        }

        return false
    }

    /**
     * WebViewへ返す空レスポンス
     */
    fun emptyResponse():
            WebResourceResponse {

        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(
                ByteArray(0)
            )
        )
    }

}
