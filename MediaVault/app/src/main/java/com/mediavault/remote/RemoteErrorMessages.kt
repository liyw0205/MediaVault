package com.mediavault.remote

import android.content.Context
import com.mediavault.R
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 将远程协议/播放链路上的异常转为用户可读中文（Toast、刮削错误、测试连接等）。
 */
object RemoteErrorMessages {

    fun userMessage(context: Context, throwable: Throwable?): String {
        if (throwable == null) return context.getString(R.string.remote_err_unknown)
        val chain = generateSequence(throwable) { it.cause }.toList()
        for (t in chain) {
            when (t) {
                is UnknownHostException ->
                    return context.getString(R.string.remote_err_host)
                is ConnectException ->
                    return context.getString(R.string.remote_err_connect)
                is SocketTimeoutException ->
                    return context.getString(R.string.remote_err_timeout)
                is SSLException ->
                    return context.getString(R.string.remote_err_ssl)
            }
            if (t is IOException) {
                val msg = t.message?.trim().orEmpty()
                if (msg.isNotEmpty()) {
                    mapKnownIoMessage(context, msg)?.let { return it }
                }
            }
        }
        val leaf = chain.lastOrNull() ?: throwable
        val msg = leaf.message?.trim().orEmpty()
        if (msg.isNotEmpty()) {
            mapKnownIoMessage(context, msg)?.let { return it }
            if (looksEnglishTechnical(msg)) {
                return context.getString(R.string.remote_err_generic_fmt, msg.take(120))
            }
            return msg.take(200)
        }
        return context.getString(R.string.remote_err_unknown)
    }

    private fun looksEnglishTechnical(msg: String): Boolean {
        if (msg.any { it.code in 0x4E00..0x9FFF }) return false
        val lower = msg.lowercase()
        return lower.contains("exception") || lower.contains("failed") ||
            lower.contains("error") || lower.contains("unsupported") ||
            lower.contains("remote not") || lower.contains("webdav")
    }

    private fun mapKnownIoMessage(context: Context, msg: String): String? {
        return when {
            msg.contains("缓存缺口") || msg.contains("behind prefix") ->
                context.getString(R.string.remote_err_cache_gap)
            msg.contains("Remote not configured") ->
                context.getString(R.string.remote_err_not_configured)
            msg.contains("Missing remote id") ->
                context.getString(R.string.remote_err_bad_uri)
            msg.contains("Unsupported URI") ->
                context.getString(R.string.remote_err_bad_uri)
            msg.contains("Not opened") ->
                context.getString(R.string.remote_err_not_opened)
            msg.contains("401") || msg.contains("403") ||
                msg.contains("Unauthorized", ignoreCase = true) || msg.contains("Forbidden", ignoreCase = true) ->
                context.getString(R.string.remote_err_auth)
            msg.contains("404") || msg.contains("Not Found", ignoreCase = true) ||
                (msg.startsWith("FTP") && msg.contains("550")) ||
                msg.contains("No such file", ignoreCase = true) ->
                context.getString(R.string.remote_err_not_found)
            msg.contains("416") || msg.contains("Range Not Satisfiable", ignoreCase = true) ||
                msg.contains("Requested Range", ignoreCase = true) ->
                context.getString(R.string.remote_err_range_unsupported)
            msg.startsWith("WebDAV GET") || msg.startsWith("WebDAV ") ->
                context.getString(R.string.remote_err_webdav_fmt, msg.removePrefix("WebDAV ").take(80))
            msg.startsWith("FTP 登录失败") || msg.startsWith("FTP 无法打开") ||
                msg.startsWith("FTP 不支持断点续传") ||
                msg.contains("FTP 不支持从指定位置") -> msg
            else -> null
        }
    }
}