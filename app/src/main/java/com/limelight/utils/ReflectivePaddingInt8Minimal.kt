package com.limelight.utils

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.min

object ReflectivePaddingInt8Minimal {

    /**
     * In-place Reflected Padding + Feather + Blur auf ByteBuffer (INT8 RGB)
     * Minimaler Speicher: kein alpha3 Merge, nur 2 temporäre Mats
     */
    @JvmStatic
    fun applyReflectedPadding(buffer: ByteBuffer) {
        val size = 256
        val band = (size * 0.15).toInt() // obere/untere 20%
        val featherPx = 12
        val blurKsize = 7

        // --- ByteBuffer -> Mat (CV_8UC3) ---
        buffer.rewind()
        val arr = ByteArray(size * size * 3)
        buffer.get(arr)
        val mat = Mat(size, size, CvType.CV_8UC3)
        mat.put(0, 0, arr)

        // --- Top-Band ---
        val topBand = mat.submat(band, 2 * band, 0, size)
        var tmp = Mat()
        Core.flip(topBand, tmp, 0)
        Imgproc.GaussianBlur(tmp, tmp, Size(blurKsize.toDouble(), blurKsize.toDouble()), 0.0)
        blendInt8Minimal(mat.submat(0, band, 0, size), tmp, band, featherPx)
        tmp.release()
        topBand.release()

        // --- Bottom-Band ---
        val botBand = mat.submat(size - 2 * band, size - band, 0, size)
        tmp = Mat()
        Core.flip(botBand, tmp, 0)
        Imgproc.GaussianBlur(tmp, tmp, Size(blurKsize.toDouble(), blurKsize.toDouble()), 0.0)
        blendInt8Minimal(mat.submat(size - band, size, 0, size), tmp, band, featherPx)
        tmp.release()
        botBand.release()

        // --- Mat -> ByteBuffer zurück ---
        Core.flip(mat, mat, 0)
        mat.get(0, 0, arr)
        buffer.rewind()
        buffer.put(arr)
        buffer.rewind()
        mat.release()
    }

    /**
     * INT8 Alpha-Blend ohne Merge: dst = (alpha*padded + (255-alpha)*dst)/255
     * alpha linear von 0-255 über band Pixel
     */
    private fun blendInt8Minimal(dst: Mat, padded: Mat, band: Int, featherPx: Int) {
        val width = dst.cols()
        val channels = dst.channels()
        val dstRow = ByteArray(width * channels)
        val padRow = ByteArray(width * channels)

        for (y in 0 until band) {
            val alpha = min(255, (y * 255) / featherPx)
            val invAlpha = 255 - alpha

            dst.get(y, 0, dstRow)
            padded.get(y, 0, padRow)

            for (i in dstRow.indices) {
                val pVal = padRow[i].toInt() and 0xFF
                val dVal = dstRow[i].toInt() and 0xFF
                val v = (alpha * pVal + invAlpha * dVal) / 255
                dstRow[i] = v.toByte()
            }

            dst.put(y, 0, dstRow)
        }
    }
}
