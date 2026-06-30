package com.ravjitsinghrashboardengine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initialize the Native Bridge UI
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // Attach the Kotlin functions to the Javascript UI
        webView.addJavascriptInterface(RashboardBridge(), "Android")
        setContentView(webView)

        // 2. Inject the sleek Tailwind UI (Matches your index.html perfectly)
        val uiHtml = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                <script src="https://cdn.tailwindcss.com"></script>
                <script>
                    tailwind.config = {
                        theme: {
                            extend: {
                                colors: { base: '#000000', surface: '#09090b', elevated: '#18181b', accent: '#ffffff', muted: '#a1a1aa' },
                                boxShadow: { 'elite': 'inset 0 1px 0 0 rgba(255, 255, 255, 0.05), inset 0 0 0 1px rgba(255, 255, 255, 0.02), 0 10px 40px -10px rgba(0,0,0,0.5)' }
                            }
                        }
                    }
                </script>
                <style>
                    body { background-color: #000; color: #fff; font-family: -apple-system, sans-serif; -webkit-tap-highlight-color: transparent; }
                    .glass-card { background: linear-gradient(180deg, #09090b, #000); border-radius: 24px; box-shadow: var(--tw-shadow-elite); border: 1px solid rgba(255,255,255,0.05); }
                </style>
            </head>
            <body class="flex items-center justify-center h-screen p-6 overflow-hidden">
                <div class="glass-card w-full max-w-sm p-8 flex flex-col items-center text-center relative overflow-hidden">
                    <div class="absolute -top-20 -left-20 w-40 h-40 bg-blue-500/20 blur-[60px] rounded-full pointer-events-none"></div>
                    
                    <div class="w-16 h-16 rounded-[20px] bg-black border border-white/10 shadow-[0_0_15px_rgba(59,130,246,0.3)] flex items-center justify-center mb-5 relative z-10">
                        <svg class="w-8 h-8 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"></path></svg>
                    </div>
                    
                    <h1 class="text-2xl font-bold tracking-tight mb-1 relative z-10">LiteRT Engine</h1>
                    <p id="status-text" class="text-sm font-medium text-muted mb-8 relative z-10">System Offline</p>

                    <div class="w-full space-y-3 relative z-10">
                        <button id="btn-start" onclick="startEngine()" class="w-full bg-white text-black font-bold py-3.5 rounded-xl active:scale-[0.98] transition-transform shadow-[0_4px_14px_rgba(255,255,255,0.2)]">
                            INITIALIZE PROTOCOL
                        </button>
                        <button id="btn-stop" onclick="stopEngine()" class="w-full bg-red-500/10 text-red-500 border border-red-500/20 font-bold py-3.5 rounded-xl active:scale-[0.98] transition-transform hidden">
                            TERMINATE & UNLOAD RAM
                        </button>
                    </div>
                    <p id="log-text" class="text-[10px] text-muted mt-6 font-mono break-all opacity-70 relative z-10">Awaiting user command...</p>
                </div>

                <script>
                    function setStatus(status, log, isRunning) {
                        document.getElementById('status-text').innerHTML = status;
                        document.getElementById('log-text').innerText = log;
                        if (isRunning) {
                            document.getElementById('btn-start').classList.add('hidden');
                            document.getElementById('btn-stop').classList.remove('hidden');
                        } else {
                            document.getElementById('btn-start').classList.remove('hidden');
                            document.getElementById('btn-stop').classList.add('hidden');
                        }
                    }

                    function startEngine() {
                        setStatus('<span class="text-amber-500 animate-pulse">Checking Storage...</span>', 'Requesting hardware access...', false);
                        window.Android.startProtocol();
                    }

                    function stopEngine() {
                        setStatus('<span class="text-red-500">Purging RAM...</span>', 'Sending kill signal to kernel...', true);
                        window.Android.stopProtocol();
                    }

                    function updateFromAndroid(state, message) {
                        if (state === 'LIVE') {
                            setStatus('<span class="text-emerald-500 drop-shadow-[0_0_8px_rgba(16,185,129,0.8)]">● API ONLINE :8080</span>', message, true);
                        } else if (state === 'ERROR') {
                            setStatus('<span class="text-red-500">System Fault</span>', message, false);
                        } else if (state === 'OFFLINE') {
                            setStatus('System Offline', message, false);
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, uiHtml, "text/html", "UTF-8", null)
    }

    // --- THE NATIVE BRIDGE ---
    inner class RashboardBridge {
        @JavascriptInterface
        fun startProtocol() {
            runOnUiThread {
                if (checkStoragePermissions()) {
                    executeModelBoot()
                }
            }
        }

        @JavascriptInterface
        fun stopProtocol() {
            runOnUiThread {
                // Instantly kill the background service to free up the 2.3GB of RAM
                stopService(Intent(this@MainActivity, EngineService::class.java))
                updateUI("OFFLINE", "Service terminated. 2.3GB RAM successfully freed.")
            }
        }
    }

    private fun executeModelBoot() {
        // Create the custom directory in the user's Downloads folder
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val rashboardDir = File(downloadsDir, "RashboardModels")
        
        if (!rashboardDir.exists()) {
            rashboardDir.mkdirs()
        }

        // ⚡ NEW: Automatically scan for ANY file that ends with .litertlm
        val targetModel = rashboardDir.listFiles { _, name -> 
            name.endsWith(".litertlm", ignoreCase = true) 
        }?.firstOrNull()

        if (targetModel == null || !targetModel.exists()) {
            updateUI("ERROR", "Model not found. Place your .litertlm model file inside your device's Downloads/RashboardModels/ folder.")
            return
        }

        // Pass the absolute file path to the service
        val intent = Intent(this, EngineService::class.java)
        intent.putExtra("MODEL_PATH", targetModel.absolutePath)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateUI("LIVE", "Model mapped from ${targetModel.name}. GPU Inference Engine Active.")
    }

    private fun checkStoragePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:" + packageName)
                startActivity(intent)
                updateUI("ERROR", "Please grant 'All Files Access' to read the 2.3GB model, then try again.")
                return false
            }
            return true
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100)
                updateUI("ERROR", "Please accept the storage permission prompt and try again.")
                return false
            }
            return true
        }
    }

    private fun updateUI(state: String, message: String) {
        val safeMessage = message.replace("'", "\\'")
        webView.evaluateJavascript("javascript:updateFromAndroid('$state', '$safeMessage');", null)
    }
}
