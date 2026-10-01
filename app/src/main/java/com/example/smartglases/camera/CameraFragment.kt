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
import java.util.ArrayDeque
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
    //
    // Kamera sekitar 15 FPS
    //
    // 10 frame ≈ 0,67 detik
    // =========================================================

    private val inferenceFrameInterval = 10


    // =========================================================
    // CONFIDENCE
    // =========================================================

    private val confidenceThreshold = 0.25f


    // =========================================================
    // ROI
    //
    // Frame kamera = 640 x 480
    // =========================================================

    private val roiLeft = 200f
    private val roiTop = 80f
    private val roiRight = 440f
    private val roiBottom = 480f


    // =========================================================
    // SMOOTHING JARAK
    //
    // Simpan 3 hasil jarak terakhir
    // =========================================================

    private val distanceHistory =
        ArrayDeque<Float>()

    private val distanceHistorySize = 3


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
                    "Mengirim Bitmap ke YOLO: " +
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

                        // =========================================
                        // INFERENCE
                        // =========================================

                        val detections =
                            detector.detect(bitmap)


                        Log.d(
                            "SMARTGLASSES_AI",
                            "Jumlah deteksi: " +
                                    detections.size
                        )

                        // =========================================================
// PERSON
// classId 0 = person
// =========================================================

                        val personDetections =
                            detections.filter { detection ->

                                detection.classId == 0 &&
                                        detection.confidence >=
                                        confidenceThreshold
                            }

                        // =========================================================
// KALIBRASI PERSON
// Hanya person dalam ROI
// Pilih confidence tertinggi
// =========================================================

                        val calibrationPerson =
                            personDetections
                                .filter { detection ->
                                    isInsideRoi(detection)
                                }
                                .maxByOrNull { detection ->
                                    detection.confidence
                                }


                        if (calibrationPerson != null) {

                            val detection =
                                calibrationPerson


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


                            Log.d(
                                "SMARTGLASSES_PERSON_CALIBRATION",
                                String.format(
                                    "Person: " +
                                            "confidence=%.2f, " +
                                            "x1=%.2f, " +
                                            "y1=%.2f, " +
                                            "x2=%.2f, " +
                                            "y2=%.2f, " +
                                            "width=%.2f, " +
                                            "height=%.2f, " +
                                            "center=(%.2f, %.2f)",
                                    detection.confidence,
                                    detection.x1,
                                    detection.y1,
                                    detection.x2,
                                    detection.y2,
                                    boxWidth,
                                    boxHeight,
                                    centerX,
                                    centerY
                                )
                            )
                        }

// =========================================================
// BICYCLE
// classId 1 = bicycle
// =========================================================

                        val bicycleDetections =
                            detections.filter { detection ->

                                detection.classId == 1 &&
                                        detection.confidence >=
                                        confidenceThreshold
                            }


