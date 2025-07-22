package com.loqmane.glimpse

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class GraphicOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val lock = Any()
    private val graphics = mutableListOf<Graphic>()

    var imageWidth = 0
    var imageHeight = 0
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    var isFrontFacing = false // Add this flag

    abstract class Graphic(private val overlay: GraphicOverlay) {
        abstract fun draw(canvas: Canvas)

        fun scale(v: Float) = v * overlay.scaleFactor

        // Mirror X if front facing
        fun translateX(x: Float): Float {
            return if (overlay.isFrontFacing) {
                overlay.width - (x * overlay.scaleFactor + overlay.offsetX)
            } else {
                x * overlay.scaleFactor + overlay.offsetX
            }
        }

        fun translateY(y: Float) = y * overlay.scaleFactor + overlay.offsetY

    }

    fun clear() = synchronized(lock) { graphics.clear() }.also { postInvalidate() }
    fun add(graphic: Graphic) = synchronized(lock) { graphics.add(graphic) }
    fun setCameraInfo(width: Int, height: Int, isFrontFacing: Boolean) {
        imageWidth = width
        imageHeight = height
        this.isFrontFacing = isFrontFacing
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(lock) {
            if (imageWidth > 0 && imageHeight > 0) {
                val vw = width.toFloat()
                val vh = height.toFloat()
                val sx = vw / imageWidth
                val sy = vh / imageHeight
                scaleFactor = max(sx, sy)   // match PreviewView’s fillCenter
                offsetX = (vw - imageWidth * scaleFactor) / 2f
                offsetY = (vh - imageHeight * scaleFactor) / 2f
            }
            graphics.forEach { it.draw(canvas) }
        }
    }
}