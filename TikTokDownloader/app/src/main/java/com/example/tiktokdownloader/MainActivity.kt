package com.example.tiktokdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.tiktokdownloader.api.TikTokApiService
import com.example.tiktokdownloader.api.TikTokData
import com.example.tiktokdownloader.databinding.ActivityMainBinding
import com.example.tiktokdownloader.utils.DownloadUtil
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val apiService = TikTokApiService()
    private var lastVideoData: TikTokData? = null

    companion object {
        private const val PREFS_NAME = "tiktok_downloader_prefs"
        private const val KEY_THEME = "key_theme_mode"
        private const val THEME_AUTO = 0
        private const val THEME_DARK = 1
        private const val THEME_LIGHT = 2

        // Ссылка для пожертвований (Boosty, CloudTips, чаевые Тинькофф/Сбер и т.д.)
        const val DONATION_URL = "https://pay.cloudtips.ru/p/f35243af"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            startDownloadProcess()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applySavedTheme()

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupThemeToggle()
        setupListeners()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    /**
     * Reads saved theme from SharedPreferences and sets the night mode.
     */
    private fun applySavedTheme() {
        val themeMode = prefs.getInt(KEY_THEME, THEME_AUTO)
        when (themeMode) {
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /**
     * Configures the 3-state bottom toggle for Auto / Dark / Light theme.
     */
    private fun setupThemeToggle() {
        val currentTheme = prefs.getInt(KEY_THEME, THEME_AUTO)
        when (currentTheme) {
            THEME_DARK -> binding.toggleThemeGroup.check(R.id.btnThemeDark)
            THEME_LIGHT -> binding.toggleThemeGroup.check(R.id.btnThemeLight)
            else -> binding.toggleThemeGroup.check(R.id.btnThemeAuto)
        }

        binding.toggleThemeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newTheme = when (checkedId) {
                    R.id.btnThemeDark -> THEME_DARK
                    R.id.btnThemeLight -> THEME_LIGHT
                    else -> THEME_AUTO
                }

                if (newTheme != prefs.getInt(KEY_THEME, THEME_AUTO)) {
                    prefs.edit().putInt(KEY_THEME, newTheme).apply()
                    when (newTheme) {
                        THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        // Quick paste button placed directly next to the input field
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                binding.etUrl.setText(text)
                Toast.makeText(this, "Ссылка вставлена", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            }
        }

        // Main download button
        binding.btnDownload.setOnClickListener {
            checkPermissionsAndDownload()
        }

        // Open in gallery / Downloads folder
        binding.btnViewVideo.setOnClickListener {
            openDownloadsFolder()
        }

        // Share button
        binding.btnShare.setOnClickListener {
            lastVideoData?.playUrl?.let { url ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TikTok Video (No Watermark)")
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                startActivity(Intent.createChooser(shareIntent, "Поделиться видео"))
            }
        }

        // Support Developer buttons
        binding.btnSupport.setOnClickListener {
            showSupportDialog()
        }
        binding.btnSupportHeader.setOnClickListener {
            showSupportDialog()
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                val extractedUrl = apiService.extractTikTokUrl(sharedText)
                if (extractedUrl != null) {
                    binding.etUrl.setText(extractedUrl)
                    checkPermissionsAndDownload()
                } else {
                    binding.etUrl.setText(sharedText)
                }
            }
        }
    }

    private fun checkPermissionsAndDownload() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startDownloadProcess()
        }
    }

    private fun startDownloadProcess() {
        val input = binding.etUrl.text?.toString()?.trim() ?: ""
        val tiktokUrl = apiService.extractTikTokUrl(input)

        if (tiktokUrl == null) {
            binding.tilUrl.error = getString(R.string.error_invalid_url)
            return
        }
        binding.tilUrl.error = null

        binding.progressIndicator.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = false
        binding.tvStatus.text = getString(R.string.status_fetching_info)
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.cardPreview.visibility = View.GONE

        lifecycleScope.launch {
            val result = apiService.fetchVideoData(tiktokUrl)

            binding.progressIndicator.visibility = View.GONE
            binding.btnDownload.isEnabled = true

            result.onSuccess { data ->
                lastVideoData = data
                displayVideoPreview(data)

                val playUrl = data.playUrl
                if (!playUrl.isNullOrEmpty()) {
                    DownloadUtil.downloadVideo(
                        context = this@MainActivity,
                        videoUrl = playUrl,
                        videoId = data.id,
                        title = data.title
                    )

                    binding.tvStatus.text = getString(R.string.status_success)
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                    Toast.makeText(this@MainActivity, "Загрузка начата", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvStatus.text = getString(R.string.error_fetch_failed)
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error))
                }
            }.onFailure { error ->
                binding.tvStatus.text = "Ошибка: ${error.localizedMessage ?: getString(R.string.error_fetch_failed)}"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error))
            }
        }
    }

    private fun displayVideoPreview(data: TikTokData) {
        binding.cardPreview.visibility = View.VISIBLE

        val authorName = data.author?.let { "@${it.uniqueId ?: it.nickname ?: ""}" } ?: ""
        binding.tvAuthor.text = authorName
        binding.tvVideoTitle.text = data.title?.ifEmpty { "TikTok Video" } ?: "TikTok Video"

        if (!data.cover.isNullOrEmpty()) {
            Glide.with(this)
                .load(data.cover)
                .centerCrop()
                .into(binding.ivCover)
        }
    }

    private fun openDownloadsFolder() {
        try {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
            }
            try {
                startActivity(Intent.createChooser(fallbackIntent, "Открыть папку Загрузки"))
            } catch (ex: Exception) {
                Toast.makeText(this, "Файл сохранен в папку «Загрузки»", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Shows a dialog informing that the app is 100% free and ad-free, with an option to support development.
     */
    private fun showSupportDialog() {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.support_dialog_title)
            .setMessage(R.string.support_dialog_message)
            .setIcon(R.drawable.ic_heart)
            .setNegativeButton(R.string.support_dialog_btn_close, null)

        if (DONATION_URL.isNotEmpty()) {
            builder.setPositiveButton(R.string.support_dialog_btn_support) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            builder.setPositiveButton(R.string.support_dialog_btn_support) { _, _ ->
                Toast.makeText(
                    this,
                    "Спасибо за поддержку! Ссылка разработчика будет доступна в следующем обновлении.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        builder.show()
    }
}
