package com.westhecool.webtoon

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        btnDownload = findViewById(R.id.btnDownload)
        progressBar = findViewById(R.id.progressBar)
        tvLog = findViewById(R.id.tvLog)

        tvLog.movementMethod = ScrollingMovementMethod()

        btnDownload.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                startDownload(url)
            } else {
                appendLog("Please enter a URL")
            }
        }
    }

    private fun startDownload(url: String) {
        btnDownload.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvLog.text = ""
        appendLog("Starting process for: $url")

        lifecycleScope.launch {
            try {
                appendLog("Fetching comic info...")
                val comicInfo = WebtoonScraper.getComicInfo(url)
                appendLog("Found: ${comicInfo.title} by ${comicInfo.author}")

                appendLog("Fetching chapter list (this might take a while)...")
                val chapters = WebtoonScraper.getChapterList(url)
                appendLog("Found ${chapters.size} chapters.")

                if (chapters.isEmpty()) {
                    appendLog("No chapters found. Check the URL.")
                    return@launch
                }

                appendLog("Starting EPUB creation...")
                val epubFile = EpubGenerator.createEpub(this@MainActivity, comicInfo, chapters) { msg, progress ->
                    runOnUiThread {
                        // Avoid flooding logs, maybe just update status if message is repetitive
                        if (!msg.startsWith("Downloading Chapter")) {
                             appendLog(msg)
                        } else {
                            // Maybe just update last line or just append?
                            // Let's just append for now, user can see progress.
                            // But 100 chapters = 100 lines. That's fine.
                            tvLog.append("$msg\n")
                        }
                        progressBar.progress = progress
                    }
                }

                progressBar.progress = 100
                appendLog("Success! EPUB saved to: ${epubFile.absolutePath}")

            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
                e.printStackTrace()
            } finally {
                btnDownload.isEnabled = true
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun appendLog(msg: String) {
        tvLog.append("$msg\n")
    }
}
