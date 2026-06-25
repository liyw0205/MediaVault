package com.mediavault.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mediavault.data.MediaStore
import com.mediavault.databinding.ActivitySettingsBinding
import com.mediavault.remote.RemoteClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: MediaStore

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        store.appendLocalRootUri(uri.toString())
        binding.localRootsEdit.setText(store.readLocalRootUris().joinToString("\n"))
        Toast.makeText(this, "已添加目录", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = MediaStore(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(com.mediavault.R.string.settings)

        binding.localRootsEdit.setText(store.readLocalRootUris().joinToString("\n"))
        binding.remotesEdit.setText(prettyRemotes(store.readRemotesJsonText()))

        binding.pickLocalRootBtn.setOnClickListener { pickTree.launch(null) }
        binding.saveBtn.setOnClickListener { save() }
        binding.testWebDavBtn.setOnClickListener { testWebDav() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun prettyRemotes(raw: String): String = raw.trim()

    private fun save() {
        try {
            val roots = binding.localRootsEdit.text.toString().lines()
                .map { it.trim() }.filter { it.isNotEmpty() }
            store.writeLocalRootUris(roots)
            store.writeRemotesJsonText(binding.remotesEdit.text.toString().trim())
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun testWebDav() {
        lifecycleScope.launch {
            try {
                val text = binding.remotesEdit.text.toString().trim()
                val cfg = RemoteClients.firstWebDav(text) ?: error("JSON 中无 webdav 配置")
                val msg = withContext(Dispatchers.IO) {
                    val client = RemoteClients.create(cfg)
                    val test = client.testConnection()
                    val list = client.list("").take(5).joinToString { it.name }
                    "$test\n示例: $list"
                }
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}