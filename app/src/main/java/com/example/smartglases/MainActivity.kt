package com.example.smartglases

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import com.example.smartglases.ai.Detection
import com.example.smartglases.camera.CameraFragment
import com.example.smartglases.camera.DetectionOverlayView

import java.util.Locale


class MainActivity : AppCompatActivity() {

    // =========================================================
    // CAMERA PERMISSION
    // =========================================================

    private val CAMERA_PERMISSION_CODE = 100


    // =========================================================
    // DETECTION OVERLAY
    // =========================================================

    private lateinit var detectionOverlay: DetectionOverlayView


    // =========================================================
    // TEXT TO SPEECH
    // =========================================================

    private var textToSpeech: TextToSpeech? = null

    private var ttsReady = false


    // =========================================================
    // ZONA JARAK YANG SUDAH DIUCAPKAN
    //
    // null = belum ada suara
    // 3 = zona 3 meter
    // 2 = zona 2 meter
    // 1 = zona 1 meter
    // =========================================================

    private var lastSpokenDistanceZone: Int? = null


    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)


        // =====================================================
        // LAYOUT
        // =====================================================

        setContentView(
            R.layout.activity_main
        )


        // =====================================================
        // OVERLAY
        // =====================================================

        detectionOverlay =
            findViewById(
                R.id.detection_overlay
            )


        // =====================================================
        // CAMERA PERMISSION
        // =====================================================

        checkCameraPermission()


        // =====================================================
        // INITIALIZE TTS
        // =====================================================

        initializeTextToSpeech()


        // =====================================================
        // CAMERA FRAGMENT
        // =====================================================

        if (savedInstanceState == null) {

            supportFragmentManager
                .beginTransaction()
                .replace(
                    R.id.camera_fragment_container,
                    CameraFragment()
                )
                .commit()
        }
    }


    // =========================================================
    // UPDATE DETECTIONS
    // =========================================================

    fun updateDetections(
        detections: List<Detection>
    ) {

        detectionOverlay.setDetections(
            detections
        )
    }


    // =========================================================
    // INITIALIZE TEXT TO SPEECH
    // =========================================================

    private fun initializeTextToSpeech() {

        textToSpeech =
            TextToSpeech(
                this
            ) { status ->

                if (
                    status ==
                    TextToSpeech.SUCCESS
                ) {

                    val result =
                        textToSpeech?.setLanguage(
                            Locale(
                                "id",
                                "ID"
                            )
                        )


                    // =================================================
                    // CEK BAHASA
                    // =================================================

                    if (
                        result ==
                        TextToSpeech.LANG_MISSING_DATA ||
                        result ==
                        TextToSpeech.LANG_NOT_SUPPORTED
                    ) {

                        ttsReady = false

                        Log.e(
                            "SMARTGLASSES_TTS",
                            "Bahasa Indonesia tidak tersedia"
                        )

                        return@TextToSpeech
                    }


                    // =================================================
                    // TTS SIAP
                    // =================================================

                    ttsReady = true


                    // =================================================
                    // KECEPATAN BICARA
                    // =================================================

                    textToSpeech?.setSpeechRate(
                        0.95f
                    )


                    // =================================================
                    // PITCH
                    // =================================================

                    textToSpeech?.setPitch(
                        1.0f
                    )


                    Log.d(
                        "SMARTGLASSES_TTS",
                        "TTS siap digunakan"
                    )

                } else {

                    ttsReady = false

                    Log.e(
                        "SMARTGLASSES_TTS",
                        "Gagal menginisialisasi TTS"
                    )
                }
            }
    }


    // =========================================================
    // SPEAK OBSTACLE
    //
    // Sistem menggunakan 3 zona:
    //
    // > 3 m  = diam
    // <= 3 m = "Ada orang di depan."
    // <= 2 m = "Orang 2 meter."
    // <= 1 m = "Hati-hati, orang 1 meter."
    //
    // Suara hanya keluar ketika zona berubah.
    // =========================================================

    fun speakObstacle(
        distance: Float
    ) {

        // =====================================================
        // TTS BELUM SIAP
        // =====================================================

        if (!ttsReady) {
            return
        }


        // =====================================================
        // JARAK TIDAK VALID
        // =====================================================

        if (
            distance.isNaN() ||
            distance.isInfinite()
        ) {

            return
        }


        // =====================================================
        // TENTUKAN ZONA
        // =====================================================

        val currentZone =
            when {

                // ---------------------------------------------
                // 1 METER
                // ---------------------------------------------

                distance <= 1.0f -> {
                    1
                }


                // ---------------------------------------------
                // 2 METER
                // ---------------------------------------------

                distance <= 2.0f -> {
                    2
                }


                // ---------------------------------------------
                // 3 METER
                // ---------------------------------------------

                distance <= 3.0f -> {
                    3
                }


                // ---------------------------------------------
                // LEBIH DARI 3 METER
                // ---------------------------------------------

                else -> {
                    0
                }
            }


        // =====================================================
        // > 3 METER
        //
        // TIDAK ADA SUARA
        // =====================================================

        if (currentZone == 0) {

            lastSpokenDistanceZone = null

            return
        }


        // =====================================================
        // ZONA MASIH SAMA
        //
        // Jangan bicara lagi.
        // =====================================================

        if (
            currentZone ==
            lastSpokenDistanceZone
        ) {

            return
        }


        // =====================================================
        // SIMPAN ZONA TERBARU
        // =====================================================

        lastSpokenDistanceZone =
            currentZone


        // =====================================================
        // PESAN TTS
        // =====================================================

        val message =
            when (currentZone) {

                // ---------------------------------------------
                // 1 METER
                // ---------------------------------------------

                1 -> {
                    "Hati-hati, orang 1 meter."
                }


                // ---------------------------------------------
                // 2 METER
                // ---------------------------------------------

                2 -> {
                    "Orang 2 meter."
                }


                // ---------------------------------------------
                // 3 METER
                // ---------------------------------------------

                3 -> {
                    "Ada orang di depan."
                }


                else -> {
                    return
                }
            }


        // =====================================================
        // LOG
        // =====================================================

        Log.d(
            "SMARTGLASSES_TTS",
            "Zona=$currentZone, " +
                    "jarak=%.2f, " +
                    "pesan=$message".format(
                        Locale.US,
                        distance
                    )
        )


        // =====================================================
        // SPEAK
        // =====================================================

        textToSpeech?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "smartglasses_obstacle_$currentZone"
        )
    }


    // =========================================================
    // RESET TTS
    //
    // Dipanggil ketika tidak ada person dalam ROI.
    //
    // Setelah reset, kalau person masuk ROI lagi,
    // suara akan berbicara lagi.
    // =========================================================

    fun resetObstacleAnnouncement() {

        if (
            lastSpokenDistanceZone != null
        ) {

            Log.d(
                "SMARTGLASSES_TTS",
                "Reset zona TTS"
            )
        }


        lastSpokenDistanceZone = null
    }


    // =========================================================
    // CAMERA PERMISSION
    // =========================================================

    private fun checkCameraPermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA
                ),
                CAMERA_PERMISSION_CODE
            )
        }
    }


    // =========================================================
    // ON DESTROY
    // =========================================================

    override fun onDestroy() {

        // =====================================================
        // STOP TTS
        // =====================================================

        textToSpeech?.stop()


        // =====================================================
        // SHUTDOWN TTS
        // =====================================================

        textToSpeech?.shutdown()

        textToSpeech = null

        ttsReady = false

        lastSpokenDistanceZone = null


        super.onDestroy()
    }
}