package com.example.smartglases.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    val classId: Int
)

class YoloDetector(context: Context) {

    private val environment = OrtEnvironment.getEnvironment()

    private val session: OrtSession

    private val inputName = "images"

    private val inputSize = 640

    init {

        val modelBytes = context.assets
            .open("yolo26n.onnx")
            .use { it.readBytes() }

        Log.d(
            "SMARTGLASSES_YOLO",
            "Ukuran model: ${modelBytes.size} bytes"
        )

        session = environment.createSession(modelBytes)

        Log.d(
            "SMARTGLASSES_YOLO",
            "Model berhasil dibuka"
        )

        Log.d(
            "SMARTGLASSES_YOLO",
            "Input: ${session.inputInfo}"
        )

        Log.d(
            "SMARTGLASSES_YOLO",
            "Output: ${session.outputInfo}"
        )

        Log.d(
            "SMARTGLASSES_YOLO",
            "Model YOLO berhasil dimuat"
        )
    }

    fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float = 0.25f
    ): List<Detection> {

        val letterbox = letterbox(bitmap)

        val inputBuffer = bitmapToFloatBuffer(
            letterbox.bitmap
        )

        val inputTensor = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )

        try {

            val inputs = mapOf(
                inputName to inputTensor
            )

            session.run(inputs).use { result ->

                val outputTensor = result[0] as OnnxTensor

                val outputBuffer = outputTensor.floatBuffer

                val detections = mutableListOf<Detection>()

                outputBuffer.rewind()

                for (i in 0 until 300) {

                    val x1 = outputBuffer.get()
                    val y1 = outputBuffer.get()
                    val x2 = outputBuffer.get()
                    val y2 = outputBuffer.get()
                    val confidence = outputBuffer.get()
                    val classId = outputBuffer.get().toInt()

                    if (confidence < confidenceThreshold) {
                        continue
                    }

                    val originalX1 =
                        (x1 - letterbox.padX) / letterbox.scale

                    val originalY1 =
                        (y1 - letterbox.padY) / letterbox.scale

                    val originalX2 =
                        (x2 - letterbox.padX) / letterbox.scale

                    val originalY2 =
                        (y2 - letterbox.padY) / letterbox.scale

                    val finalX1 =
                        originalX1.coerceIn(
                            0f,
                            bitmap.width.toFloat()
                        )

                    val finalY1 =
                        originalY1.coerceIn(
                            0f,
                            bitmap.height.toFloat()
                        )

                    val finalX2 =
                        originalX2.coerceIn(
                            0f,
                            bitmap.width.toFloat()
                        )

                    val finalY2 =
                        originalY2.coerceIn(
                            0f,
                            bitmap.height.toFloat()
                        )

                    detections.add(
                        Detection(
                            x1 = finalX1,
                            y1 = finalY1,
                            x2 = finalX2,
                            y2 = finalY2,
                            confidence = confidence,
                            classId = classId
                        )
                    )
                }

                return detections
            }

        } finally {

            inputTensor.close()
        }
    }

    private data class LetterboxResult(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private fun letterbox(
        source: Bitmap
    ): LetterboxResult {

        val scale = min(
            inputSize.toFloat() / source.width,
            inputSize.toFloat() / source.height
        )

        val newWidth =
            (source.width * scale).toInt()

        val newHeight =
            (source.height * scale).toInt()

        val padX =
            (inputSize - newWidth) / 2f

        val padY =
            (inputSize - newHeight) / 2f

        val outputBitmap = Bitmap.createBitmap(
            inputSize,
            inputSize,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(outputBitmap)

        canvas.drawColor(Color.BLACK)

        val destination = RectF(
            padX,
            padY,
            padX + newWidth,
            padY + newHeight
        )

        val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or
                    Paint.FILTER_BITMAP_FLAG
        )

        canvas.drawBitmap(
            source,
            null,
            destination,
            paint
        )

        return LetterboxResult(
            bitmap = outputBitmap,
            scale = scale,
            padX = padX,
            padY = padY
        )
    }

    private fun bitmapToFloatBuffer(
        bitmap: Bitmap
    ): FloatBuffer {

        val byteBuffer = ByteBuffer
            .allocateDirect(
                1 * 3 * inputSize * inputSize * 4
            )
            .order(ByteOrder.nativeOrder())

        val floatBuffer = byteBuffer.asFloatBuffer()

        val pixels = IntArray(
            inputSize * inputSize
        )

        bitmap.getPixels(
            pixels,
            0,
            inputSize,
            0,
            0,
            inputSize,
            inputSize
        )

        // YOLO membutuhkan format:
        // [1, 3, 640, 640]
        // Jadi channel R, lalu G, lalu B.

        for (channel in 0..2) {

            for (pixel in pixels) {

                val red =
                    (pixel shr 16) and 0xFF

                val green =
                    (pixel shr 8) and 0xFF

                val blue =
                    pixel and 0xFF

                val value = when (channel) {
                    0 -> red
                    1 -> green
                    else -> blue
                }

                floatBuffer.put(
                    value / 255.0f
                )
            }
        }

        floatBuffer.rewind()

        return floatBuffer
    }

    fun close() {
        session.close()
    }
}