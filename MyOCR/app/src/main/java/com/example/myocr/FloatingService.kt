package com.example.myocr

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.util.DisplayMetrics
import android.util.Log
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

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: Button
    private lateinit var textBubble: TextView

    private var mediaProjection: MediaProjection? = null

    // ===================================================================
    // KUNCI PERBAIKAN: VirtualDisplay & ImageReader dibuat SEKALI SAJA
    // dan TIDAK PERNAH di-release sampai service mati.
    // ===================================================================
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0

    // Flag untuk mencegah dobel proses jepretan
    private var isSedangMemproses = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buatUIMelayang()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        buatNotifikasiForeground()

        try {
            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED

            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("DATA")
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)

                // Daftarkan callback — wajib untuk Android 14+
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        Log.w("MY_OCR_LOG", "MediaProjection di-stop oleh sistem!")
                        mediaProjection = null
                        // Bersihkan VirtualDisplay jika proyeksi dihentikan paksa
                        bersihkanVirtualDisplay()
                    }
                }, Handler(Looper.getMainLooper()))

                // Ambil ukuran layar & siapkan VirtualDisplay SEKALI di sini
                inisialisasiVirtualDisplay()

                Toast.makeText(this, "Penerjemah Siap! Tap T untuk terjemahkan.", Toast.LENGTH_SHORT).show()

            } else {
                Toast.makeText(this, "Izin rekam layar ditolak!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MY_OCR_LOG", "Gagal inisiasi: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }

        return START_NOT_STICKY
    }

    /**
     * Dibuat SATU KALI saat service start.
     * VirtualDisplay ini akan terus hidup selama service hidup.
     */
    @SuppressLint("WrongConstant")
    private fun inisialisasiVirtualDisplay() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        // ImageReader dengan maxImages = 2 (buffer aman)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ManhwaTranslator",
            screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )

        Log.d("MY_OCR_LOG", "VirtualDisplay siap: ${screenWidth}x${screenHeight}")
    }

    @SuppressLint("SetTextI18n")
    private fun buatUIMelayang() {
        // --- TOMBOL MELAYANG ---
        floatingButton = Button(this).apply {
            text = "T"
            setBackgroundColor(android.graphics.Color.parseColor("#FF6200EE"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                if (!isSedangMemproses) {
                    mulaiProsesTerjemahan()
                } else {
                    Toast.makeText(this@FloatingService, "Tunggu, sedang memproses...", Toast.LENGTH_SHORT).show()
                }
            }
            setOnLongClickListener {
                Toast.makeText(this@FloatingService, "Penerjemah dimatikan!", Toast.LENGTH_SHORT).show()
                stopSelf()
                true
            }
        }

        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }
        windowManager.addView(floatingButton, btnParams)

        // --- BALON TEKS ---
        textBubble = TextView(this).apply {
            text = "Menerjemahkan..."
            setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
            setTextColor(android.graphics.Color.WHITE)
            setPadding(32, 32, 32, 32)
            textSize = 16f
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }

        val textParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        windowManager.addView(textBubble, textParams)
    }

    private fun mulaiProsesTerjemahan() {
        if (mediaProjection == null || virtualDisplay == null || imageReader == null) {
            munculkanPesanDiLayar("Izin rekam layar hilang! Aktifkan ulang dari aplikasi.")
            return
        }

        isSedangMemproses = true

        // Sembunyikan UI agar tidak ikut terfoto
        floatingButton.visibility = View.GONE
        textBubble.visibility = View.GONE

        // Tunggu UI benar-benar hilang dari layar
        Handler(Looper.getMainLooper()).postDelayed({
            jepretLayar()
        }, 300)
    }

    private fun jepretLayar() {
        // Timeout: jika dalam 3 detik tidak ada gambar, batalkan
        val timeoutRunnable = Runnable {
            if (isSedangMemproses) {
                Log.w("MY_OCR_LOG", "Timeout saat menunggu gambar")
                selesaiMemproses()
                munculkanPesanDiLayar("Gagal memfoto layar (Timeout). Coba lagi!")
            }
        }
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(timeoutRunnable, 3000)

        // ===================================================================
        // KUNCI PERBAIKAN: Kita TIDAK membuat ImageReader baru.
        // Kita cukup AMBIL gambar dari ImageReader yang sudah ada.
        // ===================================================================
        try {
            // Coba ambil gambar yang sudah ada di buffer
            val image = imageReader?.acquireLatestImage()

            if (image != null) {
                handler.removeCallbacks(timeoutRunnable)
                Log.d("MY_OCR_LOG", "Gambar berhasil diambil dari buffer!")

                try {
                    val planes = image.planes
                    val buffer: ByteBuffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val bitmapWidth = screenWidth + rowPadding / pixelStride
                    val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)

                    val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    bitmap.recycle()

                    image.close() // Wajib! Biar slot buffer kosong lagi

                    Handler(Looper.getMainLooper()).post {
                        selesaiMemproses()
                        textBubble.text = "Sedang membaca teks Korea..."
                        textBubble.visibility = View.VISIBLE
                        bacaTeksDariBitmap(finalBitmap)
                    }
                } catch (e: Exception) {
                    image.close()
                    handler.removeCallbacks(timeoutRunnable)
                    selesaiMemproses()
                    munculkanPesanDiLayar("Gagal proses gambar: ${e.message}")
                }
            } else {
                // Buffer kosong — tunggu sebentar lalu coba lagi SEKALI
                Log.w("MY_OCR_LOG", "Buffer kosong, retry dalam 200ms...")
                Handler(Looper.getMainLooper()).postDelayed({
                    handler.removeCallbacks(timeoutRunnable)
                    val retryImage = imageReader?.acquireLatestImage()
                    if (retryImage != null) {
                        handler.removeCallbacks(timeoutRunnable)
                        try {
                            val planes = retryImage.planes
                            val buffer: ByteBuffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * screenWidth
                            val bitmapWidth = screenWidth + rowPadding / pixelStride
                            val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(buffer)
                            val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                            bitmap.recycle()
                            retryImage.close()

                            selesaiMemproses()
                            textBubble.text = "Sedang membaca teks Korea..."
                            textBubble.visibility = View.VISIBLE
                            bacaTeksDariBitmap(finalBitmap)
                        } catch (e: Exception) {
                            retryImage.close()
                            selesaiMemproses()
                            munculkanPesanDiLayar("Gagal retry: ${e.message}")
                        }
                    } else {
                        selesaiMemproses()
                        munculkanPesanDiLayar("Buffer layar kosong. Coba tap T lagi!")
                    }
                }, 200)
            }
        } catch (e: Exception) {
            Log.e("MY_OCR_LOG", "Error jepret: ${e.message}")
            handler.removeCallbacks(timeoutRunnable)
            selesaiMemproses()
            munculkanPesanDiLayar("Error kamera: ${e.message}")
        }
    }

    private fun selesaiMemproses() {
        isSedangMemproses = false
        floatingButton.visibility = View.VISIBLE
    }

    private fun munculkanPesanDiLayar(pesan: String) {
        Handler(Looper.getMainLooper()).post {
            selesaiMemproses()
            textBubble.text = pesan
            textBubble.visibility = View.VISIBLE
            tutupBalonTeksOtomatis()
        }
    }

    // === MATA: GOOGLE ML KIT ===
    private fun bacaTeksDariBitmap(bitmap: Bitmap) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    bitmap.recycle()
                    if (visionText.text.isNotBlank()) {
                        textBubble.text = "Menerjemahkan dengan AI..."
                        kirimKeLaptop(visionText.text)
                    } else {
                        textBubble.text = "(Tidak ada teks Korea yang terdeteksi)"
                        tutupBalonTeksOtomatis()
                    }
                }
                .addOnFailureListener { e ->
                    bitmap.recycle()
                    textBubble.text = "Gagal membaca teks: ${e.message}"
                    tutupBalonTeksOtomatis()
                }
        } catch (e: Exception) {
            bitmap.recycle()
            munculkanPesanDiLayar("OCR Error: ${e.message}")
        }
    }

    // === OTAK: OLLAMA DI LAPTOP ===
    private fun kirimKeLaptop(teksKorea: String) {
        val ipLaptop = "192.168.100.109" // Ganti dengan IP laptop kamu

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val prompt = """
            Kamu adalah mesin terjemah otomatis. Tugasmu HANYA menerjemahkan, tidak lebih.
            ATURAN KERAS:
            - TERJEMAHKAN SEMUA teks dialog/bubble ke bahasa Indonesia santai
            - DILARANG KERAS menulis kalimat seperti "Maaf", "Tidak dapat", "Sebagai AI", atau alasan apapun
            - DILARANG memberi catatan, penjelasan, atau komentar apapun
            - Abaikan watermark dan URL
            - Jika teks pendek atau aneh, tetap terjemahkan apa adanya
            - Output HANYA hasil terjemahan, titik.

            Teks:
            $teksKorea
            """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("model", "qwen2.5:3b")
            put("prompt", prompt)
            put("stream", false)
            put("options", JSONObject().apply {
                put("num_predict", 2048)  // Bebas panjang output
                put("temperature", 0.3)   // Lebih konsisten/akurat
            })
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
                            val teksBersih = JSONObject(hasilRaw).getString("response")
                            Handler(Looper.getMainLooper()).post {
                                textBubble.text = teksBersih
                            }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            textBubble.text = "Laptop menolak: Error ${response.code}"
                            tutupBalonTeksOtomatis()
                        }
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    textBubble.text = "Gagal konek ke Laptop (Cek IP & pastikan Ollama nyala)"
                    tutupBalonTeksOtomatis()
                }
            }
        }.start()
    }

    private fun tutupBalonTeksOtomatis() {
        Handler(Looper.getMainLooper()).postDelayed({
            textBubble.visibility = View.GONE
        }, 4000)
    }

    private fun bersihkanVirtualDisplay() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e("MY_OCR_LOG", "Error cleanup: ${e.message}")
        }
    }

    private fun buatNotifikasiForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "MANHWA_CHANNEL",
                "Manhwa Translator",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, "MANHWA_CHANNEL")
                .setContentTitle("Translator Aktif")
                .setContentText("Tap tombol T di layar untuk menerjemahkan")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bersihkanVirtualDisplay()
        mediaProjection?.stop()
        mediaProjection = null
        if (::floatingButton.isInitialized) windowManager.removeView(floatingButton)
        if (::textBubble.isInitialized) windowManager.removeView(textBubble)
        Log.d("MY_OCR_LOG", "FloatingService destroyed, semua resource dibersihkan.")
    }
}