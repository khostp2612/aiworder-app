package com.aicustomer.engine

import android.app.Service
import android.content.Intent
import android.os.*
import android.util.Log
import com.aicustomer.voice.VadDetector

class SherpaService : Service() {

    companion object {
        const val TAG = "SherpaService"
        const val MSG_LOAD_MODELS = 1
        const val MSG_RECOGNIZE = 2
        const val MSG_UNLOAD = 3
        const val MSG_READY = 4
        const val MSG_RESULT = 5
        const val MSG_ERROR = 6
    }

    private var sttEngine: SttEngine? = null
    private var vadDetector: VadDetector? = null
    private var isInitRunning = false

    inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_LOAD_MODELS -> handleLoadModels(msg)
                MSG_RECOGNIZE -> handleRecognize(msg)
                MSG_UNLOAD -> handleUnload()
                else -> super.handleMessage(msg)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(IncomingHandler()).binder

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "SherpaService created in process: ${android.os.Process.myPid()}")
    }

    private fun handleLoadModels(msg: Message) {
        if (isInitRunning) return
        isInitRunning = true

        val client = msg.replyTo ?: return

        Thread {
            try {
                Log.e(TAG, "Starting STT/VAD load...")

                Log.e(TAG, "Loading VAD models FIRST...")
                vadDetector = VadDetector(this)
                val vadLoaded = try {
                    vadDetector?.initBlocking()
                    Log.e(TAG, "VAD init: ${vadDetector?.isInitialized()}")
                    vadDetector!!.isInitialized()
                } catch (e: Exception) {
                    Log.e(TAG, "VAD load error", e)
                    false
                }

                Log.e(TAG, "Loading STT models...")
                sttEngine = SttEngine(this)
                val sttLoaded = try {
                    sttEngine?.loadBlocking()
                    Log.e(TAG, "STT loaded: ${sttEngine?.isLoaded()}")
                    sttEngine!!.isLoaded()
                } catch (e: Exception) {
                    Log.e(TAG, "STT load error", e)
                    false
                }

                val ready = sttLoaded || vadLoaded
                Log.e(TAG, "Load complete: STT=$sttLoaded VAD=$vadLoaded")

                val reply = Message.obtain(null, MSG_READY).apply {
                    arg1 = if (ready) 1 else 0
                    obj = "STT=$sttLoaded VAD=$vadLoaded"
                }
                client.send(reply)
            } catch (e: Exception) {
                Log.e(TAG, "Init thread crashed", e)
                try {
                    client.send(Message.obtain(null, MSG_ERROR).apply {
                        obj = e.message ?: "init failed"
                    })
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun handleRecognize(msg: Message) {
        val client = msg.replyTo ?: return

        Thread {
            try {
                val audioData = msg.obj as? ShortArray ?: run {
                    client.send(Message.obtain(null, MSG_ERROR).apply { obj = "No audio data" })
                    return@Thread
                }

                Log.e(TAG, "Recognizing ${audioData.size} samples...")
                val floatSamples = FloatArray(audioData.size) { audioData[it] / 32768.0f }

                sttEngine?.acceptWaveform(floatSamples)
                val result = sttEngine?.getFinalResultAndReset() ?: ""

                Log.e(TAG, "Result: '$result'")
                client.send(Message.obtain(null, MSG_RESULT).apply {
                    obj = result
                    arg1 = 1
                })
            } catch (e: Exception) {
                Log.e(TAG, "Recognition error", e)
                try {
                    client.send(Message.obtain(null, MSG_ERROR).apply {
                        obj = e.message ?: "recognition failed"
                    })
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun handleUnload() {
        sttEngine?.unload()
        vadDetector?.destroy()
        sttEngine = null
        vadDetector = null
        isInitRunning = false
        Log.e(TAG, "Unloaded")
    }

    override fun onDestroy() {
        handleUnload()
        super.onDestroy()
    }
}

