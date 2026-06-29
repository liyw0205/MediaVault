package com.mediavault.remote

import java.io.FilterInputStream
import java.io.InputStream

internal class RemoteLimitedInputStream(
    `in`: InputStream,
    private var remaining: Long,
) : FilterInputStream(`in`) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n.toLong()
        return n
    }
}