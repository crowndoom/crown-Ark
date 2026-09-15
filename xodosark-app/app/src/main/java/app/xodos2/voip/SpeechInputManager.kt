package app.xodos2.voip

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Manages speech-to-text input, ported from The412Banner/NewTermux SpeechInputManager.
 * Supports listening callbacks and reactive StateFlow for Compose UI.
 */
class SpeechInputManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechInputManager"

        fun isAvailable(context: Context): Boolean {
            return SpeechRecognizer.isRecognitionAvailable(context)
        }
    }

    interface SpeechCallback {
        fun onResult(text: String)
        fun onError(error: String)
        fun onListeningStarted()
        fun onListeningStopped()
        fun onRmsChanged(rmsdB: Float) {}
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var callback: SpeechCallback? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastResult = MutableStateFlow("")
    val lastResult: StateFlow<String> = _lastResult.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    fun setCallback(cb: SpeechCallback?) {
        this.callback = cb
    }

    fun startListening() {
        if (_isListening.value) return

        if (!isAvailable(context)) {
            val msg = "Speech recognition is not available on this device."
            callback?.onError(msg)
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    callback?.onListeningStarted()
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    _rmsLevel.value = rmsdB
                    callback?.onRmsChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                    callback?.onListeningStopped()
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    val msg = speechErrorToString(error)
                    Log.e(TAG, "Speech recognition error: $msg")
                    callback?.onError(msg)
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        Log.d(TAG, "Speech recognized: $text")
                        _lastResult.value = text
                        callback?.onResult(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        _lastResult.value = matches[0]
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a command for proot...")
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognizer", e)
            _isListening.value = false
            callback?.onError(e.message ?: "Failed to start speech input")
        }
    }

    fun stopListening() {
        if (_isListening.value) {
            speechRecognizer?.stopListening()
            _isListening.value = false
            callback?.onListeningStopped()
        }
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        _isListening.value = false
    }

    private fun speechErrorToString(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            else -> "Speech recognition error ($error)"
        }
    }
}
