package com.translator.gemini

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val REQUEST_SCREEN_CAPTURE = 1001
    private lateinit var apiKeyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        apiKeyInput = EditText(this).apply {
            hint = "Paste Google AI Studio API Key"
            val savedKey = getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("API_KEY", "")
            setText(savedKey)
        }

        val startButton = Button(this).apply {
            text = "Start Floating Translator"
            setOnClickListener {
                val key = apiKeyInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please enter your API key first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("API_KEY", key).apply()

                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    return@setOnClickListener
                }

                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
            }
        }

        layout.addView(apiKeyInput)
        layout.addView(startButton)
        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            val key = apiKeyInput.text.toString().trim()
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
                putExtra("API_KEY", key)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            moveTaskToBack(true)
        }
    }
}

