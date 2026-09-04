package com.mengchong.desktop

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页：填 DeepSeek 的 API Key 和你的昵称。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val settings = SettingsStore(SharedPrefKVStore(this))
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val nicknameInput = findViewById<EditText>(R.id.nicknameInput)
        val volcAppIdInput = findViewById<EditText>(R.id.volcAppIdInput)
        val volcTokenInput = findViewById<EditText>(R.id.volcTokenInput)

        apiKeyInput.setText(settings.apiKey)
        nicknameInput.setText(settings.nickname)
        volcAppIdInput.setText(settings.volcAppId)
        volcTokenInput.setText(settings.volcToken)

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            settings.apiKey = apiKeyInput.text.toString().trim()
            settings.nickname = nicknameInput.text.toString().trim()
            settings.volcAppId = volcAppIdInput.text.toString().trim()
            settings.volcToken = volcTokenInput.text.toString().trim()
            Toast.makeText(this, "保存好啦~", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
