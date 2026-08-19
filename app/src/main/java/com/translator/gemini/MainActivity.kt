package com.translator.gemini

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var startButton: Button
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
                putExtra("EXTRA_RESULT_DATA", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish()
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 80, 50, 50)
        }

        apiKeyInput = EditText(this).apply {
            hint = "Enter Google AI Studio API Key"
            val prefs = getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)
            setText(prefs.getString("api_key", ""))
        }

        startButton = Button(this).apply {
            text = "Start Floating Translator"
            setOnClickListener {
                val key = apiKeyInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please enter an API Key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("api_key", key)
                    .apply()

                checkPermissionsAndStart()
            }
        }

        layout.addView(apiKeyInput)
        layout.addView(startButton)
        setContentView(layout)
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Enable 'Display over other apps' and try again", Toast.LENGTH_LONG).show()
            return
        }

        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }
}
