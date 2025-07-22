package com.loqmane.glimpse

import android.annotation.SuppressLint
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

const val MODEL_INPUT_WIDTH = 160
const val MODEL_INPUT_HEIGHT = 160


// Converts an ImageProxy (typically from CameraX in YUV_420_888 format) to a Bitmap.
@SuppressLint("UnsafeOptInUsageError")
fun ImageProxy.toBitmap(): Bitmap? {
    if (image == null) return null // Should not happen with a valid ImageProxy

    if (format != ImageFormat.YUV_420_888) {
        Log.e(
            "ImageProxyToBitmap",
            "Unsupported image format: $format. Only YUV_420_888 is supported."
        )
        // For simplicity, this example only handles YUV_420_888.
        return null
    }

    val yBuffer = planes[0].buffer // Y plane
    val uBuffer = planes[1].buffer // U plane
    val vBuffer = planes[2].buffer // V plane

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)


    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(
        Rect(0, 0, yuvImage.width, yuvImage.height),
        100,
        out
    ) // 100 for best quality
    val imageBytes = out.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

    // Rotate the bitmap if necessary, based on the ImageProxy's rotationDegrees
    val rotationDegrees = this.imageInfo.rotationDegrees
    if (rotationDegrees == 0) return bitmap

    val matrix = Matrix()
    matrix.postRotate(rotationDegrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * Crops a face from a given Bitmap using a bounding box, adds padding, and resizes to the model's input size.
 * This is the single source of truth for face cropping in the app.
 */
fun cropFaceBitmap(bitmap: Bitmap, boundingBox: Rect): Bitmap {
    // Add padding around the face for better recognition
    val padding = (boundingBox.width() * 0.2).toInt() // 20% padding

    // Calculate the bounds with padding, ensuring we don't go out of the bitmap boundaries
    val left = (boundingBox.left - padding).coerceAtLeast(0)
    val top = (boundingBox.top - padding).coerceAtLeast(0)
    val right = (boundingBox.right + padding).coerceAtMost(bitmap.width)
    val bottom = (boundingBox.bottom + padding).coerceAtMost(bitmap.height)
    val width = right - left
    val height = bottom - top

    // Ensure width and height are positive for the initial crop
    if (width <= 0 || height <= 0) {
        Log.w(
            "CropFace",
            "Cannot crop face, invalid bounding box dimensions after clamping. cropWidth: $width, cropHeight: $height. Returning placeholder."
        )
        // Return a black 160x160 bitmap.
        return Bitmap.createBitmap(MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    // 1. Perform the initial crop
    val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, width, height)

    // 2. Resize the cropped bitmap to the model's required input size
    return Bitmap.createScaledBitmap(
        croppedBitmap,
        MODEL_INPUT_WIDTH,
        MODEL_INPUT_HEIGHT,
        true // filter
    )
}



