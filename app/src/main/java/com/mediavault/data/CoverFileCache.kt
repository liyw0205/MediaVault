package com.mediavault.data

import java.io.File
import java.io.FileInputStream

/** 封面缓存校验：拒绝空文件、极小文件与非图片魔数，避免 43B 之类坏缓存永久复用。 */
object CoverFileCache {
    /** 合法封面下限；1x1 PNG 约 68B，正常海报/截图远大于此。 */
    const val MIN_VALID_BYTES = 128L

    fun isValidCoverFile(file: File?): Boolean {
        if (file == null || !file.isFile) return false
        if (file.length() < MIN_VALID_BYTES) return false
        return try {
            FileInputStream(file).use { ins ->
                val h = ByteArray(12)
                val n = ins.read(h)
                if (n < 3) return false
                // JPEG
                if (h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte()) return true
                // PNG
                if (
                    n >= 8 &&
                    h[0] == 0x89.toByte() &&
                    h[1] == 0x50.toByte() &&
                    h[2] == 0x4E.toByte() &&
                    h[3] == 0x47.toByte()
                ) {
                    return true
                }
                // WEBP: RIFF....WEBP
                if (
                    n >= 12 &&
                    h[0] == 'R'.code.toByte() &&
                    h[1] == 'I'.code.toByte() &&
                    h[2] == 'F'.code.toByte() &&
                    h[3] == 'F'.code.toByte() &&
                    h[8] == 'W'.code.toByte() &&
                    h[9] == 'E'.code.toByte() &&
                    h[10] == 'B'.code.toByte() &&
                    h[11] == 'P'.code.toByte()
                ) {
                    return true
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 无效则删除，返回是否仍为有效缓存。 */
    fun keepIfValid(file: File): Boolean {
        if (isValidCoverFile(file)) return true
        runCatching { if (file.exists()) file.delete() }
        return false
    }

    fun atomicWrite(dest: File, writer: (tmp: File) -> Unit): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".tmp")
        return try {
            if (tmp.exists()) tmp.delete()
            writer(tmp)
            if (!isValidCoverFile(tmp)) {
                tmp.delete()
                return false
            }
            if (dest.exists()) dest.delete()
            if (tmp.renameTo(dest)) {
                true
            } else {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
                isValidCoverFile(dest)
            }
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }
}
