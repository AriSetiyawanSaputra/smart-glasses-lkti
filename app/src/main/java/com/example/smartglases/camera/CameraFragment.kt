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


                        // =================================================
                        // KIRIM PERSON KE OVERLAY
                        //
                        // Hanya class person
                        // =================================================

                        activity?.runOnUiThread {

                            try {

                                (
                                        activity as MainActivity
                                        ).updateDetections(
                                        personDetections
                                    )

                            } catch (e: Exception) {

                                Log.e(
                                    "SMARTGLASSES_OVERLAY",
                                    "Gagal mengirim person ke overlay",
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
//
// Kalibrasi person yang sudah selesai:
//
// 1 meter -> 357.87 px
// 2 meter -> 219.82 px
// 3 meter -> 153.88 px
// 4 meter -> 80.02 px
//
// Input:
// boxWidth = lebar bounding box person
//
// Output:
// perkiraan jarak dalam meter
// =========================================================

    private fun estimatePersonDistance(
        boxWidth: Float
    ): Float {

        // =====================================================
        // DATA KALIBRASI
        // =====================================================

        val w1m = 357.87f
        val w2m = 219.82f
        val w3m = 153.88f
        val w4m = 110.00f


        // =====================================================
        // <= 1 METER
        //
        // Kalau box lebih besar dari nilai 1 meter,
        // anggap person berada pada 1 meter atau lebih dekat.
        // =====================================================

        if (boxWidth >= w1m) {

            return 1.0f
        }


        // =====================================================
        // >= 4 METER
        //
        // Kalau box lebih kecil dari nilai 4 meter,
        // anggap person berada pada 4 meter atau lebih jauh.
        // =====================================================

        if (boxWidth <= w4m) {

            return 4.0f
        }


        // =====================================================
        // 1 - 2 METER
        // =====================================================

        if (boxWidth >= w2m) {

            return interpolate(

                value = boxWidth,

                valueA = w1m,
                distanceA = 1.0f,

                valueB = w2m,
                distanceB = 2.0f
            )
        }


        // =====================================================
        // 2 - 3 METER
        // =====================================================

        if (boxWidth >= w3m) {

            return interpolate(

                value = boxWidth,

                valueA = w2m,
                distanceA = 2.0f,

                valueB = w3m,
                distanceB = 3.0f
            )
        }


        // =====================================================
        // 3 - 4 METER
        // =====================================================

        return interpolate(

            value = boxWidth,

            valueA = w3m,
            distanceA = 3.0f,

            valueB = w4m,
            distanceB = 4.0f
        )
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