package com.limelight.utils

import android.content.Context
import android.net.Uri
import java.io.*

object FileUriUtils {
    @JvmStatic
    fun openUriForRead(context: Context, uri: Uri?): String {
        if (uri == null) return ""
        var inputStream: InputStream? = null
        var reader: Reader? = null
        var bufferedReader: BufferedReader? = null
        val result = StringBuilder()
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                reader = InputStreamReader(inputStream)
                bufferedReader = BufferedReader(reader)
                var temp: String?
                while (bufferedReader.readLine().also { temp = it } != null) {
                    result.append(temp)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                reader?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            try {
                inputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            try {
                bufferedReader?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return result.toString()
    }

    @JvmStatic
    fun openUriForWrite(context: Context, uri: Uri?, content: String): Boolean {
        if (uri == null) return false
        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream != null) {
                outputStream.write(content.toByteArray())
                outputStream.flush()
                outputStream.close()
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    @JvmStatic
    fun writerFileString(file: File, content: String): Boolean {
        var fileOutputStream: FileOutputStream? = null
        try {
            fileOutputStream = FileOutputStream(file)
            fileOutputStream.write(content.toByteArray())
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        return true
    }
}
