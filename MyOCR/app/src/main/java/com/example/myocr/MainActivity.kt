package com.example.myocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myocr.ui.theme.MyOCRTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyOCRTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TampilanUtama()
                }
            }
        }
    }
}

@Composable
fun TampilanUtama() {
    val context = LocalContext.current
    val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    // Ini "Penangkap" jawaban saat user mengklik "Mulai Merekam" di layar HP
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, FloatingService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data) // PERBAIKAN: Gunakan data aslinya langsung!
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            Toast.makeText(context, "Izin rekam layar ditolak!", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Translator Manhwa AI", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = {
            // 1. Cek dulu apakah izin Tombol Melayang (Overlay) sudah aktif?
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
                Toast.makeText(context, "Izinkan Overlay lalu kembali ke aplikasi", Toast.LENGTH_LONG).show()
            } else {
                // 2. Kalau Overlay sudah aktif, minta izin Rekam Layar!
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }) {
            Text("Aktifkan Penerjemah")
        }
    }
}