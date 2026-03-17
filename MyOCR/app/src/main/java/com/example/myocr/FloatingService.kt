package com.example.myocr // PENTING: Sesuaikan dengan nama package kamu!

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import android.content.pm.ServiceInfo
import android.util.Log

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: Button
    private lateinit var textBubble: TextView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buatUIMelayang()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wajib untuk Android 8+ agar tidak di-kill sistem
        buatNotifikasiForeground()

        try {
            // Menerima "Izin Rekam Layar" dari MainActivity
            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED

            // Cara aman mengambil data di Android terbaru
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("DATA")
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)

                // ==========================================================
                // INI YANG KURANG TADI: DAFTARKAN CALLBACK UNTUK ANDROID 14+
                // ==========================================================
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        mediaProjection = null
                    }
                }
                mediaProjection?.registerCallback(callback, Handler(Looper.getMainLooper()))
                // ==========================================================

                Toast.makeText(this, "Penerjemah Siap Digunakan!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MY_OCR_LOG", "Gagal inisiasi: ${e.message}")
            Toast.makeText(this, "Error: Gagal memulai perekam", Toast.LENGTH_LONG).show()
        }

        return START_NOT_STICKY
    }

    @SuppressLint("SetTextI18n")
    private fun buatUIMelayang() {
        // 1. TOMBOL MELAYANG
        floatingButton = Button(this).apply {
            text = "T"
            setBackgroundColor(android.graphics.Color.parseColor("#FF6200EE"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                mulaiProsesTerjemahan()
            }
        }

        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        windowManager.addView(floatingButton, btnParams)

        // 2. BALON TEKS HASIL TERJEMAHAN (Awalnya disembunyikan)
        textBubble = TextView(this).apply {
            text = "Menerjemahkan..."
            setBackgroundColor(android.graphics.Color.parseColor("#CC000000")) // Hitam transparan
            setTextColor(android.graphics.Color.WHITE)
            setPadding(32, 32, 32, 32)
            textSize = 16f
            visibility = View.GONE

            // Klik balon teks untuk menyembunyikannya
            setOnClickListener { visibility = View.GONE }
        }

        val textParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM // Muncul di bawah layar
        }

        windowManager.addView(textBubble, textParams)
    }

    private fun mulaiProsesTerjemahan() {
        if (mediaProjection == null) {
            Toast.makeText(this, "Izin rekam layar belum ada!", Toast.LENGTH_SHORT).show()
            return
        }

        // Sembunyikan tombol & teks agar tidak ikut terfoto
        floatingButton.visibility = View.GONE
        textBubble.visibility = View.GONE

        // PERBAIKAN: Ganti jeda jadi 500 milidetik (setengah detik)
        // Agar tombol "T" benar-benar lenyap sebelum layar difoto!
        Handler(Looper.getMainLooper()).postDelayed({
            jepretLayar()
        }, 500)
    }

    @SuppressLint("WrongConstant")
    private fun jepretLayar() {
        try {
            val metrics = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            if (mediaProjection == null) {
                munculkanPesanDiLayar("Izin rekam layar hilang, silakan aktifkan ulang dari awal!")
                return
            }

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            var gambarSudahDiambil = false // Kunci agar tidak dobel proses

            imageReader?.setOnImageAvailableListener({ reader ->
                if (gambarSudahDiambil) return@setOnImageAvailableListener

                try {
                    val image = reader.acquireLatestImage()
                    // Pastikan gambarnya benar-benar ada
                    if (image != null) {
                        gambarSudahDiambil = true // Kunci!

                        val planes = image.planes
                        val buffer: ByteBuffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmapWidth = width + rowPadding / pixelStride
                        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)

                        val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)

                        image.close()
                        hentikanJepretLayar()

                        Handler(Looper.getMainLooper()).post {
                            floatingButton.visibility = View.VISIBLE
                            textBubble.text = "Sedang membaca teks Korea..."
                            textBubble.visibility = View.VISIBLE

                            bacaTeksDariBitmap(finalBitmap)
                        }
                    }
                } catch (e: Throwable) { // Pakai Throwable untuk menangkap semua jenis error (termasuk RAM penuh)
                    e.printStackTrace()
                    hentikanJepretLayar()
                    munculkanPesanDiLayar("Gagal memproses foto: ${e.message}")
                }
            }, Handler(Looper.getMainLooper()))

            // === TIMEOUT 2 DETIK ===
            // Kalau dalam 2 detik layar gagal difoto, paksa munculkan tombol T lagi!
            Handler(Looper.getMainLooper()).postDelayed({
                if (!gambarSudahDiambil) {
                    hentikanJepretLayar()
                    munculkanPesanDiLayar("Layar gagal difoto (Timeout/Kosong). Coba klik lagi!")
                }
            }, 2000)

        } catch (e: Throwable) {
            e.printStackTrace()
            munculkanPesanDiLayar("Kamera error: ${e.message}")
        }
    }

    // Fungsi bantuan agar kita tidak capek nulis kode berulang
    private fun munculkanPesanDiLayar(pesan: String) {
        Handler(Looper.getMainLooper()).post {
            floatingButton.visibility = View.VISIBLE
            textBubble.text = pesan
            textBubble.visibility = View.VISIBLE
            tutupBalonTeksOtomatis()
        }
    }

    private fun hentikanJepretLayar() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null // Bersihkan dari memori

            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close() // INI KUNCI AGAR BISA DIPAKAI BERKALI-KALI!
            imageReader = null // Bersihkan dari memori
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // === MATA: GOOGLE ML KIT ===
    private fun bacaTeksDariBitmap(bitmap: Bitmap) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isNotBlank()) {
                        textBubble.text = "Menerjemahkan dengan Qwen..."
                        kirimKeLaptop(visionText.text)
                    } else {
                        textBubble.text = "(Tidak ada teks Korea yang terdeteksi)"
                        tutupBalonTeksOtomatis()
                    }
                }
                .addOnFailureListener {
                    textBubble.text = "Gagal membaca gambar!"
                    tutupBalonTeksOtomatis()
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // === OTAK: OLLAMA DI LAPTOP ===
    private fun kirimKeLaptop(teksKorea: String) {
        val ipLaptop = "192.168.100.109" // IP LAPTOP KAMU

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // Prompt galak supaya Qwen nggak cerewet ngasih "Note"
        val prompt = "Terjemahkan teks komik/manhwa Korea ini ke bahasa Indonesia gaul dan santai. HANYA berikan hasil terjemahannya saja, TANPA penjelasan, catatan, atau basa-basi apa pun. Abaikan teks aneh atau watermark. Teks: \n$teksKorea"

        val jsonBody = JSONObject().apply {
            put("model", "qwen2.5:3b") // Pake AI yang ringan
            put("prompt", prompt)
            put("stream", false)
        }.toString()

        val request = Request.Builder()
            .url("http://$ipLaptop:11434/api/generate")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val hasilRaw = response.body?.string()
                        if (hasilRaw != null) {
                            val jsonObject = JSONObject(hasilRaw)
                            val teksBersih = jsonObject.getString("response")

                            // Tampilkan di layar HP
                            Handler(Looper.getMainLooper()).post {
                                textBubble.text = teksBersih
                            }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post { textBubble.text = "Laptop menolak: Error ${response.code}" }
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { textBubble.text = "Gagal konek ke Laptop (Pastikan IP dan Ollama nyala)" }
            }
        }.start()
    }

    private fun tutupBalonTeksOtomatis() {
        Handler(Looper.getMainLooper()).postDelayed({
            textBubble.visibility = View.GONE
        }, 3000) // Tutup setelah 3 detik jika gagal/kosong
    }

    private fun buatNotifikasiForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("MANHWA_CHANNEL", "Manhwa Translator", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, "MANHWA_CHANNEL")
                .setContentTitle("Translator Aktif")
                .setContentText("Aplikasi siap menerjemahkan layar")
                .setSmallIcon(android.R.drawable.ic_dialog_info) // Ganti icon bawaan biar aman
                .build()

            // PERBAIKAN DI SINI: Wajib menyebutkan tipe Media Projection untuk Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingButton.isInitialized) windowManager.removeView(floatingButton)
        if (::textBubble.isInitialized) windowManager.removeView(textBubble)
        mediaProjection?.stop()
    }
}