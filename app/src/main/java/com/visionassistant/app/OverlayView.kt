package com.visionassistant.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var dets: List<Detection> = emptyList()
    private var frameW = 1
    private var frameH = 1
    private val box = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val text = Paint().apply { color = Color.GREEN; textSize = 42f; style = Paint.Style.FILL }

    fun update(d: List<Detection>, w: Int, h: Int) {
        dets = d; frameW = w; frameH = h
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // PreviewView uses FILL_CENTER: the frame is scaled to cover the view and cropped.
        val scale = maxOf(width / frameW.toFloat(), height / frameH.toFloat())
        val dx = (width - frameW * scale) / 2f
        val dy = (height - frameH * scale) / 2f
        for (d in dets) {
            val l = d.x1 * frameW * scale + dx
            val t = d.y1 * frameH * scale + dy
            val r = d.x2 * frameW * scale + dx
            val b = d.y2 * frameH * scale + dy
            canvas.drawRect(l, t, r, b, box)
            canvas.drawText("${d.label} ${(d.score * 100).toInt()}%", l + 6f, t + 44f, text)
        }
    }
}
