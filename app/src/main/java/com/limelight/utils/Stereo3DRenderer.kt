package com.limelight.utils

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.Log
import android.view.Surface
import com.limelight.LimeLog
import com.limelight.preferences.PreferenceConfiguration
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Stereo3DRenderer(
    private val glSurfaceView: GLSurfaceView,
    private val onSurfaceReadyListener: OnSurfaceReadyListener?,
    private val context: Context,
    private var prefConfig: PreferenceConfiguration?
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    interface OnSurfaceReadyListener {
        fun onStereo3DSurfaceReady(surface: Surface)
    }

    companion object {
        private const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
        private val QUAD_VERTICES = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
        private val TEXTURE_VERTICES = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f)
        private const val AI_MODEL = "midas-midas-v2-w8a8.tflite"
        private const val modelInputHeight = 256
        private const val modelInputWidth = 256
        private const val NUM_BUFFERS = 6
        private const val NUM_INPUT_BUFFERS = 10
        private const val NUM_SMOOTHED_BUFFERS = 3

        @JvmField
        var isMovieMode = true

        @JvmField
        @Volatile
        var fps: Float = 0f

        @JvmField
        @Volatile
        var threeDFps: Float = 0f

        @JvmField
        @Volatile
        var drawDelay: Float = 0.0f

        @JvmField
        var isDebugMode: Boolean = false

        @JvmField
        var isActive: Boolean = false

        @JvmField
        var renderer: String = "CPU"

        private var calcFps: Float = 0f
        private var depthMapResultCount: Int = 0
        private var calcThreeDFps: Float = 0f

        init {
            if (!OpenCVLoader.initLocal()) {
                LimeLog.severe("Internal OpenCV library not found. Using OpenCV Manager for initialization")
            } else {
                LimeLog.info("OpenCV library found inside package. Using it!")
            }
        }

        @JvmStatic
        fun convertRgbaToRgb(rgbaBuffer: ByteBuffer, rgbBuffer: ByteBuffer, width: Int, height: Int) {
            var rgbaMat: Mat? = null
            var rgbMat: Mat? = null
            try {
                rgbaMat = Mat(height, width, CvType.CV_8UC4, rgbaBuffer)
                rgbMat = Mat(height, width, CvType.CV_8UC3, rgbBuffer)
                Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            } finally {
                rgbaMat?.release()
                rgbMat?.release()
            }
        }

        @JvmStatic
        fun calculateAverageDifferenceOCV(buffer1: ByteBuffer?, buffer2: ByteBuffer?, width: Int, height: Int): Double {
            if (buffer1 == null || buffer2 == null) {
                return 1.0
            }

            var mat1: Mat? = null
            var mat2: Mat? = null
            var diffMat: Mat? = null
            try {
                mat1 = Mat(height, width, CvType.CV_8UC1, buffer1)
                mat2 = Mat(height, width, CvType.CV_8UC1, buffer2)
                diffMat = Mat()
                Core.absdiff(mat1, mat2, diffMat)
                val meanDifference = Core.mean(diffMat)
                return meanDifference.`val`[0] / 255.0
            } finally {
                mat1?.release()
                mat2?.release()
                diffMat?.release()
            }
        }
    }

    private val pboHandles = IntArray(2)
    private var pboIndex = 0

    private var PBO_SIZE = modelInputWidth * modelInputHeight * 4

    private val frameLock = java.lang.Object()
    private val quadVertexBuffer: FloatBuffer
    private val textureVertexBuffer: FloatBuffer
    private val frameAvailable = AtomicBoolean(false)
    private val gpuDelegateFailed = AtomicBoolean(false)
    private val isAiResultHandlingRunning = AtomicBoolean(false)
    private val isAiRunning = AtomicBoolean(false)

    private var bilateralBlurProgram = 0
    private var depthMapTextureId = 0
    private var dibr3dProgram = 0
    private val latestDepthMap = AtomicReference<ByteBuffer?>(null)
    private var fboHandle = 0
    private var fboTextureId = 0
    private var filterFboHandle = 0
    private var filteredDepthMapTextureId = 0
    private var intermediateFboHandle = 0
    private var intermediateTextureId = 0
    private var simple3dProgram = 0
    private var videoTextureId = 0

    private var gpuDelegate: GpuDelegate? = null
    private var tflite: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var tfliteInputBuffer: ByteBuffer? = null

    private var totalDrawTime: Long = 0
    private var lastFpsTime: Long = 0
    private var previousFrameForComparison: ByteBuffer? = null
    private var currentlyRenderingMap: ByteBuffer? = null
    private var executorService: ExecutorService? = null
    private var filledOutputBuffers: BlockingQueue<InferenceResult>? = null
    private var freeInputBuffers: BlockingQueue<ByteBuffer>? = null
    private var freeOutputBuffers: BlockingQueue<ByteBuffer>? = null
    private var freeSmoothedBuffers: BlockingQueue<ByteBuffer>? = null
    private var inferenceInputQueue: BlockingQueue<RenderResult> = ArrayBlockingQueue(1)
    private var previousPixelBuffer: ByteBuffer? = null
    private var videoSurface: Surface? = null
    private var videoSurfaceTexture: SurfaceTexture? = null

    private val ON_DRAW_CHANGE_TRESHOLD = 2.0f
    @Volatile
    private var block = false

    init {
        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadVertexBuffer.put(QUAD_VERTICES).position(0)
        textureVertexBuffer = ByteBuffer.allocateDirect(TEXTURE_VERTICES.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        textureVertexBuffer.put(TEXTURE_VERTICES).position(0)
    }

    fun setPrefConfig(prefConfig: PreferenceConfiguration?) {
        this.prefConfig = prefConfig
    }

    fun onSurfaceDestroyed() {
        LimeLog.info("Quit called. Shutting down 3dRenderer.")
        executorService?.let {
            it.shutdownNow()
            try {
                if (!it.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    LimeLog.warning("Thread pool did not terminate in time.")
                }
            } catch (e: InterruptedException) {
                it.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        tflite?.close()
        tflite = null
        gpuDelegate?.close()
        gpuDelegate = null
        nnApiDelegate?.close()
        nnApiDelegate = null

        videoSurface?.release()
        videoSurface = null
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null

        glSurfaceView.queueEvent {
            GLES20.glDeleteProgram(simple3dProgram)
            GLES20.glDeleteProgram(bilateralBlurProgram)
            GLES20.glDeleteProgram(dibr3dProgram)

            val textures = intArrayOf(
                videoTextureId,
                depthMapTextureId,
                filteredDepthMapTextureId,
                fboTextureId,
                intermediateTextureId
            )
            GLES20.glDeleteTextures(textures.size, textures, 0)

            val fbos = intArrayOf(fboHandle, intermediateFboHandle, filterFboHandle)
            GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)
        }

        filledOutputBuffers?.clear()
        previousPixelBuffer = null
        currentlyRenderingMap = null
        prefConfig = null
        drawDelay = 0.0f
        calcFps = 0f
        calcThreeDFps = 0.0f
        renderer = "CPU"
        isActive = false
    }

    fun getVideoSurface(): Surface? {
        return videoSurface
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(frameLock) {
            frameAvailable.set(true)
        }
        glSurfaceView.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        videoTextureId = createExternalOESTexture()
        videoSurfaceTexture = SurfaceTexture(videoTextureId)
        videoSurfaceTexture!!.setOnFrameAvailableListener(this)
        videoSurface = Surface(videoSurfaceTexture)

        depthMapTextureId = createEmptyTexture(modelInputWidth, modelInputHeight)

        simple3dProgram = createProgram(ShaderUtils.SIMPLE_VERTEX_SHADER, ShaderUtils.SIMPLE_FRAGMENT_SHADER)
        bilateralBlurProgram = createProgram(ShaderUtils.VERTEX_SHADER, ShaderUtils.OPTIMIZED_SINGLE_PASS_GAUSSIAN_BLUR_SHADER)
        dibr3dProgram = createProgram(ShaderUtils.VERTEX_SHADER, ShaderUtils.FRAGMENT_SHADER_3D)

        initializeFilterFbo()
        initializeIntermediateFbo()
        initializeTfLite()
        initializeFbo()
        initBuffer()
        initializePBOs()

        val mapSize = modelInputWidth * modelInputHeight
        freeSmoothedBuffers = ArrayBlockingQueue(NUM_SMOOTHED_BUFFERS)
        for (i in 0 until NUM_SMOOTHED_BUFFERS) {
            freeSmoothedBuffers!!.offer(ByteBuffer.allocateDirect(mapSize).order(ByteOrder.nativeOrder()))
        }

        val pboSize = modelInputWidth * modelInputHeight * 4
        previousPixelBuffer = ByteBuffer.allocateDirect(pboSize).order(ByteOrder.nativeOrder())
        previousFrameForComparison = ByteBuffer.allocateDirect(pboSize).order(ByteOrder.nativeOrder())
        val inputPixelSize = modelInputWidth * modelInputHeight * 4
        freeInputBuffers = ArrayBlockingQueue(NUM_INPUT_BUFFERS)
        inferenceInputQueue = ArrayBlockingQueue(1)
        for (i in 0 until NUM_INPUT_BUFFERS) {
            freeInputBuffers!!.offer(ByteBuffer.allocateDirect(inputPixelSize).order(ByteOrder.nativeOrder()))
        }

        executorService = Executors.newFixedThreadPool(2)

        onSurfaceReadyListener?.onStereo3DSurfaceReady(videoSurface!!)

        if (!isAiResultHandlingRunning.get()) {
            isAiResultHandlingRunning.set(true)
            executorService!!.submit(AiResultHandling())
        }
        if (!isAiRunning.get()) {
            isAiRunning.set(true)
            executorService!!.submit(AiTask())
        }
        isActive = true
    }

    private fun initializeIntermediateFbo() {
        intermediateTextureId = createRgbaTexture(modelInputWidth, modelInputHeight)
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        intermediateFboHandle = fbos[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, intermediateTextureId, 0)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.warning("Intermediate Framebuffer is not complete.")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun getParallax(): Float {
        return (prefConfig?.parallax_depth ?: 0f) * 0.7f
    }

    private fun applyTwoPassGaussianBlur() {
        val blurProgram = bilateralBlurProgram

        GLES20.glUseProgram(blurProgram)

        val posHandle = GLES20.glGetAttribLocation(blurProgram, "a_Position")
        val texHandle = GLES20.glGetAttribLocation(blurProgram, "a_TexCoord")
        val inputTextureHandle = GLES20.glGetUniformLocation(blurProgram, "s_InputTexture")
        val texelSizeHandle = GLES20.glGetUniformLocation(blurProgram, "u_texelSize")
        val directionHandle = GLES20.glGetUniformLocation(blurProgram, "u_blurDirection")
        val parallaxHandle = GLES20.glGetUniformLocation(blurProgram, "u_parallax")
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glUniform1f(parallaxHandle, getParallax())

        GLES20.glUniform2f(texelSizeHandle, 1.0f / modelInputWidth, 1.0f / modelInputHeight)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle)
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight)

        GLES20.glUniform2f(directionHandle, 1.0f, 0.0f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId)
        GLES20.glUniform1i(inputTextureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle)
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight)

        GLES20.glUniform2f(directionHandle, 0.0f, 1.0f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTextureId)
        GLES20.glUniform1i(inputTextureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun drawBothEyes(dualBubble3dProgram: Int, convergence: Float, shift: Float) {
        val viewWidth = glSurfaceView.width
        val viewHeight = glSurfaceView.height

        val parallax = getParallax() * 0.06f

        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight)
        drawEye(dualBubble3dProgram, -parallax, convergence, shift)

        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight)
        drawEye(dualBubble3dProgram, parallax, convergence, shift)
    }

    private fun drawEye(program: Int, parallax: Float, convergence: Float, shift: Float) {
        GLES20.glUseProgram(program)
        val posHandle = GLES20.glGetAttribLocation(program, "a_Position")
        val texHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        val colorTexHandle = GLES20.glGetUniformLocation(program, "s_ColorTexture")
        val depthTexHandle = GLES20.glGetUniformLocation(program, "s_DepthTexture")
        val parallaxHandle = GLES20.glGetUniformLocation(program, "u_parallax")
        val convergenceHandle = GLES20.glGetUniformLocation(program, "u_convergence")
        val shiftHandle = GLES20.glGetUniformLocation(program, "u_shift")
        val debugModeHandle = GLES20.glGetUniformLocation(program, "u_debugMode")

        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glEnableVertexAttribArray(texHandle)

        GLES20.glUniform1i(debugModeHandle, if (isDebugMode) 1 else 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(colorTexHandle, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId)
        GLES20.glUniform1i(depthTexHandle, 1)
        GLES20.glUniform1f(parallaxHandle, parallax)
        GLES20.glUniform1f(convergenceHandle, convergence)
        GLES20.glUniform1f(shiftHandle, shift)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawWithShader() {
        prefConfig?.let {
            drawBothEyes(dibr3dProgram, it.convergence_ratio, it.balance_shift)
        }
    }

    private fun initBuffer() {
        if (tflite != null) {
            val inputSize = modelInputHeight * modelInputWidth * 3
            tfliteInputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
            val outputSize = modelInputHeight * modelInputWidth
            freeOutputBuffers = ArrayBlockingQueue(NUM_BUFFERS)
            filledOutputBuffers = ArrayBlockingQueue(NUM_BUFFERS)
            for (i in 0 until NUM_BUFFERS) {
                freeOutputBuffers!!.offer(ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder()))
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val startTime = System.nanoTime()

        synchronized(frameLock) {
            if (!frameAvailable.get()) {
                if (!isMovieMode) {
                    glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                } else {
                    glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    return
                }
            } else if (isMovieMode) {
                block = true
            }
            frameAvailable.set(false)
        }
        try {
            videoSurfaceTexture?.updateTexImage()
        } catch (e: Exception) {
            Log.w("Stereo3DRenderer", "updateTexImagse failed", e)
            return
        }

        currentlyRenderingMap?.let {
            freeSmoothedBuffers?.offer(it)
        }

        val startTimeAi = System.nanoTime()
        var endTimeAi = System.nanoTime()
        if (tflite != null) {
            if (block || !isMovieMode) {
                val pixelBufferForAI = freeInputBuffers?.poll()
                if (pixelBufferForAI != null) {
                    val success = readPixelsForAI(pixelBufferForAI)
                    if (success) {
                        val difference = hasSceneChangedFast(pixelBufferForAI, previousFrameForComparison)
                        pixelBufferForAI.rewind()
                        previousFrameForComparison?.rewind()
                        previousFrameForComparison?.put(pixelBufferForAI)

                        if (inferenceInputQueue.offer(RenderResult(pixelBufferForAI, difference))) {
                            Log.d("AiTask", "Success: The AI will now process this buffer.")
                        } else {
                            freeInputBuffers?.offer(pixelBufferForAI)
                        }
                    } else {
                        freeInputBuffers?.offer(pixelBufferForAI)
                    }
                }
                var newMap: ByteBuffer? = null
                if (block && isMovieMode) {
                    while (latestDepthMap.getAndSet(null).also { newMap = it } == null) {
                        try {
                            Thread.sleep(1)
                        } catch (e: InterruptedException) {
                        }
                    }
                } else {
                    newMap = latestDepthMap.getAndSet(null)
                }
                if (newMap != null) {
                    block = false
                    currentlyRenderingMap = newMap
                    depthMapResultCount++
                    endTimeAi = System.nanoTime()
                    Log.d("Stereo3DRenderer", "DepthMap OutputSpeed " + (endTimeAi - startTimeAi) / 1_000_000 + " ms")
                }
            }

            currentlyRenderingMap?.let {
                uploadLatestDepthMapToGpu(it)
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            applyTwoPassGaussianBlur()
            drawWithShader()
            val endTime = System.nanoTime()

            if (lastFpsTime == 0L) {
                lastFpsTime = startTime
            }
            totalDrawTime += (endTime - lastFpsTime)

            if (endTime - lastFpsTime >= 1_000_000_000) {
                if (fps > 0) {
                    drawDelay = (totalDrawTime.toFloat() / fps / 1000000000f)
                }
                totalDrawTime = 0
                fps = calcFps
                calcFps = 0f
                depthMapResultCount = 0
                threeDFps = calcThreeDFps
                calcThreeDFps = 0f
                lastFpsTime = endTime
                val freeInputCap = (freeInputBuffers?.size ?: 0) + (freeInputBuffers?.remainingCapacity() ?: 0)
                val aiInCap = inferenceInputQueue.size + inferenceInputQueue.remainingCapacity()
                val aiOutCap = (filledOutputBuffers?.size ?: 0) + (filledOutputBuffers?.remainingCapacity() ?: 0)
                val freeSmoothCap = (freeSmoothedBuffers?.size ?: 0) + (freeSmoothedBuffers?.remainingCapacity() ?: 0)

                val queueStatus = String.format(
                    "Queues (Free/Cap) | FreeInput: %d/%d, To_AI: %d/%d, From_AI: %d/%d, Free_Smooth: %d/%d",
                    freeInputBuffers?.remainingCapacity() ?: 0, freeInputCap,
                    inferenceInputQueue.remainingCapacity(), aiInCap,
                    filledOutputBuffers?.remainingCapacity() ?: 0, aiOutCap,
                    freeSmoothedBuffers?.remainingCapacity() ?: 0, freeSmoothCap
                )
                Log.d("Stereo3DRenderer", queueStatus)
            } else {
                calcFps++
            }
        }
    }

    private fun createFlatDepthMap(): ByteBuffer {
        val mapSize = modelInputWidth * modelInputHeight
        val flatData = ByteArray(mapSize) { 128.toByte() }

        val flatMap = ByteBuffer.allocateDirect(mapSize).order(ByteOrder.nativeOrder())
        flatMap.put(flatData)
        flatMap.rewind()
        return flatMap
    }

    private fun uploadLatestDepthMapToGpu(depthMap: ByteBuffer?) {
        if (depthMap != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, modelInputWidth, modelInputHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, depthMap)
        }
    }

    private fun drawQuad(program: Int, scale: Float, offset: Float) {
        GLES20.glUseProgram(program)

        val posHandle = GLES20.glGetAttribLocation(program, "a_Position")
        val texHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        val offsetHandle = GLES20.glGetUniformLocation(program, "u_xOffset")
        val scaleHandle = GLES20.glGetUniformLocation(program, "u_xScale")

        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glEnableVertexAttribArray(texHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId)

        if (scaleHandle != -1) GLES20.glUniform1f(scaleHandle, scale)
        if (offsetHandle != -1) GLES20.glUniform1f(offsetHandle, offset)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private class InferenceResult(val pixelBuffer: ByteBuffer, val rawDepthBuffer: ByteBuffer)
    private class RenderResult(val pixelBuffer: ByteBuffer, val imageDifference: Double)

    private fun initializePBOs() {
        PBO_SIZE = modelInputWidth * modelInputHeight * 4

        GLES30.glGenBuffers(2, pboHandles, 0)

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[0])
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PBO_SIZE, null, GLES30.GL_DYNAMIC_READ)

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[1])
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PBO_SIZE, null, GLES30.GL_DYNAMIC_READ)

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    private fun readPixelsForAI(destinationBuffer: ByteBuffer): Boolean {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle)
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight)
        drawQuad(simple3dProgram, 1.0f, 0.0f)
        destinationBuffer.rewind()

        GLES20.glReadPixels(0, 0, modelInputWidth, modelInputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, destinationBuffer)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return true
    }

    private fun readPixelsForAI_Async(destinationBuffer: ByteBuffer): Boolean {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle)
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight)
        drawQuad(simple3dProgram, 1.0f, 0.0f)
        val writeIndex = pboIndex
        val readIndex = (pboIndex + 1) % 2
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[writeIndex])
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            GLES30.glReadPixels(0, 0, modelInputWidth, modelInputHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[readIndex])
        val mappedBuffer = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, PBO_SIZE, GLES30.GL_MAP_READ_BIT
        ) as ByteBuffer?
        var success = false
        if (mappedBuffer != null) {
            destinationBuffer.rewind()
            mappedBuffer.rewind()
            destinationBuffer.put(mappedBuffer)
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            success = true
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        pboIndex = readIndex
        return success
    }

    private fun initializeTfLite() {
        val options = Interpreter.Options()

        try {
            val gpuOptions = GpuDelegate.Options()
            gpuOptions.setQuantizedModelsAllowed(true)
            gpuOptions.setPrecisionLossAllowed(true)
            gpuOptions.setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            gpuDelegate = GpuDelegate(gpuOptions)
            options.addDelegate(gpuDelegate)
            LimeLog.info("GPU Delegate aktiviert")
            renderer = "GPU"
            tflite = Interpreter(loadModelFile(context, AI_MODEL)!!, options)
        } catch (e: Exception) {
            LimeLog.info("GPU Delegate nicht verfügbar: " + e.message)
            gpuDelegate?.close()
            try {
                nnApiDelegate = NnApiDelegate()
                options.addDelegate(nnApiDelegate)
                tflite = Interpreter(loadModelFile(context, AI_MODEL)!!, options)
                LimeLog.info("NNAPI Delegate aktiviert")
                renderer = "NNAPI"
            } catch (exception: Exception) {
                LimeLog.info("NNAPI Delegate nicht verfügbar: " + e.message)
                nnApiDelegate?.close()
                try {
                    LimeLog.info("Fallback: CPU")
                    tflite = Interpreter(loadModelFile(context, AI_MODEL)!!, options)
                    renderer = "CPU"
                } catch (ex: Exception) {
                    reinitializeTfLiteOnCpu()
                }
            }
        }
    }

    private fun reinitializeTfLiteOnCpu() {
        tflite?.close()
        tflite = null
        gpuDelegate?.close()
        gpuDelegate = null

        try {
            val options = Interpreter.Options()
            options.setUseNNAPI(true)
            options.setNumThreads(4)
            tflite = Interpreter(loadModelFile(context, AI_MODEL)!!, options)
            LimeLog.info("Successfully re-initialized TFLite interpreter on CPU.")
        } catch (e: IOException) {
            LimeLog.severe("Failed to re-initialize TFLite model on CPU: " + e.message)
        }
    }

    @Throws(IOException::class)
    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer? {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    private fun initializeFbo() {
        fboTextureId = createRgbaTexture(modelInputWidth, modelInputHeight)
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboHandle = fbos[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId, 0)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.severe("Framebuffer is not complete.")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun initializeFilterFbo() {
        filteredDepthMapTextureId = createRgbaTexture(modelInputWidth, modelInputHeight)
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        filterFboHandle = fbos[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId, 0)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.severe("Filter Framebuffer is not complete.")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun createExternalOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        return textureId
    }

    private fun createEmptyTexture(width: Int, height: Int): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        return textureId
    }

    private fun createRgbaTexture(width: Int, height: Int): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        return textureId
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        var shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            LimeLog.severe("Could not compile shader " + type + ":")
            LimeLog.severe(GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (fragmentShader == 0) return 0

        var program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                LimeLog.severe("Could not link program: ")
                LimeLog.severe(GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }
        return program
    }

    private fun hasFrameChangedSignificantlyOCV(newPixelBuffer: ByteBuffer?, oldPixelBuffer: ByteBuffer?): Double {
        if (newPixelBuffer == null || oldPixelBuffer == null || newPixelBuffer.capacity() != oldPixelBuffer.capacity()) {
            return 1.0 // maximal unterschiedliche Frames
        }

        var mat1: Mat? = null
        var mat2: Mat? = null
        var gray1: Mat? = null
        var gray2: Mat? = null
        var edges1: Mat? = null
        var edges2: Mat? = null
        var histGray1: Mat? = null
        var histGray2: Mat? = null
        var histEdge1: Mat? = null
        var histEdge2: Mat? = null

        try {
            mat1 = Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, newPixelBuffer)
            mat2 = Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, oldPixelBuffer)

            gray1 = Mat()
            gray2 = Mat()
            Imgproc.cvtColor(mat1, gray1, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(mat2, gray2, Imgproc.COLOR_RGBA2GRAY)

            edges1 = Mat()
            edges2 = Mat()
            val gradX1 = Mat()
            val gradY1 = Mat()
            val gradX2 = Mat()
            val gradY2 = Mat()
            Imgproc.Sobel(gray1, gradX1, CvType.CV_16S, 1, 0)
            Imgproc.Sobel(gray1, gradY1, CvType.CV_16S, 0, 1)
            Core.convertScaleAbs(gradX1, gradX1)
            Core.convertScaleAbs(gradY1, gradY1)
            Core.addWeighted(gradX1, 0.5, gradY1, 0.5, 0.0, edges1)

            Imgproc.Sobel(gray2, gradX2, CvType.CV_16S, 1, 0)
            Imgproc.Sobel(gray2, gradY2, CvType.CV_16S, 0, 1)
            Core.convertScaleAbs(gradX2, gradX2)
            Core.convertScaleAbs(gradY2, gradY2)
            Core.addWeighted(gradX2, 0.5, gradY2, 0.5, 0.0, edges2)

            gradX1.release()
            gradY1.release()
            gradX2.release()
            gradY2.release()

            histGray1 = Mat()
            histGray2 = Mat()
            Imgproc.calcHist(listOf(gray1), MatOfInt(0), Mat(), histGray1, MatOfInt(256), MatOfFloat(0f, 256f))
            Imgproc.calcHist(listOf(gray2), MatOfInt(0), Mat(), histGray2, MatOfInt(256), MatOfFloat(0f, 256f))

            histEdge1 = Mat()
            histEdge2 = Mat()
            Imgproc.calcHist(listOf(edges1), MatOfInt(0), Mat(), histEdge1, MatOfInt(256), MatOfFloat(0f, 256f))
            Imgproc.calcHist(listOf(edges2), MatOfInt(0), Mat(), histEdge2, MatOfInt(256), MatOfFloat(0f, 256f))

            val grayDiff = 1.0 - Imgproc.compareHist(histGray1, histGray2, Imgproc.HISTCMP_CORREL)
            val edgeDiff = 1.0 - Imgproc.compareHist(histEdge1, histEdge2, Imgproc.HISTCMP_CORREL)

            return 0.5 * grayDiff + 0.5 * edgeDiff

        } finally {
            mat1?.release()
            mat2?.release()
            gray1?.release()
            gray2?.release()
            edges1?.release()
            edges2?.release()
            histGray1?.release()
            histGray2?.release()
            histEdge1?.release()
            histEdge2?.release()
        }
    }

    private fun hasSceneChangedFast(currentFrame: ByteBuffer?, previousFrame: ByteBuffer?): Double {
        if (currentFrame == null || previousFrame == null || currentFrame.capacity() != previousFrame.capacity()) {
            return 0.0
        }

        currentFrame.rewind()
        previousFrame.rewind()

        var totalDifference: Long = 0
        var pixelsSampled = 0

        val PIXEL_STRIDE = 4
        val PIXEL_SAMPLE_RATE = 32
        val ROW_SAMPLE_RATE = 32
        val SAMPLE_STRIDE = PIXEL_STRIDE * PIXEL_SAMPLE_RATE
        val ROW_STRIDE = modelInputWidth * PIXEL_STRIDE * ROW_SAMPLE_RATE

        var row = 0
        while (row < currentFrame.capacity()) {
            var col = 0
            while (col < modelInputWidth * PIXEL_STRIDE) {
                val index = row + col
                if (index + 2 >= currentFrame.capacity()) break

                totalDifference += Math.abs((currentFrame.get(index).toInt() and 0xFF) - (previousFrame.get(index).toInt() and 0xFF)).toLong()
                totalDifference += Math.abs((currentFrame.get(index + 1).toInt() and 0xFF) - (previousFrame.get(index + 1).toInt() and 0xFF)).toLong()
                totalDifference += Math.abs((currentFrame.get(index + 2).toInt() and 0xFF) - (previousFrame.get(index + 2).toInt() and 0xFF)).toLong()
                pixelsSampled++
                col += SAMPLE_STRIDE
            }
            row += ROW_STRIDE
        }

        if (pixelsSampled == 0) return 0.0

        return totalDifference.toDouble() / pixelsSampled
    }

    private inner class AiTask : Runnable {
        private var previousRawMap: ByteBuffer? = null

        override fun run() {
            var pixelBuffer: ByteBuffer? = null
            var difference = 0.0
            while (!Thread.currentThread().isInterrupted) {
                val startTime = System.nanoTime()
                var waitTime = System.nanoTime()
                var aiTime = System.nanoTime()
                var aiTime_end = System.nanoTime()
                try {
                    if (tflite == null) return
                    val result = inferenceInputQueue.take()
                    pixelBuffer = result.pixelBuffer
                    difference = result.imageDifference
                    val outputBuffer = freeOutputBuffers!!.take()
                    waitTime = System.nanoTime()
                    outputBuffer.rewind()

                    if (difference > ON_DRAW_CHANGE_TRESHOLD || previousRawMap == null) {
                        tfliteInputBuffer!!.rewind()
                        pixelBuffer.rewind()

                        convertRgbaToRgb(pixelBuffer, tfliteInputBuffer!!, modelInputWidth, modelInputHeight)

                        aiTime = System.nanoTime()
                        ReflectivePaddingInt8Minimal.applyReflectedPadding(tfliteInputBuffer!!)
                        tflite!!.run(tfliteInputBuffer!!, outputBuffer)
                        if (previousRawMap == null) {
                            previousRawMap = ByteBuffer.allocateDirect(outputBuffer.capacity())
                        }
                        previousRawMap!!.clear()
                        outputBuffer.rewind()
                        previousRawMap!!.put(outputBuffer)
                        previousRawMap!!.rewind()
                    } else {
                        outputBuffer.clear()
                        previousRawMap!!.rewind()
                        outputBuffer.put(previousRawMap!!)
                        outputBuffer.rewind()
                    }
                    calcThreeDFps++
                    aiTime_end = System.nanoTime()
                    filledOutputBuffers!!.put(InferenceResult(pixelBuffer, outputBuffer))
                    pixelBuffer = null
                } catch (e: InterruptedException) {
                    LimeLog.severe("AI inference failed: " + e.message)
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    LimeLog.severe("AI inference failed: " + e.message)
                    gpuDelegateFailed.set(true)
                } finally {
                    val duration = (System.nanoTime() - startTime) / 1_000_000
                    val waitTimeText = (waitTime - startTime) / 1_000_000
                    val aitimeText = (aiTime_end - aiTime) / 1_000_000
                    if (pixelBuffer != null) {
                        freeInputBuffers!!.offer(pixelBuffer)
                    }
                    Log.d("Stereo3DRenderer", "CalculateTime AiDepthMap: $duration ms ${filledOutputBuffers!!.remainingCapacity()} $waitTimeText ms aitime: $aitimeText")
                }
            }
            isAiRunning.set(false)
        }
    }

    private inner class AiResultHandling : Runnable {
        private val IMAGE_DIFFERENCE_MULTIPLIER = 100.0
        private val MAX_SMOOTHING_FACTOR = 1.0
        private val MIN_SMOOTHING_FACTOR = 0.005

        private val processedDataArray = ByteArray(modelInputWidth * modelInputHeight)
        private var previousSmoothedMat: Mat? = null
        private var isFirstFrame = true

        override fun run() {
            var resultBuffer = createFlatDepthMap()
            var result: InferenceResult? = null

            while (!Thread.currentThread().isInterrupted) {
                val startTime = System.nanoTime()
                var waitTime = System.nanoTime()
                var rawMat: Mat? = null
                var processedMat: Mat? = null
                try {
                    result = filledOutputBuffers!!.take()
                    resultBuffer = freeSmoothedBuffers!!.take()
                    waitTime = System.nanoTime()

                    var intermediate: InferenceResult?
                    while (filledOutputBuffers!!.poll().also { intermediate = it } != null) {
                        freeInputBuffers!!.offer(result!!.pixelBuffer)
                        freeOutputBuffers!!.offer(result!!.rawDepthBuffer)
                        result = intermediate
                    }
                    val rawDepthBuffer = result!!.rawDepthBuffer
                    val currentPixelBuffer = result!!.pixelBuffer

                    currentPixelBuffer.rewind()
                    val imageDifference = hasFrameChangedSignificantlyOCV(currentPixelBuffer, previousPixelBuffer) * IMAGE_DIFFERENCE_MULTIPLIER

                    rawMat = Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC1, rawDepthBuffer)
                    processedMat = Mat()
                    Core.normalize(rawMat, processedMat, 0.0, 255.0, Core.NORM_MINMAX)

                    if (isFirstFrame) {
                        previousSmoothedMat = processedMat.clone()
                        isFirstFrame = false
                    }

                    var smoothing = (imageDifference * 10) / (threeDFps * 3)
                    smoothing = Math.min(smoothing, MAX_SMOOTHING_FACTOR)
                    smoothing = Math.max(smoothing, MIN_SMOOTHING_FACTOR)
                    val diff = Mat()
                    Core.absdiff(processedMat, previousSmoothedMat, diff)
                    val mmr = Core.minMaxLoc(diff)
                    val thresholdValue = Math.max(1.0, mmr.maxVal * (1.0 - smoothing) * 0.1)
                    val validMask = Mat()
                    Imgproc.threshold(diff, validMask, thresholdValue, 255.0, Imgproc.THRESH_BINARY_INV)
                    processedMat.copyTo(previousSmoothedMat, validMask)
                    val blended = Mat()
                    Core.addWeighted(processedMat, smoothing, previousSmoothedMat, 1.0 - smoothing, 0.0, blended)
                    val inverseMask = Mat()
                    Core.bitwise_not(validMask, inverseMask)
                    blended.copyTo(previousSmoothedMat, inverseMask)
                    diff.release()
                    validMask.release()
                    inverseMask.release()
                    blended.release()
                    previousSmoothedMat!!.get(0, 0, processedDataArray)
                    rawDepthBuffer.rewind()
                    rawDepthBuffer.put(processedDataArray)

                    rawDepthBuffer.rewind()
                    resultBuffer.rewind()
                    resultBuffer.put(rawDepthBuffer)

                    rawDepthBuffer.rewind()
                    resultBuffer.rewind()
                    latestDepthMap.set(resultBuffer)

                    previousPixelBuffer!!.rewind()
                    previousPixelBuffer!!.put(currentPixelBuffer)
                } catch (e: Exception) {
                    LimeLog.severe("AI exception " + e.message)
                } finally {
                    rawMat?.release()
                    processedMat?.release()
                    if (resultBuffer != null) {
                        freeSmoothedBuffers!!.offer(resultBuffer)
                    }
                    if (result != null) {
                        freeInputBuffers!!.offer(result!!.pixelBuffer)
                        freeOutputBuffers!!.offer(result!!.rawDepthBuffer)
                    }
                    val duration = (System.nanoTime() - startTime) / 1_000_000
                    val waitTimeText = (waitTime - startTime) / 1_000_000
                    Log.d("Stereo3DRenderer", "CalculateTime AiResult:    $duration ms ${freeOutputBuffers!!.remainingCapacity()} $waitTimeText ms ")
                }
            }
            isAiResultHandlingRunning.set(false)
        }
    }
}
