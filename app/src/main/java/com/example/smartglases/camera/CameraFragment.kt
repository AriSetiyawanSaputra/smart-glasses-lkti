package com.example.smartglases.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

import com.example.smartglases.MainActivity
import com.example.smartglases.R
import com.example.smartglases.ai.Detection
import com.example.smartglases.ai.YoloDetector

import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment as AusbcCameraFragment
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors


class CameraFragment : AusbcCameraFragment() {

    // =========================================================
    // VIEW
    // =========================================================

    private var rootView: View? = null

    private var cameraView: AspectRatioTextureView? = null

    private var cameraContainer: FrameLayout? = null


    // =========================================================
    // YOLO
    // =========================================================

    private var yoloDetector: YoloDetector? = null


    // =========================================================
    // THREAD INFERENCE
    // =========================================================

    private val inferenceExecutor =
        Executors.newSingleThreadExecutor()

    @Volatile
    private var inferenceRunning = false


    // =========================================================
    // FRAME COUNTER
    // =========================================================

    private var frameCounter = 0


    // =========================================================
    // INTERVAL INFERENCE
    // =========================================================

    // C270 sekitar 15 FPS
    // YOLO dijalankan setiap 10 frame
    private val inferenceFrameInterval = 10


    // =========================================================
    // CONFIDENCE
    // =========================================================

    private val confidenceThreshold = 0.25f


    // =========================================================
    // ROI
    // Frame kamera = 640 x 480
    // =========================================================

    private val roiLeft = 200f
    private val roiTop = 80f
    private val roiRight = 440f
    private val roiBottom = 480f


    // =========================================================
    // CALLBACK DATA KAMERA
    // =========================================================

    private val previewDataCallback =
        object : IPreviewDataCallBack {

