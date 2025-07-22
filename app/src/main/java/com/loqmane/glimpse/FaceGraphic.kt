package com.loqmane.glimpse

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mlkit.vision.face.Face


class FaceGraphic(
    overlay: GraphicOverlay,
    private val face: Face,
    private val recognizedName: String
) : GraphicOverlay.Graphic(overlay) {

    private val facePositionPaint: Paint
    private val boxPaint: Paint
    private val namePaint: Paint // Paint for drawing the name

    init {
        val selectedColor = Color.GREEN // Or any color you prefer

        facePositionPaint = Paint()
        facePositionPaint.color = selectedColor

        boxPaint = Paint()
        boxPaint.color = selectedColor
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = 5.0f

        namePaint = Paint() // Initialize name paint
        namePaint.color = Color.GREEN
        namePaint.textSize = 40.0f
        namePaint.textAlign = Paint.Align.CENTER

    }

    override fun draw(canvas: Canvas) {
        val face = this@FaceGraphic.face.boundingBox

        // Draws a bounding box around the face.
        val left = translateX(face.left.toFloat())
        val top = translateY(face.top.toFloat())
        val right = translateX(face.right.toFloat())
        val bottom = translateY(face.bottom.toFloat())
        canvas.drawRect(left, top, right, bottom, boxPaint)

        // Draw the recognized name above the bounding box
        val x = translateX(face.centerX().toFloat())
        val y = top - 10 // Position text slightly above the box
        canvas.drawText(recognizedName, x, y, namePaint)
    }

    companion object {
        private const val FACE_POSITION_RADIUS = 4.0f
        private const val BOX_STROKE_WIDTH = 5.0f
    }
}
