package com.loqmane.glimpse

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class FaceEmbeddingHelper(
    context: Context,
    modelName: String = "facenet.tflite"
) {
    private val TAG = "FaceEmbeddingHelper"
    private val interpreter: Interpreter
    private val modelInputHeight: Int // Actual model's expected H (e.g., 160 for NCHW's H)
    private val modelInputWidth: Int  // Actual model's expected W (e.g., 160 for NCHW's W)
    private val modelInputChannels: Int // Actual model's expected C (e.g., 3 for NCHW's C)
    private val embeddingDim: Int
    private val imageProcessor: ImageProcessor // This will be set to process to HxW
    private val isNCHW: Boolean

    init {
        val modelBuf = FileUtil.loadMappedFile(context, modelName)
        interpreter = Interpreter(modelBuf, Interpreter.Options().apply {
            setNumThreads(4)
        })

        val inT = interpreter.getInputTensor(0)
        val inShape = inT.shape()

        require(inT.dataType() == DataType.FLOAT32) {
            "Model input tensor data type is not FLOAT32, but ${inT.dataType()}"
        }

        // Determine format and dimensions
        if (inShape.size == 4 && inShape[1] == 3 && (inShape[2] > 3 && inShape[3] > 3)) { // Heuristic for NCHW [1, 3, H, W]
            isNCHW = true
            modelInputChannels = inShape[1]
            modelInputHeight = inShape[2]
            modelInputWidth = inShape[3]
        } else if (inShape.size == 4 && inShape[3] == 3 && (inShape[1] > 3 && inShape[2] > 3)) { // Heuristic for NHWC [1, H, W, 3]
            isNCHW = false
            modelInputHeight = inShape[1]
            modelInputWidth = inShape[2]
            modelInputChannels = inShape[3]
        } else {
            isNCHW = false // Default assumption
            modelInputHeight = 160
            modelInputWidth = 160
            modelInputChannels = 3
        }


        val outT = interpreter.getOutputTensor(0)
        val outShape = outT.shape()
        embeddingDim = outShape[1]

        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(modelInputHeight, modelInputWidth, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 127.5f))  // [0,255] → [-1,1]
            .build()
    }

    fun getFaceEmbedding(bitmap: Bitmap): FloatArray? {

        if (bitmap.width == 0 || bitmap.height == 0) {
            Log.e(TAG, "**** DEBUG **** CRITICAL ERROR: Input bitmap has zero width or height!")
            return null
        }

        return try {
            val tensorImg = TensorImage(DataType.FLOAT32)
            tensorImg.load(bitmap)
            Log.d(
                TAG,
                "**** DEBUG **** TensorImage after load(bitmap): W=${tensorImg.width}, H=${tensorImg.height}"
            )

            // ImageProcessor processes to modelInputHeight x modelInputWidth, output is NHWC
            val processedNHWCTensorImage: TensorImage = imageProcessor.process(tensorImg)

            val inputBuf: ByteBuffer
            if (isNCHW) {
                processedNHWCTensorImage.buffer.rewind()
                inputBuf = convertNHWCToNCHW(
                    processedNHWCTensorImage.buffer,
                    modelInputHeight, // Should be 160
                    modelInputWidth,  // Should be 160
                    modelInputChannels // Should be 3
                )
            } else {
                inputBuf = processedNHWCTensorImage.buffer
            }
            inputBuf.rewind() // Ensure buffer is ready for interpreter

            val outputEmbedding = Array(1) { FloatArray(embeddingDim) }
            interpreter.run(inputBuf, outputEmbedding)

            val normalizedEmbedding = l2Normalize(outputEmbedding[0])
            normalizedEmbedding
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts a ByteBuffer with pixel data in NHWC (height, width, channels) order
     * to NCHW (channels, height, width) order.
     * Assumes Float32 data.
     */
    private fun convertNHWCToNCHW(
        nhwcBuffer: ByteBuffer,
        height: Int,
        width: Int,
        channels: Int
    ): ByteBuffer {
        // Make sure the input buffer is at the beginning
        nhwcBuffer.rewind()

        val numElements = height * width
        val nchwByteBuffer =
            ByteBuffer.allocateDirect(numElements * channels * 4) // 4 bytes per float
        nchwByteBuffer.order(ByteOrder.nativeOrder())

        // Create arrays to hold planar data
        val rChannel = FloatArray(numElements)
        val gChannel = FloatArray(numElements)
        val bChannel = FloatArray(numElements)

        // De-interleave from NHWC to separate channel arrays
        for (i in 0 until numElements) {
            rChannel[i] = nhwcBuffer.float
            gChannel[i] = nhwcBuffer.float
            bChannel[i] = nhwcBuffer.float
        }

        // Interleave into NCHW order (plane by plane)
        for (value in rChannel) nchwByteBuffer.putFloat(value)
        for (value in gChannel) nchwByteBuffer.putFloat(value)
        for (value in bChannel) nchwByteBuffer.putFloat(value)

        nchwByteBuffer.rewind() // Prepare for reading
        return nchwByteBuffer
    }


    private fun l2Normalize(embedding: FloatArray): FloatArray {
        var sum = 0f
        for (value in embedding) {
            sum += value * value
        }
        val magnitude = sqrt(sum)

        if (magnitude < 1e-10f) {
            return embedding
        }

        val normalizedEmbedding = FloatArray(embedding.size)
        for (i in embedding.indices) {
            normalizedEmbedding[i] = embedding[i] / magnitude
        }

        var normalizedSum = 0f
        for (value in normalizedEmbedding) {
            normalizedSum += value * value
        }
        return normalizedEmbedding
    }

    fun close() {
        interpreter.close()
    }
}
