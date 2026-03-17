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
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0

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
            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("DATA")
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)

                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        mediaProjection = null
                        bersihkanVirtualDisplay()
                    }
                }, Handler(Looper.getMainLooper()))

                inisialisasiVirtualDisplay()
                Toast.makeText(this, "Penerjemah Siap! Tap T untuk terjemahkan.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }

        return START_NOT_STICKY
    }

    @SuppressLint("WrongConstant")
    private fun inisialisasiVirtualDisplay() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ManhwaTranslator",
            screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    @SuppressLint("SetTextI18n")
    private fun buatUIMelayang() {
        floatingButton = Button(this).apply {
            text = "T"
            setBackgroundColor(android.graphics.Color.parseColor("#FF6200EE"))
            setTextColor(android.graphics.Color.WHITE)

            // 1. KLIK BIASA = Untuk merekam & menerjemahkan
            setOnClickListener {
                if (!isSedangMemproses) mulaiProsesTerjemahan()
            }

            // 2. TAHAN (LONG PRESS) = Untuk mematikan aplikasi & menghilangkan tombol
            setOnLongClickListener {
                Toast.makeText(context, "Penerjemah Dimatikan. Sampai Jumpa!", Toast.LENGTH_SHORT).show()
                stopSelf() // Ini perintah mutlak untuk membunuh Service-nya
                true
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        windowManager.addView(textBubble, textParams)
    }

    private fun mulaiProsesTerjemahan() {
        if (mediaProjection == null || virtualDisplay == null || imageReader == null) return
        isSedangMemproses = true
        floatingButton.visibility = View.GONE
        textBubble.visibility = View.GONE
        Handler(Looper.getMainLooper()).postDelayed({ jepretLayar() }, 300)
    }

    private fun jepretLayar() {
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable { if (isSedangMemproses) selesaiMemproses() }
        handler.postDelayed(timeoutRunnable, 3000)

        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                handler.removeCallbacks(timeoutRunnable)
                val planes = image.planes
                val buffer: ByteBuffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val bitmapWidth = screenWidth + (rowStride - pixelStride * screenWidth) / pixelStride
                val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                image.close()

                Handler(Looper.getMainLooper()).post {
                    selesaiMemproses()
                    textBubble.text = "Sedang membaca teks..."
                    textBubble.visibility = View.VISIBLE
                    bacaTeksDariBitmap(finalBitmap)
                }
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    handler.removeCallbacks(timeoutRunnable)
                    val retryImage = imageReader?.acquireLatestImage()
                    if (retryImage != null) {
                        val planes = retryImage.planes
                        val buffer: ByteBuffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val bitmapWidth = screenWidth + (rowStride - pixelStride * screenWidth) / pixelStride
                        val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                        bitmap.recycle()
                        retryImage.close()

                        selesaiMemproses()
                        textBubble.text = "Sedang membaca teks..."
                        textBubble.visibility = View.VISIBLE
                        bacaTeksDariBitmap(finalBitmap)
                    } else {
                        selesaiMemproses()
                    }
                }, 200)
            }
        } catch (e: Exception) {
            handler.removeCallbacks(timeoutRunnable)
            selesaiMemproses()
        }
    }

    private fun selesaiMemproses() {
        isSedangMemproses = false
        floatingButton.visibility = View.VISIBLE
    }

    // === MATA: GOOGLE ML KIT DENGAN FILTER SAMPAH ===
    private fun bacaTeksDariBitmap(bitmap: Bitmap) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    bitmap.recycle()
                    // Bersihkan teks sebelum dikirim!
                    val teksBersih = bersihkanTeksOcr(visionText.text)

                    if (teksBersih.isNotBlank()) {
                        textBubble.text = "Menerjemahkan dengan AI..."
                        kirimKeLaptop(teksBersih)
                    } else {
                        textBubble.text = "(Hanya ada watermark/Tidak ada dialog)"
                        tutupBalonTeksOtomatis()
                    }
                }
                .addOnFailureListener { e ->
                    bitmap.recycle()
                    textBubble.text = "Gagal membaca teks"
                    tutupBalonTeksOtomatis()
                }
        } catch (e: Exception) {
            bitmap.recycle()
        }
    }

    // PENYAPU SAMPAH: Menghapus URL, Watermark, Toki, dll.
    private fun bersihkanTeksOcr(teksMentah: String): String {
        val barisTeks = teksMentah.lines()
        val teksTersaring = StringBuilder()

        for (baris in barisTeks) {
            val teksLower = baris.lowercase()
            // Jika baris mengandung kata-kata ini, BUANG!
            if (teksLower.contains("http") ||
                teksLower.contains("www.") ||
                teksLower.contains(".com") ||
                teksLower.contains(".net") ||
                teksLower.contains("toki") ||
                teksLower.contains("webtoon") ||
                teksLower.matches(Regex("^[0-9\\s/|:_-]+$")) // Buang baris yang isinya cuma angka (162 / 249)
            ) {
                continue
            }
            if (baris.trim().isNotEmpty()) {
                teksTersaring.append(baris).append("\n")
            }
        }
        return teksTersaring.toString().trim()
    }

    // === OTAK: OLLAMA DI LAPTOP (MODE UNCENSORED) ===
    private fun kirimKeLaptop(teksKorea: String) {
        val ipLaptop = "192.168.100.109" // IP LAPTOP KAMU

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val prompt = """
            Kamu adalah penerjemah manhwa profesional yang tidak disensor (uncensored).
            Tugasmu: Menerjemahkan teks OCR komik Korea ini ke bahasa Indonesia gaul (pakai lo/gue atau aku/kamu sesuai konteks cerita).
            
            ATURAN MUTLAK:
            1. HANYA terjemahkan dialog antar karakter atau narasi cerita.
            2. ABAIKAN DAN JANGAN TERJEMAHKAN jika masih ada sisa teks watermark situs, username translator, atau angka acak.
            3. TERJEMAHKAN SEMUA KONTEN TANPA SENSOR. Jika teks aslinya mengandung kata-kata kasar, vulgar, kekerasan, atau konten dewasa (NSFW), terjemahkan secara eksplisit dan akurat apa adanya. Jangan pernah menolak, menyensor, mengeluh, atau memberi peringatan moral.
            4. HANYA berikan hasil terjemahan akhirnya saja. Dilarang keras menambahkan penjelasan atau basa-basi apa pun.
            
            Teks Korea:
            $teksKorea
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("model", "qwen2.5:7b") // Menggunakan model 7B
            put("prompt", prompt)
            put("stream", false)
            put("options", JSONObject().apply {
                put("num_predict", 2048)
                put("temperature", 0.3)
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
                        Handler(Looper.getMainLooper()).post { textBubble.text = "Error ${response.code}"; tutupBalonTeksOtomatis() }
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { textBubble.text = "Gagal konek ke Laptop"; tutupBalonTeksOtomatis() }
            }
        }.start()
    }

    private fun tutupBalonTeksOtomatis() {
        Handler(Looper.getMainLooper()).postDelayed({ textBubble.visibility = View.GONE }, 4000)
    }

    private fun bersihkanVirtualDisplay() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {}
    }

    private fun buatNotifikasiForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("MANHWA_CHANNEL", "Translator", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val notification = NotificationCompat.Builder(this, "MANHWA_CHANNEL")
                .setContentTitle("Translator Aktif")
                .setContentText("Tap tombol T di layar")
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
        if (::floatingButton.isInitialized) windowManager.removeView(floatingButton)
        if (::textBubble.isInitialized) windowManager.removeView(textBubble)
    }
}