// =========================================================
// SEMUA OBJECT YANG AKAN DITAMPILKAN
//
// person + bicycle
// =========================================================

                        val objectDetections =
                            detections.filter { detection ->

                                (
                                        detection.classId == 0 ||
                                                detection.classId == 1
                                        ) &&
                                        detection.confidence >=
                                        confidenceThreshold
                            }

                        Log.d(
                            "SMARTGLASSES_OBJECT",
                            "Person=${personDetections.size}, " +
                                    "Bicycle=${bicycleDetections.size}"
                        )

                        Log.d(
                            "SMARTGLASSES_PERSON",
                            "Jumlah person: " +
                                    personDetections.size
                        )

                        Log.d(
                            "SMARTGLASSES_BICYCLE",
                            "Jumlah bicycle: ${bicycleDetections.size}"
                        )

                        for (detection in bicycleDetections) {

                            val width =
                                detection.x2 - detection.x1

                            val height =
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


                            Log.d(
                                "SMARTGLASSES_BICYCLE_CALIBRATION",
                                String.format(
                                    "Bicycle: " +
                                            "confidence=%.2f, " +
                                            "x1=%.2f, " +
                                            "y1=%.2f, " +
                                            "x2=%.2f, " +
                                            "y2=%.2f, " +
                                            "width=%.2f, " +
                                            "height=%.2f, " +
                                            "center=(%.2f, %.2f)",
                                    detection.confidence,
                                    detection.x1,
                                    detection.y1,
                                    detection.x2,
                                    detection.y2,
                                    width,
                                    height,
                                    centerX,
                                    centerY
                                )
                            )
                        }

                        // =========================================
                        // KIRIM PERSON KE OVERLAY
                        // =========================================

                        requireActivity().runOnUiThread {

                            try {

                                (
                                        requireActivity()
                                                as MainActivity
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


                        // =========================================
                        // FILTER PERSON DALAM ROI
                        // =========================================

                        val roiPersons =
                            personDetections.filter { detection ->

                                isInsideRoi(
                                    detection
                                )
                            }


                        Log.d(
                            "SMARTGLASSES_ROI",
                            "Jumlah person dalam ROI = " +
                                    roiPersons.size
                        )


                        // =========================================
                        // TIDAK ADA PERSON DALAM ROI
                        // =========================================

                        if (roiPersons.isEmpty()) {

                            // -----------------------------------------
                            // Bersihkan smoothing
                            // -----------------------------------------

                            distanceHistory.clear()


                            Log.d(
                                "SMARTGLASSES_OBSTACLE",
                                "Tidak ada person dalam ROI"
                            )


                            // -----------------------------------------
                            // Reset TTS
                            // -----------------------------------------

                            requireActivity()
                                .runOnUiThread {

                                    try {

                                        (
                                                requireActivity()
                                                        as MainActivity
                                                ).resetObstacleAnnouncement()

                                    } catch (e: Exception) {

                                        Log.e(
                                            "SMARTGLASSES_TTS",
                                            "Gagal reset status TTS",
                                            e
                                        )
                                    }
                                }


                        } else {

                            // =========================================
                            // PILIH PERSON TERDEKAT
                            // =========================================

                            val nearestPerson = roiPersons
                                .map { detection ->
                                    val boxWidth = detection.x2 - detection.x1
                                    val distance = estimatePersonDistance(boxWidth)

                                    Log.d(
                                        "SMARTGLASSES_DISTANCE_DEBUG",
                                        String.format(
                                            "CALCULATION: boxWidth=%.2f -> distance=%.2f",
                                            boxWidth,
                                            distance
                                        )
                                    )

                                    Pair(detection, distance)
                                }
                                .minByOrNull { it.second }


                            // =========================================
                            // PERSON TERDEKAT ADA
                            // =========================================

                            if (nearestPerson != null) {

                                val detection =
                                    nearestPerson.first

                                val rawDistance =
                                    nearestPerson.second


                                // =====================================
                                // SMOOTHING
                                // =====================================

                                val smoothedDistance =
                                    smoothDistance(
                                        rawDistance
                                    )


                                // =====================================
                                // STATUS OBSTACLE
                                // =====================================

                                val obstacleStatus =
                                    getObstacleStatus(
                                        smoothedDistance
                                    )


                                // =====================================
                                // BOTTOM CENTER
                                // =====================================

                                val bottomCenterX =
                                    (
                                            detection.x1 +
                                                    detection.x2
                                            ) / 2f

                                val bottomCenterY =
                                    detection.y2


                                // =====================================
                                // DEBUG BOX
                                // =====================================

                                Log.d(
                                    "SMARTGLASSES_DISTANCE_DEBUG",
                                    String.format(
                                        "Selected person: " +
                                                "confidence=%.2f, " +
                                                "x1=%.2f, " +
                                                "y1=%.2f, " +
                                                "x2=%.2f, " +
                                                "y2=%.2f",
                                        detection.confidence,
                                        detection.x1,
                                        detection.y1,
                                        detection.x2,
                                        detection.y2
                                    )
                                )




                                // =====================================
                                // DEBUG JARAK
                                // =====================================

                                Log.d(
                                    "SMARTGLASSES_DISTANCE_DEBUG",
                                    String.format(
                                        "DISTANCE: " +
                                                "raw=%.2f, " +
                                                "smooth=%.2f",
                                        rawDistance,
                                        smoothedDistance
                                    )
                                )


                                // =====================================
                                // LOG PERSON
                                // =====================================

                                Log.d(
                                    "SMARTGLASSES_PERSON",
                                    String.format(
                                        "Person dalam ROI: " +
                                                "confidence=%.2f, " +
                                                "bottomCenter=(%.2f, %.2f)",
                                        detection.confidence,
                                        bottomCenterX,
                                        bottomCenterY
                                    )
                                )


                                // =====================================
                                // LOG JARAK
                                // =====================================

                                Log.d(
                                    "SMARTGLASSES_DISTANCE",
                                    String.format(
                                        "Person dalam ROI: " +
                                                "raw=%.2f meter, " +
                                                "smooth=%.2f meter",
                                        rawDistance,
                                        smoothedDistance
                                    )
                                )


                                // =====================================
                                // LOG OBSTACLE
                                // =====================================

                                Log.d(
                                    "SMARTGLASSES_OBSTACLE",
                                    String.format(
                                        "Person dalam ROI: " +
                                                "jarak=%.2f meter, " +
                                                "status=%s",
                                        smoothedDistance,
                                        obstacleStatus
                                    )
                                )


                                // =====================================
                                // TTS
                                // =====================================

                                requireActivity()
                                    .runOnUiThread {

                                        try {

                                            (
                                                    requireActivity()
                                                            as MainActivity
                                                    ).speakObstacle(
                                                    smoothedDistance
                                                )

                                        } catch (e: Exception) {

                                            Log.e(
                                                "SMARTGLASSES_TTS",
                                                "Gagal menjalankan TTS",
                                                e
                                            )
                                        }
                                    }
                            }
                        }


                    } catch (e: Exception) {

                        Log.e(
                            "SMARTGLASSES_AI",
                            "Inference YOLO gagal",
                            e
                        )

                    } finally {

                        // =========================================
                        // SELESAI
                        // =========================================

                        inferenceRunning = false


                        // =========================================
                        // HAPUS BITMAP
                        // =========================================

                        bitmap.recycle()
                    }
                }
            }
        }


    // =========================================================
    // SMOOTH DISTANCE
    // =========================================================

    private fun smoothDistance(
        newDistance: Float
    ): Float {

        // =========================================
        // Tambahkan nilai baru
        // =========================================

        distanceHistory.addLast(
            newDistance
        )


        // =========================================
        // Kalau lebih dari 3 nilai,
        // hapus nilai paling lama
        // =========================================

        while (
            distanceHistory.size >
            distanceHistorySize
        ) {

            distanceHistory.removeFirst()
        }


        // =========================================
        // Urutkan untuk mengambil median
        // =========================================

        val sortedValues =
            distanceHistory
                .toList()
                .sorted()


        // =========================================
        // Median
        // =========================================

        return sortedValues[
            sortedValues.size / 2
        ]
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
    //
    // KALIBRASI TERBARU
    //
    // 1 m -> 46.91
    // 2 m -> 111.85
    // 3 m -> 135.22
    // 4 m -> 145.02
    // =========================================================

    private fun estimatePersonDistance(
        boxWidth: Float
    ): Float {

        // =========================================================
        // KALIBRASI PERSON TERBARU
        //
        // 1 meter  -> 357.87 px
        // 2 meter  -> 219.82 px
        // 3 meter  -> 153.88 px
        // 4 meter  -> 80.02 px
        // =========================================================

        val w1m = 357.87f
        val w2m = 219.82f
        val w3m = 153.88f
        val w4m = 80.02f


        // =========================================================
        // SANGAT DEKAT / <= 1 METER
        // =========================================================

        if (boxWidth >= w1m) {
            return 1.0f
        }


        // =========================================================
        // >= 4 METER
        // =========================================================

        if (boxWidth <= w4m) {
            return 4.0f
        }


        // =========================================================
        // 1 - 2 METER
        // =========================================================

        if (boxWidth >= w2m) {

            return interpolate(
                boxWidth,
                w1m,
                1.0f,
                w2m,
                2.0f
            )
        }


        // =========================================================
        // 2 - 3 METER
        // =========================================================

        if (boxWidth >= w3m) {

            return interpolate(
                boxWidth,
                w2m,
                2.0f,
                w3m,
                3.0f
            )
        }


        // =========================================================
        // 3 - 4 METER
        // =========================================================

        return interpolate(
            boxWidth,
            w3m,
            3.0f,
            w4m,
            4.0f
        )
    }


    // =========================================================
    // INTERPOLASI
    // =========================================================

    private fun interpolate(
        value: Float,
        valueA: Float,
        distanceA: Float,
        valueB: Float,
        distanceB: Float
    ): Float {

        val ratio =
            (
                    value - valueA
                    ) /
                    (
                            valueB - valueA
                            )


        return distanceA +
                ratio *
                (
                        distanceB - distanceA
                        )
    }


    // =========================================================
    // STATUS OBSTACLE
    // =========================================================

    private fun getObstacleStatus(
        distance: Float
    ): String {

        if (
            distance.isNaN() ||
            distance.isInfinite()
        ) {
            return "UNKNOWN"
        }

        if (distance <= 1.0f) {
            return "DANGER"
        }

        if (distance <= 3.0f) {
            return "WARNING"
        }

        if (distance <= 4.0f) {
            return "FAR"
        }

        return "SAFE"
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


        distanceHistory.clear()


        yoloDetector?.close()

        yoloDetector = null


        rootView = null

        cameraView = null

        cameraContainer = null


        super.onDestroyView()
    }
}