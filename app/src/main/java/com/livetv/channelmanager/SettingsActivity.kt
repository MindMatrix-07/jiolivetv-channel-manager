package com.livetv.channelmanager

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        val etEpgRepos = findViewById<TextInputEditText>(R.id.etEpgRepos)
        val btnSave = findViewById<MaterialButton>(R.id.btnSaveSettings)

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedRepos = prefs.getString("epg_repos", "")
        etEpgRepos.setText(savedRepos)

        btnSave.setOnClickListener {
            val reposStr = etEpgRepos.text.toString().trim()
            prefs.edit().putString("epg_repos", reposStr).apply()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
