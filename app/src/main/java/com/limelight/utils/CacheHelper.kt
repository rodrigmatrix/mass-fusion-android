package com.limelight.utils

import java.io.*

object CacheHelper {
    @JvmStatic
    fun openPath(createPath: Boolean, root: File, vararg path: String): File {
        var f = root
        for (i in path.indices) {
            val component = path[i]
            if (i == path.size - 1) {
                if (createPath) {
                    f.mkdirs()
                }
            }
            f = File(f, component)
        }
        return f
    }

    @JvmStatic
    fun getFileSize(root: File, vararg path: String): Long {
        return openPath(false, root, *path).length()
    }

    @JvmStatic
    fun deleteCacheFile(root: File, vararg path: String): Boolean {
        return openPath(false, root, *path).delete()
    }

    @JvmStatic
    fun cacheFileExists(root: File, vararg path: String): Boolean {
        return openPath(false, root, *path).exists()
    }

    @JvmStatic
    @Throws(FileNotFoundException::class)
    fun openCacheFileForInput(root: File, vararg path: String): InputStream {
        return BufferedInputStream(FileInputStream(openPath(false, root, *path)))
    }

    @JvmStatic
    @Throws(FileNotFoundException::class)
    fun openCacheFileForOutput(root: File, vararg path: String): OutputStream {
        return BufferedOutputStream(FileOutputStream(openPath(true, root, *path)))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeInputStreamToOutputStream(inStream: InputStream, outStream: OutputStream, maxLength: Long) {
        var remainingLength = maxLength
        val buf = ByteArray(4096)
        var bytesRead: Int
        while (inStream.read(buf).also { bytesRead = it } != -1) {
            remainingLength -= bytesRead.toLong()
            if (remainingLength <= 0) {
                throw IOException("Stream exceeded max size")
            }
            outStream.write(buf, 0, bytesRead)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readInputStreamToString(inStream: InputStream): String {
        val r: Reader = InputStreamReader(inStream)
        val sb = java.lang.StringBuilder()
        val buf = CharArray(256)
        var bytesRead: Int
        while (r.read(buf).also { bytesRead = it } != -1) {
            sb.append(buf, 0, bytesRead)
        }
        try {
            inStream.close()
        } catch (ignored: IOException) {
        }
        return sb.toString()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeStringToOutputStream(outStream: OutputStream, str: String) {
        outStream.write(str.toByteArray(charset("UTF-8")))
    }
}
