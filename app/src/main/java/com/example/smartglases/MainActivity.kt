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
import kotlin.math.roundToInt

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
    // Sistem menggunakan 2 zona peringatan:
    //
    // > 4 m  = diam
    // <= 4 m = "Objek di depan 4 meter"
    // <= 1 m = "Awas objek sangat dekat"
    //
    // Suara hanya keluar ketika zona berubah (1x peringatan).
    // =========================================================

    // =========================================================
    // SPEAK OBSTACLE (DENGAN TRANSLASI NAMA OBJEK)
    // =========================================================

    fun speakObstacle(
        classId: Int,
        distance: Float
    ) {
        // 1. Cek kesiapan TTS dan validitas angka jarak
        if (!ttsReady || distance.isNaN() || distance.isInfinite()) {
            return
        }

        // 2. Tentukan zona (1 = Kritis <=1m, 4 = Peringatan Dini <=4m)
        val zone = when {
            distance <= 1.0f -> 1
            distance <= 4.0f -> 4
            else -> 0
        }

        // Objek lebih dari 4 meter -> reset memori zona
        if (zone == 0) {
            lastSpokenDistanceZone = null
            return
        }

        // Jangan ulangi peringatan pada zona yang sama
        if (lastSpokenDistanceZone == zone) {
            return
        }

        // 3. Mapping ID kelas COCO ke Bahasa Indonesia
        val spokenClassName = when (classId) {
            0 -> "orang"
            1 -> "sepeda"
            2 -> "mobil"
            3 -> "motor"
            4 -> "pesawat"
            5 -> "bus"
            6 -> "kereta"
            7 -> "truk"
            13 -> "bangku"
            56 -> "kursi"
            57 -> "sofa"
            60 -> "meja"
            else -> "objek"
        }

        // 4. Pembulatan jarak ke meter terdekat (contoh: 2.3m -> 2 meter)
        val distanceMeter = distance.roundToInt()

        // 5. Format pesan suara dinamis
        val message = when (zone) {
            4 -> "$spokenClassName di depan $distanceMeter meter"
            1 -> "Awas $spokenClassName sangat dekat"
            else -> return
        }

        Log.d("SMARTGLASSES_TTS", "Zona=$zone, Jarak=$distanceMeter m, Pesan=$message")

        // 6. Eksekusi suara TTS
        textToSpeech?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "smartglasses_obstacle_$zone"
        )

        // 7. Simpan zona yang baru disuarakan
        lastSpokenDistanceZone = zone
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