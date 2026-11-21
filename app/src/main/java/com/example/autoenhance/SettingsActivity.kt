package com.example.autoenhance

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

class SettingsActivity : ComponentActivity() {

    private lateinit var tvVersion: TextView
    private lateinit var tvRepo: TextView
    private lateinit var swAutoCheck: Switch
    private lateinit var btnCheck: Button
    private lateinit var tvUpdateStatus: TextView
    private lateinit var cardUpdate: LinearLayout
    private lateinit var tvNewVersion: TextView
    private lateinit var tvNotes: TextView
    private lateinit var btnInstall: Button
    private lateinit var progressUpdate: ProgressBar
    private lateinit var btnReleases: TextView

    private var available: UpdateManager.Info? = null
    private var downloadedFile: File? = null
    private var busy = false

    private val allowInstall =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (UpdateManager.canInstall(this)) {
                startDownloadAndInstall()
            } else {
                toast("Install permission dewa hoyni.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tvVersion = findViewById(R.id.tvVersion)
        tvRepo = findViewById(R.id.tvRepo)
        swAutoCheck = findViewById(R.id.swAutoCheck)
        btnCheck = findViewById(R.id.btnCheck)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        cardUpdate = findViewById(R.id.cardUpdate)
        tvNewVersion = findViewById(R.id.tvNewVersion)
        tvNotes = findViewById(R.id.tvNotes)
        btnInstall = findViewById(R.id.btnInstall)
        progressUpdate = findViewById(R.id.progressUpdate)
        btnReleases = findViewById(R.id.btnReleases)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        val code = UpdateManager.currentVersionCode(this)
        tvVersion.text = "Version ${UpdateManager.currentVersionName(this)}  (build $code)"
        val repo = UpdateManager.repo()
        tvRepo.text = if (repo.isBlank()) "Update source: set kora nai" else "Update source: github.com/$repo"
        btnReleases.visibility = if (repo.isBlank()) View.GONE else View.VISIBLE
        btnReleases.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$repo/releases")))
        }

        swAutoCheck.isChecked = UpdateManager.isAutoCheckEnabled(this)
        swAutoCheck.setOnCheckedChangeListener { _, on -> UpdateManager.setAutoCheckEnabled(this, on) }

        btnCheck.setOnClickListener { checkForUpdates() }
        btnInstall.setOnClickListener { onInstallClicked() }

        checkForUpdates()   // page khulle auto check
    }

    // ---------------------------------------------------------------- check

    private fun checkForUpdates() {
        if (busy) return
        busy = true
        btnCheck.isEnabled = false
        cardUpdate.visibility = View.GONE
        tvUpdateStatus.text = "Update check korchi..."
        Thread {
            val r = UpdateManager.checkLatest(this)
            UpdateManager.saveResult(this, r)
            runOnUiThread {
                busy = false
                btnCheck.isEnabled = true
                when (r) {
                    is UpdateManager.CheckResult.UpToDate ->
                        tvUpdateStatus.text = "✅ Apni latest version e achen (${r.latestName})."
                    is UpdateManager.CheckResult.Error ->
                        tvUpdateStatus.text = "⚠ ${r.message}"
                    is UpdateManager.CheckResult.Available -> showAvailable(r.info)
                }
            }
        }.start()
    }

    private fun showAvailable(info: UpdateManager.Info) {
        available = info
        downloadedFile = null
        tvUpdateStatus.text = "🎉 Notun update paoa gese!"
        cardUpdate.visibility = View.VISIBLE
        tvNewVersion.text = "${info.versionName}  •  %.1f MB".format(info.size / 1048576f)
        tvNotes.text = info.notes.ifBlank { "Bug fix ar improvement." }
        btnInstall.text = "⬇ Download & Install"
        btnInstall.isEnabled = true
        progressUpdate.visibility = View.INVISIBLE
    }

    // ---------------------------------------------------------------- download + install

    private fun onInstallClicked() {
        if (busy) return
        val file = downloadedFile
        if (file != null && file.exists()) {
            installFile(file)
            return
        }
        if (!UpdateManager.canInstall(this)) {
            toast("Prothome 'Install unknown apps' allow korun, tarpor back chapun.")
            val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            allowInstall.launch(i)
            return
        }
        startDownloadAndInstall()
    }

    private fun startDownloadAndInstall() {
        val info = available ?: return
        if (busy) return
        busy = true
        btnInstall.isEnabled = false
        btnCheck.isEnabled = false
        progressUpdate.visibility = View.VISIBLE
        progressUpdate.progress = 0
        tvUpdateStatus.text = "Download hochche... 0%"

        Thread {
            val result = try {
                val f = UpdateManager.download(this, info) { pct ->
                    runOnUiThread {
                        progressUpdate.progress = pct
                        tvUpdateStatus.text = "Download hochche... $pct%"
                    }
                }
                Pair(f, UpdateManager.verify(this, f))
            } catch (e: Exception) {
                Pair(null, "Download error: ${e.message}")
            }
            runOnUiThread {
                busy = false
                btnCheck.isEnabled = true
                btnInstall.isEnabled = true
                val (file, err) = result
                when {
                    file != null && err == null -> {
                        downloadedFile = file
                        tvUpdateStatus.text = "Download done. Install screen khulchhe..."
                        btnInstall.text = "📲 Install"
                        installFile(file)
                    }
                    err == "SIGNATURE" -> {
                        tvUpdateStatus.text =
                            "⚠ Ei update ager version er sathe sign mile ni (ager build onno key e chilo). " +
                                "Ekbar purano app uninstall kore notun APK GitHub Releases theke install korun. " +
                                "Tarpor theke auto update thik kaj korbe."
                        btnInstall.text = "⬇ Download & Install"
                    }
                    else -> {
                        tvUpdateStatus.text = "⚠ $err"
                        btnInstall.text = "⬇ Abar try korun"
                    }
                }
            }
        }.start()
    }

    private fun installFile(file: File) {
        try {
            UpdateManager.install(this, file)
        } catch (e: Exception) {
            tvUpdateStatus.text = "⚠ Install screen khola gelo na: ${e.message}"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