            override fun onPreviewData(
                data: ByteArray?,
                width: Int,
                height: Int,
                format: IPreviewDataCallBack.DataFormat
            ) {

                // =================================================
                // DATA NULL
                // =================================================

                if (data == null) {
                    return
                }


                // =================================================
                // HANYA NV21
                // =================================================

                if (
                    format !=
                    IPreviewDataCallBack.DataFormat.NV21
                ) {
                    return
                }


                // =================================================
                // FRAME COUNTER
                // =================================================

                frameCounter++


                // =================================================
                // INFERENCE SETIAP 10 FRAME
                // =================================================

                if (
                    frameCounter %
                    inferenceFrameInterval != 0
                ) {
                    return
                }


                // =================================================
                // NV21 -> BITMAP
                // =================================================

                val bitmap =
                    nv21ToBitmap(
                        data,
                        width,
                        height
                    ) ?: return


                Log.d(
                    "SMARTGLASSES_AI",
                    "Bitmap YOLO: " +
                            "${bitmap.width}x${bitmap.height}"
                )


                // =================================================
                // CEK INFERENCE
                // =================================================

                if (inferenceRunning) {

                    bitmap.recycle()

                    return
                }


                // =================================================
                // AMBIL DETECTOR
                // =================================================

                val detector =
                    yoloDetector ?: run {

                        bitmap.recycle()

                        return
                    }


                inferenceRunning = true


                // =================================================
                // JALANKAN YOLO
                // =================================================

                inferenceExecutor.execute {

                    try {

                        // =================================================
                        // INFERENCE
                        // =================================================

                        val detections =
                            detector.detect(bitmap)


                        Log.d(
                            "SMARTGLASSES_AI",
                            "Jumlah semua deteksi: " +
                                    detections.size
                        )


                        // =================================================
                        // PERSON
                        // classId 0 = person
                        // =================================================

                        val personDetections =
                            detections.filter { detection ->

                                detection.classId == 0 &&
                                        detection.confidence >=
                                        confidenceThreshold
                            }

                        val bicycleDetections = detections.filter {
                            it.classId == 1 &&
                                    it.confidence >= confidenceThreshold
                        }

                        // =========================================================
// MOTOR
// classId 3 = motor
// =========================================================

                        val motorDetections = detections.filter {
                            it.classId == 3 &&
                                    it.confidence >= confidenceThreshold
                        }

                        // =========================================================
// POTTED PLANT
// classId 58 = potted plant
// =========================================================

                        val pottedPlantDetections = detections.filter {
                            it.classId == 58 &&
                                    it.confidence >= confidenceThreshold
                        }

                        // =========================================================
// KALIBRASI POTTED PLANT
//
// Hanya potted plant yang masuk ROI
// Pilih confidence tertinggi
// =========================================================

                        val calibrationPottedPlant =
                            pottedPlantDetections
                                .filter { detection ->
                                    isInsideRoi(detection)
                                }
                                .maxByOrNull { detection ->
                                    detection.confidence
                                }


// =========================================================
// LOG KALIBRASI POTTED PLANT
// =========================================================

                        if (calibrationPottedPlant != null) {

                            val detection =
                                calibrationPottedPlant

                            val boxWidth =
                                detection.x2 - detection.x1

                            val boxHeight =
                                detection.y2 - detection.y1

                            val centerX =
                                (detection.x1 + detection.x2) / 2f

                            val centerY =
                                (detection.y1 + detection.y2) / 2f

                            val bottomCenterX =
                                centerX

                            val bottomCenterY =
                                detection.y2

                            val insideRoi =
                                isInsideRoi(detection)


                            Log.d(
                                "SMARTGLASSES_POTTED_PLANT_CALIBRATION",
                                String.format(
                                    "PottedPlant: " +
                                            "classId=%d, " +
                                            "confidence=%.2f, " +
                                            "x1=%.2f, " +
                                            "y1=%.2f, " +
                                            "x2=%.2f, " +
                                            "y2=%.2f, " +
                                            "width=%.2f, " +
                                            "height=%.2f, " +
                                            "center=(%.2f, %.2f), " +
                                            "bottomCenter=(%.2f, %.2f), " +
                                            "insideROI=%s",

                                    detection.classId,
                                    detection.confidence,

                                    detection.x1,
                                    detection.y1,

                                    detection.x2,
                                    detection.y2,

                                    boxWidth,
                                    boxHeight,

                                    centerX,
                                    centerY,

                                    bottomCenterX,
                                    bottomCenterY,

                                    insideRoi
                                )
                            )

                        } else {

                            Log.d(
                                "SMARTGLASSES_POTTED_PLANT_CALIBRATION",
                                "PottedPlant: tidak ada potted plant dalam ROI"
                            )
                        }

                        val calibrationBicycle = bicycleDetections
                            .filter { detection ->
                                val objectX = (detection.x1 + detection.x2) / 2f
                                val objectY = detection.y2

                                objectX >= roiLeft &&
                                        objectX <= roiRight &&
                                        objectY >= roiTop &&
                                        objectY <= roiBottom
                            }
                            .maxByOrNull { it.confidence }

                        // =========================================================
// CHAIR
// classId 56 = chair
// =========================================================

                        val chairDetections =
                            detections.filter { detection ->

                                detection.classId == 56 &&
                                        detection.confidence >=
                                        confidenceThreshold
                            }



                        // =========================================================

// KALIBRASI CHAIR
//
// Hanya chair yang masuk ROI
// Pilih confidence tertinggi
// =========================================================

                        val calibrationChair =
                            chairDetections
                                .filter { detection ->
                                    isInsideRoi(detection)
                                }
                                .maxByOrNull { detection ->
                                    detection.confidence
                                }
// =========================================================
// TTS OBJEK RENDAH
//
// Chair + Bicycle
// Hanya objek yang berada dalam ROI
// =========================================================

                        val lowObjectDetections =
                            (
                                    chairDetections +
                                            bicycleDetections +
                                            motorDetections +
                                            pottedPlantDetections
                                    ).filter { detection ->
                                    isInsideRoi(detection)
                                }



// =========================================================
// PILIH OBJEK DENGAN CONFIDENCE TERTINGGI
// =========================================================

                        val calibrationLowObject =
                            lowObjectDetections
                                .maxByOrNull { detection ->
                                    detection.confidence
                                }


// =========================================================
// TTS OBJEK RENDAH
// =========================================================

                        if (calibrationLowObject != null) {

                            val detection =
                                calibrationLowObject

                            val boxWidth =
                                detection.x2 - detection.x1


                            // =====================================================
                            // ESTIMASI JARAK SESUAI CLASS
                            // =====================================================

                            val distance =
                                when (detection.classId) {

                                    // Person (0)
                                    0 -> estimatePersonDistance(boxWidth)

                                    // Chair (56)
                                    56 -> estimateChairDistance(boxWidth)

                                    // Motor (3)
                                    3 -> estimateMotorDistance(boxWidth)

                                    // Potted Plant (58)
                                    58 -> estimatePottedPlantDistance(boxWidth)

                                    // Bicycle (1)
                                    1 -> estimateBicycleDistance(boxWidth)

                                    else -> 5.0f
                                }


// =========================================================
// LOG DATA KALIBRASI (TERMASUK PERSON)
// =========================================================
                            when (detection.classId) {
                                0 -> Log.d(
                                    "TEST_PERSON_DISTANCE",
                                    String.format("Person -> BoxWidth: %.2f px | Estimasi: %.2f meter", boxWidth, distance)
                                )
                                56 -> Log.d(
                                    "TEST_CHAIR_DISTANCE",
                                    String.format("Chair -> BoxWidth: %.2f px | Estimasi: %.2f meter", boxWidth, distance)
                                )
                                3 -> Log.d(
                                    "TEST_MOTOR_DISTANCE",
                                    String.format("Motor -> BoxWidth: %.2f px | Estimasi: %.2f meter", boxWidth, distance)
                                )
                                1 -> Log.d(
                                    "TEST_BICYCLE_DISTANCE",
                                    String.format("Bicycle -> BoxWidth: %.2f px | Estimasi: %.2f meter", boxWidth, distance)
                                )
                                58 -> Log.d(
                                    "TEST_PLANT_DISTANCE",
                                    String.format("Plant -> BoxWidth: %.2f px | Estimasi: %.2f meter", boxWidth, distance)
                                )
                            }

                            // =========================================================
// TARUH LOG KHUSUS CHAIR DI SINI
// =========================================================
                            if (detection.classId == 56) {
                                Log.d(
                                    "TEST_CHAIR_DISTANCE",
                                    String.format("Chair -> BoxWidth: %.2f px | Estimasi: %.2f meter", boxWidth, distance)
                                )
                            }


                            // =====================================================
                            // LOG JARAK
                            // =====================================================

                            Log.d(
                                "SMARTGLASSES_LOW_OBJECT_DISTANCE",
                                String.format(
                                    "classId=%d, width=%.2f px, distance=%.2f meter",
                                    detection.classId,
                                    boxWidth,
                                    distance
                                )
                            )


                            // =====================================================
                            // TTS
                            // =====================================================

                            requireActivity().runOnUiThread {

                                try {

                                    (
                                            requireActivity() as MainActivity
                                            ).speakLowObject(
                                            detection.classId,
                                            distance
                                        )

                                } catch (e: Exception) {

                                    Log.e(
                                        "SMARTGLASSES_TTS_LOW_OBJECT",
                                        "Gagal menjalankan TTS objek rendah",
                                        e
                                    )
                                }
                            }

                        } else {

                            // =====================================================
                            // TIDAK ADA CHAIR / BICYCLE DALAM ROI
                            // =====================================================

                            Log.d(
                                "SMARTGLASSES_LOW_OBJECT_DISTANCE",
                                "Tidak ada objek rendah dalam ROI"
                            )


                            requireActivity().runOnUiThread {

                                try {

                                    (
                                            requireActivity() as MainActivity
                                            ).resetLowObjectAnnouncement()

                                } catch (e: Exception) {

                                    Log.e(
                                        "SMARTGLASSES_TTS_LOW_OBJECT",
                                        "Gagal reset TTS objek rendah",
                                        e
                                    )
                                }
                            }
                        }


// =========================================================
// KALIBRASI MOTOR
//
// Hanya motor yang masuk ROI
// Pilih confidence tertinggi
// =========================================================

                        val calibrationMotor =
                            motorDetections
                                .filter { detection ->
                                    isInsideRoi(detection)
                                }
                                .maxByOrNull { detection ->
                                    detection.confidence
                                }


// =========================================================
// LOG KALIBRASI MOTOR
// =========================================================

                        if (calibrationMotor != null) {

                            val detection =
                                calibrationMotor

                            val boxWidth =
                                detection.x2 - detection.x1

                            val boxHeight =
                                detection.y2 - detection.y1

                            val centerX =
                                (detection.x1 + detection.x2) / 2f

                            val centerY =
                                (detection.y1 + detection.y2) / 2f

                            val bottomCenterX =
                                centerX

                            val bottomCenterY =
                                detection.y2

                            val insideRoi =
                                isInsideRoi(detection)


                            Log.d(
                                "SMARTGLASSES_MOTOR_CALIBRATION",
                                String.format(
                                    "Motor: " +
                                            "classId=%d, " +
                                            "confidence=%.2f, " +
                                            "x1=%.2f, " +
                                            "y1=%.2f, " +
                                            "x2=%.2f, " +
                                            "y2=%.2f, " +
                                            "width=%.2f, " +
                                            "height=%.2f, " +
                                            "center=(%.2f, %.2f), " +
                                            "bottomCenter=(%.2f, %.2f), " +
                                            "insideROI=%s",

                                    detection.classId,
                                    detection.confidence,

                                    detection.x1,
                                    detection.y1,

                                    detection.x2,
                                    detection.y2,

                                    boxWidth,
                                    boxHeight,

                                    centerX,
                                    centerY,

                                    bottomCenterX,
                                    bottomCenterY,

                                    insideRoi
                                )
                            )

                        } else {

                            Log.d(
                                "SMARTGLASSES_MOTOR_CALIBRATION",
                                "Motor: tidak ada motor dalam ROI"
                            )
                        }

// =========================================================
// LOG KALIBRASI CHAIR
// =========================================================

                        if (calibrationChair != null) {

                            val detection =
                                calibrationChair

                            val boxWidth =
                                detection.x2 - detection.x1

                            val boxHeight =
                                detection.y2 - detection.y1

                            val centerX =
                                (
                                        detection.x1 +
                                                detection.x2
                                        ) / 2f

                            val centerY =
                                (
                                        detection.y1 +
                                                detection.y2
                                        ) / 2f

                            val bottomCenterX =
                                centerX

                            val bottomCenterY =
                                detection.y2

                            val insideRoi =
                                bottomCenterX >= roiLeft &&
                                        bottomCenterX <= roiRight &&
                                        bottomCenterY >= roiTop &&
                                        bottomCenterY <= roiBottom


                            Log.d(
                                "SMARTGLASSES_CHAIR_CALIBRATION",
                                String.format(
                                    "Chair: " +
                                            "classId=%d, " +
                                            "confidence=%.2f, " +
                                            "x1=%.2f, " +
                                            "y1=%.2f, " +
                                            "x2=%.2f, " +
                                            "y2=%.2f, " +
                                            "width=%.2f, " +
                                            "height=%.2f, " +
                                            "center=(%.2f, %.2f), " +
                                            "bottomCenter=(%.2f, %.2f), " +
                                            "insideROI=%s",

                                    detection.classId,
                                    detection.confidence,

                                    detection.x1,
                                    detection.y1,

                                    detection.x2,
                                    detection.y2,

                                    boxWidth,
                                    boxHeight,

                                    centerX,
                                    centerY,

                                    bottomCenterX,
                                    bottomCenterY,

                                    insideRoi
                                )
                            )

                        } else {

                            Log.d(
                                "SMARTGLASSES_CHAIR_CALIBRATION",
                                "Chair: tidak ada chair dalam ROI"
                            )
                        }

                        // =========================================================
// KALIBRASI PERSON
// classId 0 = person
// =========================================================

                        for (detection in personDetections) {

                            // Lebar bounding box
                            val boxWidth =
                                detection.x2 - detection.x1

                            // Tinggi bounding box
                            val boxHeight =
                                detection.y2 - detection.y1

                            // Center bounding box
                            val centerX =
                                (
                                        detection.x1 +
                                                detection.x2
                                        ) / 2f

                            val centerY =
                                (
                                        detection.y1 +
                                                detection.y2
                                        ) / 2f

                            // Bottom-center untuk cek ROI
                            val bottomCenterX =
                                centerX

                            val bottomCenterY =
                                detection.y2

                            // Cek apakah person masuk ROI
                            val insideRoi =
                                bottomCenterX >= roiLeft &&
                                        bottomCenterX <= roiRight &&
                                        bottomCenterY >= roiTop &&
                                        bottomCenterY <= roiBottom

                            // =====================================================
                            // LOG SEMUA DATA PERSON
                            // =====================================================

                            Log.d(
                                "SMARTGLASSES_PERSON_CALIBRATION",
                                String.format(
                                    "Person: " +
                                            "classId=%d, " +
                                            "confidence=%.2f, " +
                                            "x1=%.2f, " +
                                            "y1=%.2f, " +
                                            "x2=%.2f, " +
                                            "y2=%.2f, " +
                                            "width=%.2f, " +
                                            "height=%.2f, " +
                                            "center=(%.2f, %.2f), " +
                                            "bottomCenter=(%.2f, %.2f), " +
                                            "insideROI=%s",

                                    detection.classId,

                                    detection.confidence,

                                    detection.x1,
                                    detection.y1,

                                    detection.x2,
                                    detection.y2,

                                    boxWidth,
                                    boxHeight,

                                    centerX,
                                    centerY,

                                    bottomCenterX,
                                    bottomCenterY,

                                    insideRoi
                                )
                            )
                        }


                        Log.d(
                            "SMARTGLASSES_PERSON",
                            "Jumlah person: " +
                                    personDetections.size
                        )


                        // =================================================
                        // KALIBRASI PERSON
                        //
                        // Hanya person yang berada dalam ROI
                        // Pilih confidence tertinggi
                        // =================================================

// =========================================================
// PERSON DALAM ROI
// =========================================================

                        val calibrationPerson =
                            personDetections
                                .filter { detection ->
                                    isInsideRoi(detection)
                                }
                                .maxByOrNull { detection ->
                                    detection.confidence
                                }


// =========================================================
// PERSON TERDETEKSI
// =========================================================

                        if (calibrationPerson != null) {

                            val detection =
                                calibrationPerson

                            // Lebar bounding box person
                            val boxWidth =
                                detection.x2 - detection.x1

                            // Hitung jarak menggunakan
                            // hasil kalibrasi person yang sudah ada

                            val distance =
                                estimatePersonDistance(boxWidth)


                            // =====================================================
                            // LOG JARAK
                            // =====================================================

                            Log.d(
                                "SMARTGLASSES_DISTANCE",
                                String.format(
                                    "Person dalam ROI: " +
                                            "classId=%d, " +
                                            "width=%.2f px, " +
                                            "distance=%.2f meter",
                                    detection.classId,
                                    boxWidth,
                                    distance
                                )
                            )


                            // =====================================================
                            // TTS
                            // =====================================================

                            requireActivity().runOnUiThread {

                                try {

                                    (
                                            requireActivity()
                                                    as MainActivity
                                            ).speakObstacle(
                                            detection.classId,
                                            distance
                                        )

                                } catch (e: Exception) {

                                    Log.e(
                                        "SMARTGLASSES_TTS",
                                        "Gagal menjalankan TTS",
                                        e
                                    )
                                }
                            }

                        } else {

                            // =====================================================
                            // TIDAK ADA PERSON DALAM ROI
                            // =====================================================

                            Log.d(
                                "SMARTGLASSES_DISTANCE",
                                "Tidak ada person dalam ROI"
                            )


                            // Reset status TTS
                            requireActivity().runOnUiThread {

                                try {

                                    (
                                            requireActivity()
                                                    as MainActivity
                                            ).resetObstacleAnnouncement()

                                } catch (e: Exception) {

                                    Log.e(
                                        "SMARTGLASSES_TTS",
                                        "Gagal reset TTS",
                                        e
                                    )
                                }
                            }
                        }

// =========================================================
// DETEKSI UNTUK OVERLAY
//
// person + chair
// =========================================================

                        val objectDetections =
                            personDetections +
                                    chairDetections +
                                    bicycleDetections +
                                    motorDetections +
                                    pottedPlantDetections


// =========================================================
// KIRIM DETEKSI KE OVERLAY
// =========================================================

                        activity?.runOnUiThread {

                            try {

                                (
                                        activity as MainActivity
                                        ).updateDetections(
                                        objectDetections
                                    )

                            } catch (e: Exception) {

                                Log.e(
                                    "SMARTGLASSES_OVERLAY",
                                    "Gagal mengirim deteksi ke overlay",
                                    e
                                )
                            }
                        }



                    } catch (e: Exception) {

                        Log.e(
                            "SMARTGLASSES_AI",
                            "Inference YOLO gagal",
                            e
                        )

                    } finally {

                        inferenceRunning = false

                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }


    // =========================================================
    // NV21 -> BITMAP
    // =========================================================

    private fun nv21ToBitmap(
        data: ByteArray,
        width: Int,
        height: Int
    ): Bitmap? {

        return try {

            val yuvImage =
                YuvImage(
                    data,
                    ImageFormat.NV21,
                    width,
                    height,
                    null
                )


            val outputStream =
                ByteArrayOutputStream()


            yuvImage.compressToJpeg(

                Rect(
                    0,
                    0,
                    width,
                    height
                ),

                90,

                outputStream
            )


            val jpegData =
                outputStream.toByteArray()


            BitmapFactory.decodeByteArray(

                jpegData,

                0,

                jpegData.size
            )


        } catch (e: Exception) {

            Log.e(

                "SMARTGLASSES_BITMAP",

                "Gagal convert NV21: " +
                        e.message,

                e
            )

            null
        }
    }


    // =========================================================
    // CEK ROI
    // =========================================================

    private fun isInsideRoi(
        detection: Detection
    ): Boolean {

        // Bottom-center bounding box
        val objectX =
            (
                    detection.x1 +
                            detection.x2
                    ) / 2f


        val objectY =
            detection.y2


        return objectX >= roiLeft &&
                objectX <= roiRight &&
                objectY >= roiTop &&
                objectY <= roiBottom
    }

    // =========================================================
// ESTIMASI JARAK PERSON
// =========================================================

    private fun estimatePersonDistance(boxWidth: Float): Float {
        val w1m = 341.13f
        val w2m = 220.75f
        val w3m = 136.47f
        val w4m = 100.85f

        if (boxWidth >= w1m) return 1.0f

        // UBAH DARI 4.0f MENJADI 5.0f
        // Jika boxWidth < w4m, objek berada di jarak > 4 meter
        if (boxWidth < w4m) {
            return 5.0f
        }

        if (boxWidth >= w2m) {
            return interpolate(boxWidth, w1m, 1.0f, w2m, 2.0f)
        }

        if (boxWidth >= w3m) {
            return interpolate(boxWidth, w2m, 2.0f, w3m, 3.0f)
        }

        return interpolate(boxWidth, w3m, 3.0f, w4m, 4.0f)
    }

    // =========================================================
// INTERPOLASI JARAK
// =========================================================

    private fun interpolate(
        value: Float,
        valueA: Float,
        distanceA: Float,
        valueB: Float,
        distanceB: Float
    ): Float {

        val ratio =
            (value - valueA) /
                    (valueB - valueA)

        return distanceA +
                ratio *
                (distanceB - distanceA)
    }

// =========================================================
// ESTIMASI JARAK CHAIR
//
// Kalibrasi chair:
//
// 2 meter -> 255.53 px
// 3 meter -> 175.52 px
// 4 meter -> 150.33 px
//
// Belum ada data 1 meter.
// Untuk sementara:
// width >= 255.53 px
// dianggap berada di bawah 2 meter.
// =========================================================

    private fun estimateChairDistance(boxWidth: Float): Float {
        val w2m = 213.00f
        val w3m = 150.33f
        val w4m = 115.00f

        // Jika lebar box melampaui kalibrasi 2 meter (<= 2.0m)
        if (boxWidth >= w2m) {
            val distance = 1.9f // Mengembalikan 1.9f agar langsung memicu Zone 1 (sangat dekat)
            Log.d(
                "TEST_CHAIR_DISTANCE",
                "Chair -> BoxWidth: %.2f px | Estimasi: %.2f meter".format(boxWidth, distance)
            )
            return distance
        }

        if (boxWidth < w4m) {
            val distance = 5.0f
            Log.d(
                "TEST_CHAIR_DISTANCE",
                "Chair -> BoxWidth: %.2f px | Estimasi: %.2f meter".format(boxWidth, distance)
            )
            return distance
        }

        if (boxWidth >= w3m) {
            val distance = interpolate(boxWidth, w2m, 2.0f, w3m, 3.0f)
            Log.d(
                "TEST_CHAIR_DISTANCE",
                "Chair -> BoxWidth: %.2f px | Estimasi: %.2f meter".format(boxWidth, distance)
            )
            return distance
        }

        val distance = interpolate(boxWidth, w3m, 3.0f, w4m, 4.0f)
        Log.d(
            "TEST_CHAIR_DISTANCE",
            "Chair -> BoxWidth: %.2f px | Estimasi: %.2f meter".format(boxWidth, distance)
        )
        return distance
    }

// =========================================================
// ESTIMASI JARAK BICYCLE
//
// Kalibrasi bicycle:
//
// 2 meter -> 618.26 px
// 3 meter -> 460.02 px
// 4 meter -> 368.14 px
//
// Belum ada data 1 meter.
//
// Untuk sementara:
// width >= 618.26 px
// dianggap berada di bawah 2 meter.
// =========================================================

    private fun estimateBicycleDistance(boxWidth: Float): Float {
        val w2m = 618.26f
        val w3m = 460.02f
        val w4m = 368.14f

        if (boxWidth >= w2m) return 1.9f

        // UBAH DARI 4.0f MENJADI 5.0f
        if (boxWidth < w4m) {
            return 5.0f
        }

        if (boxWidth >= w3m) {
            return interpolate(boxWidth, w2m, 2.0f, w3m, 3.0f)
        }

        return interpolate(boxWidth, w3m, 3.0f, w4m, 4.0f)
    }

    // =========================================================
// ESTIMASI JARAK MOTOR
//
// Kalibrasi motor:
//
// 2 meter -> 636.57 px
// 3 meter -> 515.65 px
// 4 meter -> 410.39 px
//
// Belum ada data 1 meter.
//
// Untuk sementara:
// width >= 636.57 px
// dianggap berada di bawah 2 meter.
// =========================================================

    private fun estimateMotorDistance(boxWidth: Float): Float {
        val w2m = 636.57f
        val w3m = 515.65f
        val w4m = 410.39f

        if (boxWidth >= w2m) return 1.9f

        // UBAH DARI 4.0f MENJADI 5.0f
        if (boxWidth < w4m) {
            return 5.0f
        }

        if (boxWidth >= w3m) {
            return interpolate(boxWidth, w2m, 2.0f, w3m, 3.0f)
        }

        return interpolate(boxWidth, w3m, 3.0f, w4m, 4.0f)
    }

    // =========================================================
// ESTIMASI JARAK POTTED PLANT
//
// Kalibrasi potted plant:
//
// 2 meter -> 243.73 px
// 3 meter -> 146.87 px
// 4 meter -> 116.80 px
//
// Belum ada data 1 meter.
//
// Untuk sementara:
// width >= 243.73 px
// dianggap berada di bawah 2 meter.
// =========================================================

    private fun estimatePottedPlantDistance(boxWidth: Float): Float {
        val w2m = 243.73f
        val w3m = 146.87f
        val w4m = 116.80f

        if (boxWidth >= w2m) return 1.9f

        // UBAH DARI 4.0f MENJADI 5.0f
        if (boxWidth < w4m) {
            return 5.0f
        }

        if (boxWidth >= w3m) {
            return interpolate(boxWidth, w2m, 2.0f, w3m, 3.0f)
        }

        return interpolate(boxWidth, w3m, 3.0f, w4m, 4.0f)
    }

    // =========================================================
    // ROOT VIEW
    // =========================================================

    override fun getRootView(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): View? {

        if (rootView == null) {

            rootView =
                inflater.inflate(

                    R.layout.fragment_camera,

                    container,

                    false
                )


            cameraView =
                rootView?.findViewById(
                    R.id.camera_view
                )


            cameraContainer =
                rootView?.findViewById(
                    R.id.camera_container
                )
        }


        return rootView
    }



    // =========================================================
    // CAMERA VIEW
    // =========================================================

    override fun getCameraView(): IAspectRatio? {

        return cameraView
    }


    // =========================================================
    // CAMERA CONTAINER
    // =========================================================

    override fun getCameraViewContainer(): ViewGroup? {

        return cameraContainer
    }


    // =========================================================
    // GRAVITY
    // =========================================================

    override fun getGravity(): Int {

        return Gravity.CENTER
    }


    // =========================================================
    // CAMERA REQUEST
    // =========================================================

    override fun getCameraRequest(): CameraRequest {

        return CameraRequest.Builder()

            .setPreviewWidth(640)

            .setPreviewHeight(480)

            .setRenderMode(
                CameraRequest.RenderMode.OPENGL
            )

            .setAudioSource(
                CameraRequest.AudioSource.NONE
            )

            .setPreviewFormat(
                CameraRequest.PreviewFormat.FORMAT_MJPEG
            )

            .setAspectRatioShow(true)

            .setRawPreviewData(true)

            .create()
    }


    // =========================================================
    // VIEW CREATED
    // =========================================================

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {

        super.onViewCreated(
            view,
            savedInstanceState
        )


        try {

            yoloDetector =
                YoloDetector(
                    requireContext()
                )


            Log.d(
                "SMARTGLASSES_YOLO",
                "YOLO siap digunakan"
            )


        } catch (e: Exception) {

            Log.e(
                "SMARTGLASSES_YOLO",
                "Gagal membuat YOLO",
                e
            )
        }
    }


    // =========================================================
    // CAMERA STATE
    // =========================================================

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {

        when (code) {

            ICameraStateCallBack.State.OPENED -> {

                Log.d(
                    "SMARTGLASSES_CAMERA",
                    "C270 berhasil dibuka"
                )


                getCurrentCamera()
                    ?.addPreviewDataCallBack(
                        previewDataCallback
                    )
            }


            ICameraStateCallBack.State.CLOSED -> {

                Log.d(
                    "SMARTGLASSES_CAMERA",
                    "Kamera ditutup"
                )
            }


            ICameraStateCallBack.State.ERROR -> {

                Log.e(
                    "SMARTGLASSES_CAMERA",
                    "Error kamera: $msg"
                )
            }
        }
    }


    // =========================================================
    // DESTROY VIEW
    // =========================================================

    override fun onDestroyView() {

        getCurrentCamera()
            ?.removePreviewDataCallBack(
                previewDataCallback
            )


        inferenceExecutor.shutdown()


        yoloDetector?.close()

        yoloDetector = null


        rootView = null

        cameraView = null

        cameraContainer = null


        super.onDestroyView()
    }
}