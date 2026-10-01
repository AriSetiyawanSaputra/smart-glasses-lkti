package com.example.smartglases.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View

import com.example.smartglases.ai.Detection


class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {


    // =========================================================
    // PAINT BOUNDING BOX
    // =========================================================

    private val boxPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {

            style = Paint.Style.STROKE

            strokeWidth = 8f
        }


    // =========================================================
    // PAINT ROI
    // =========================================================

    private val roiPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {

            color = Color.YELLOW

            style = Paint.Style.STROKE

            strokeWidth = 6f
        }


    // =========================================================
    // PAINT TEXT
    // =========================================================

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {

            color = Color.YELLOW

            style = Paint.Style.FILL

            textSize = 36f

            isFakeBoldText = true
        }


    // =========================================================
    // DETECTIONS
    // =========================================================

    private var detections: List<Detection> =
        emptyList()


    // =========================================================
    // UKURAN SUMBER KAMERA
    // =========================================================

    private val sourceWidth = 640f

    private val sourceHeight = 480f


    // =========================================================
    // ROI
    //
    // HARUS SAMA DENGAN CameraFragment
    // =========================================================

    private val roiLeft = 200f
    private val roiTop = 80f
    private val roiRight = 440f
    private val roiBottom = 480f


    // =========================================================
    // SET DETECTIONS
    // =========================================================

    fun setDetections(
        newDetections: List<Detection>
    ) {

        detections =
            newDetections


        Log.d(
            "SMARTGLASSES_OVERLAY",
            "Menerima ${detections.size} person"
        )


        postInvalidate()
    }


    // =========================================================
    // DRAW
    // =========================================================

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)


        Log.d(
            "SMARTGLASSES_OVERLAY",
            "onDraw: " +
                    "${detections.size} person, " +
                    "view=${width}x${height}"
        )


        // =====================================================
        // HITUNG SCALE VIDEO
        // =====================================================

        val scale =
            minOf(
                width / sourceWidth,
                height / sourceHeight
            )


        val displayedWidth =
            sourceWidth * scale


        val displayedHeight =
            sourceHeight * scale


        val offsetX =
            (width - displayedWidth) / 2f


        val offsetY =
            (height - displayedHeight) / 2f


        // =====================================================
        // GAMBAR ROI
        // =====================================================

        val roiRect =
            RectF(
                offsetX +
                        roiLeft * scale,

                offsetY +
                        roiTop * scale,

                offsetX +
                        roiRight * scale,

                offsetY +
                        roiBottom * scale
            )


        canvas.drawRect(
            roiRect,
            roiPaint
        )


        // =====================================================
        // KALAU TIDAK ADA PERSON
        // =====================================================

        if (detections.isEmpty()) {
            return
        }


        // =====================================================
        // GAMBAR SETIAP PERSON
        // =====================================================

        for (detection in detections) {

            // =====================================================
            // NAMA OBJECT
            // =====================================================

            val className =
                when (detection.classId) {

                    0 -> "person"

                    1 -> "bicycle"

                    else -> "unknown"
                }


            // =====================================================
            // BOTTOM CENTER
            // =====================================================

            val bottomCenterX =
                (
                        detection.x1 +
                                detection.x2
                        ) / 2f

            val bottomCenterY =
                detection.y2


            // =====================================================
            // CEK ROI
            // =====================================================

            val insideRoi =
                bottomCenterX >= roiLeft &&
                        bottomCenterX <= roiRight &&
                        bottomCenterY >= roiTop &&
                        bottomCenterY <= roiBottom


            // =====================================================
            // WARNA BOX
            //
            // PERSON:
            //   dalam ROI  -> merah
            //   luar ROI   -> hijau
            //
            // BICYCLE:
            //   untuk sekarang -> biru
            // =====================================================

            boxPaint.color =
                when (detection.classId) {

                    // ------------------------------
                    // PERSON
                    // ------------------------------

                    0 -> {
                        if (insideRoi) {
                            Color.RED
                        } else {
                            Color.GREEN
                        }
                    }


                    // ------------------------------
                    // BICYCLE
                    // ------------------------------

                    1 -> {
                        Color.BLUE
                    }


                    else -> {
                        Color.WHITE
                    }
                }


            // =====================================================
            // KOORDINAT BOX
            // =====================================================

            val left =
                offsetX +
                        detection.x1 * scale

            val top =
                offsetY +
                        detection.y1 * scale

            val right =
                offsetX +
                        detection.x2 * scale

            val bottom =
                offsetY +
                        detection.y2 * scale


            // =====================================================
            // GAMBAR BOX
            // =====================================================

            canvas.drawRect(
                RectF(
                    left,
                    top,
                    right,
                    bottom
                ),
                boxPaint
            )


            // =====================================================
            // CONFIDENCE
            // =====================================================

            val confidence =
                (
                        detection.confidence *
                                100
                        ).toInt()


            // =====================================================
            // LABEL
            // =====================================================

            val label =
                "$className $confidence%"


            canvas.drawText(
                label,
                left,
                maxOf(
                    top - 10f,
                    40f
                ),
                textPaint
            )


            // =====================================================
            // LOG
            // =====================================================

            Log.d(
                "SMARTGLASSES_OVERLAY",
                "Draw $className: " +
                        "ROI=$insideRoi, " +
                        "left=$left, " +
                        "top=$top, " +
                        "right=$right, " +
                        "bottom=$bottom"
            )
        }
    }
}