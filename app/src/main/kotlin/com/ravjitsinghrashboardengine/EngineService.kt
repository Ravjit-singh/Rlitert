package com.ravjitsinghrashboardengine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend

class EngineService : Service() {
    private var apiServer: LocalAiServer? = null
    private var llmEngine: Engine? = null
    private var isEngineReady = false
    private var isInitializing = false
    private var engineCrashLog: String? = null // ⚡ Tracks fatal hardware errors

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        
        // Start the web server immediately so it can stream loading/error status to the UI
        apiServer = LocalAiServer()
        apiServer?.start()
    }

    // ⚡ NEW: We receive the dynamic file path from MainActivity here
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelPath = intent?.getStringExtra("MODEL_PATH")
        
        // Only boot the engine if we received a path, and it isn't already running
        if (modelPath != null && llmEngine == null && !isInitializing) {
            isInitializing = true
            thread {
                loadInferenceEngine(modelPath)
            }
        }
        
        return START_STICKY
    }

    private fun loadInferenceEngine(modelPath: String) {
        try {
            try {
                // Attempt 1: Force GPU Hardware Acceleration
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU()
                )
                val engine = Engine(config)
                engine.initialize()
                llmEngine = engine
            } catch (gpuError: Throwable) {
                gpuError.printStackTrace()
                // Attempt 2: Seamless fallback to CPU if the GPU driver rejects the file
                val cpuConfig = EngineConfig(
                    modelPath = modelPath
                )
                val engine = Engine(cpuConfig)
                engine.initialize()
                llmEngine = engine
            }
            
            isEngineReady = true
        } catch (fatalError: Throwable) {
            // Catch standard exceptions AND fatal OutOfMemoryErrors
            engineCrashLog = fatalError.message ?: fatalError.toString()
            fatalError.printStackTrace()
        } finally {
            isInitializing = false
        }
    }

    private fun startForegroundService() {
        val channelId = "rashboard_engine_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, 
                "Rashboard AI Engine", 
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Rashboard Native Bridge")
            .setContentText("GPU Engine listening on 127.0.0.1:8080")
            .setSmallIcon(android.R.drawable.ic_dialog_info) 
            .build()
            
        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // ⚡ CRITICAL: This is what actually purges the 2.3GB of RAM when the user hits "Terminate & Unload RAM"
        apiServer?.stop()
        llmEngine?.close()
        llmEngine = null
        isEngineReady = false
        super.onDestroy()
    }

    // --- THE INTERNAL WEB SERVER ---
    inner class LocalAiServer : NanoHTTPD(8080) {
        override fun serve(session: IHTTPSession?): Response {
            if (session == null) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Null session")
            }
            
            if (session.method == Method.OPTIONS) {
                val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
                res.addHeader("Access-Control-Allow-Origin", "*")
                res.addHeader("Access-Control-Allow-Headers", "*")
                return res
            }

            if (session.method == Method.POST) {
                // Pipe fatal hardware crashes directly to the UI
                if (engineCrashLog != null) {
                    val errorRes = newFixedLengthResponse(Response.Status.OK, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"[HARDWARE CRASH]: $engineCrashLog\"}}]}\n\ndata: [DONE]")
                    errorRes.addHeader("Access-Control-Allow-Origin", "*")
                    return errorRes
                }

                if (!isEngineReady || llmEngine == null) {
                    val errorRes = newFixedLengthResponse(Response.Status.OK, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"[System]: Engine is still mapping to hardware. Please wait a few seconds and try again.\"}}]}\n\ndata: [DONE]")
                    errorRes.addHeader("Access-Control-Allow-Origin", "*")
                    return errorRes
                }

                val map = HashMap<String, String>()
                try {
                    session.parseBody(map)
                    val postData = map["postData"] ?: "{}"
                    val json = JSONObject(postData)
                    val messages = json.getJSONArray("messages")
                    
                    var gemmaPrompt = ""
                    for (i in 0 until messages.length()) {
                        val msg = messages.getJSONObject(i)
                        val role = msg.getString("role")
                        val content = msg.getString("content")
                        gemmaPrompt += "<start_of_turn>${role}\n${content}<end_of_turn>\n"
                    }
                    gemmaPrompt += "<start_of_turn>model\n"

                    val conversation = llmEngine!!.createConversation()
                    val rawOutput = conversation.sendMessage(gemmaPrompt).toString()
                    conversation.close()
                    
                    var safeOutput = rawOutput
                    if (safeOutput.startsWith("Message(")) {
                        val match = Regex("text=(.*?)(, role=|\\]|\\))").find(safeOutput)
                        if (match != null) safeOutput = match.groupValues[1]
                    }
                    
                    safeOutput = safeOutput.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
                    val streamResponse = """
                        data: {"choices":[{"delta":{"content":"$safeOutput"}}]}
                        
                        data: [DONE]
                    """.trimIndent()
                    
                    val res = newFixedLengthResponse(Response.Status.OK, "text/event-stream", streamResponse)
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                    
                } catch (e: Exception) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Parse Error: ${e.message}")
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Endpoint Not Found")
        }
    }
}
