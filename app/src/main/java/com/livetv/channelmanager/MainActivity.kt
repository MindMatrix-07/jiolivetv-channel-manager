package com.livetv.channelmanager

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.livetv.channelmanager.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var channels: MutableList<Channel>
    private lateinit var adapter: ChannelAdapter

    private val addChannelLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val ch = result.data?.getParcelableExtra<Channel>("channel") ?: return@registerForActivityResult
            val editIdx = result.data?.getIntExtra("edit_index", -1) ?: -1

            if (editIdx >= 0) { channels[editIdx] = ch; adapter.notifyItemChanged(editIdx) }
            else { channels.add(ch); channels.sortBy { it.channelNumber }; adapter.notifyDataSetChanged() }

            ChannelStorage.saveChannels(this, channels)
            updateEmptyState()
            setLoading(true)

            // Push to Supabase → instantly visible on TV
            lifecycleScope.launch {
                val ok = SupabaseSync.upsertChannel(ch)
                setLoading(false)
                showSnack(
                    if (ok) {
                        "✅ \"${ch.name}\" is now live on TV!"
                    } else {
                        "⚠️ Saved locally — ${SupabaseSync.lastError ?: "Supabase sync failed"}"
                    }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Start with locally cached channels, then refresh from Supabase
        channels = ChannelStorage.loadChannels(this)

        adapter = ChannelAdapter(
            items = channels,
            onEdit = { ch, idx ->
                addChannelLauncher.launch(
                    Intent(this, AddChannelActivity::class.java)
                        .putExtra("channel", ch).putExtra("edit_index", idx)
                )
            },
            onDelete = { idx ->
                val ch = channels[idx]
                MaterialAlertDialogBuilder(this)
                    .setTitle("Remove Channel")
                    .setMessage("Delete \"${ch.name}\" from TV?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        channels.removeAt(idx); adapter.notifyItemRemoved(idx)
                        ChannelStorage.saveChannels(this, channels)
                        updateEmptyState()
                        lifecycleScope.launch {
                            val ok = SupabaseSync.deleteChannel(ch.channelNumber)
                            showSnack(
                                if (ok) {
                                    "🗑 \"${ch.name}\" removed from TV."
                                } else {
                                    "Deleted locally only — ${SupabaseSync.lastError ?: "Supabase sync failed"}"
                                }
                            )
                        }
                    }.show()
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        binding.fabAdd.setOnClickListener {
            addChannelLauncher.launch(Intent(this, AddChannelActivity::class.java))
        }

        // "Sync" button — refresh local list from Supabase
        binding.btnExport.text = "Refresh from TV"
        binding.btnExport.setOnClickListener { refreshFromSupabase() }

        updateEmptyState()
        refreshFromSupabase() // Always load latest on startup
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_jio -> {
                startActivity(Intent(this, JioIntegrationActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshFromSupabase() {
        setLoading(true)
        lifecycleScope.launch {
            val remote = SupabaseSync.fetchChannels()
            setLoading(false)
            if (remote.isNotEmpty()) {
                channels.clear()
                channels.addAll(remote)
                adapter.notifyDataSetChanged()
                ChannelStorage.saveChannels(this@MainActivity, channels)
                updateEmptyState()
                showSnack("✅ ${remote.size} channels loaded from TV database")
            } else {
                showSnack("Could not reach Supabase — ${SupabaseSync.lastError ?: "showing cached channels"}")
            }
        }
    }

    private fun setLoading(show: Boolean) {
        binding.root.findViewById<View?>(android.R.id.progress)?.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun updateEmptyState() {
        binding.tvEmpty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (channels.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showSnack(msg: String) = Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
