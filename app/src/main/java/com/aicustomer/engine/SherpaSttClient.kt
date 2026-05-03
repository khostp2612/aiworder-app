package com.aicustomer.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log

class SherpaSttClient(private val context: Context) {

    companion object {
        private const val TAG = "SherpaSttClient"
    }

    private var serviceMessenger: Messenger? = null
    private var isBound = false
    private var onReadyCallback: ((Boolean, String) -> Unit)? = null
    private var onResultCallback: ((String, Boolean) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var pendingLoad = false

    private val clientMessenger = Messenger(IncomingHandler())

    inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                SherpaService.MSG_READY -> {
                    val ready = msg.arg1 == 1
                    val info = msg.obj as? String ?: ""
                    Log.i(TAG, "Service ready: $ready ($info)")
                    onReadyCallback?.invoke(ready, info)
                }
                SherpaService.MSG_RESULT -> {
                    val text = msg.obj as? String ?: ""
                    val isFinal = msg.arg1 == 1
                    Log.i(TAG, "Result: '$text' final=$isFinal")
                    onResultCallback?.invoke(text, isFinal)
                }
                SherpaService.MSG_ERROR -> {
                    val error = msg.obj as? String ?: "unknown"
                    Log.e(TAG, "Service error: $error")
                    onErrorCallback?.invoke(error)
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceMessenger = Messenger(service)
            isBound = true
            Log.i(TAG, "Service connected, pendingLoad=$pendingLoad")
            if (pendingLoad) {
                pendingLoad = false
                sendLoadModels()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            isBound = false
            Log.i(TAG, "Service disconnected")
        }
    }

    fun bind(
        onReady: ((Boolean, String) -> Unit)? = null,
        onResult: ((String, Boolean) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        this.onReadyCallback = onReady
        this.onResultCallback = onResult
        this.onErrorCallback = onError

        val intent = Intent(context, SherpaService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "Binding to SherpaService...")
    }

    fun loadModels() {
        if (isBound && serviceMessenger != null) {
            sendLoadModels()
        } else {
            pendingLoad = true
            Log.i(TAG, "Deferring loadModels until service connects")
        }
    }

    private fun sendLoadModels() {
        val msg = Message.obtain(null, SherpaService.MSG_LOAD_MODELS).apply {
            replyTo = clientMessenger
        }
        try {
            serviceMessenger?.send(msg)
            Log.i(TAG, "Sent loadModels message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send loadModels", e)
        }
    }

    fun recognize(audioData: ShortArray) {
        if (!isBound || serviceMessenger == null) return
        val msg = Message.obtain(null, SherpaService.MSG_RECOGNIZE).apply {
            obj = audioData
            replyTo = clientMessenger
        }
        try {
            serviceMessenger?.send(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send recognize", e)
        }
    }

    fun unbind() {
        pendingLoad = false
        if (isBound) {
            try {
                val msg = Message.obtain(null, SherpaService.MSG_UNLOAD)
                serviceMessenger?.send(msg)
            } catch (_: Exception) {}
            context.unbindService(serviceConnection)
            isBound = false
        }
    }
}
