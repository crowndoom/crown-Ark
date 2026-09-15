package app.xodos2.voip

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * High-performance, low-latency VoIP / Microphone Bridge for proot distros.
 * Captures live microphone audio using Android AudioRecord (16-bit PCM, 48000Hz, Mono)
 * and streams it via TCP (127.0.0.1:4714) to proot applications (PulseAudio, Discord, Mumble, WebRTC).
 */
object VoipMicBridge {
    private const val TAG = "VoipMicBridge"

    const val DEFAULT_TCP_PORT = 4714
    const val SAMPLE_RATE = 48000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _connectedClientsCount = MutableStateFlow(0)
    val connectedClientsCount: StateFlow<Int> = _connectedClientsCount.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _statusMessage = MutableStateFlow("VoIP Mic Bridge Idle")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var audioRecord: AudioRecord? = null
    private val clientStreams = CopyOnWriteArrayList<OutputStream>()

    @Volatile
    private var isRunning = false
    private var recordThread: Thread? = null
    private var serverThread: Thread? = null

    /**
     * Script to run inside proot terminal to attach the VoIP mic to PulseAudio.
     * Checks if android_mic is already present, attempts native OpenSL ES module-sles-source,
     * and falls back to named-pipe TCP bridge on port 4714.
     */
    fun getProotSetupCommand(port: Int = DEFAULT_TCP_PORT): String {
        return "pactl list sources short | grep -q android_mic || (pactl load-module module-sles-source source_name=android_mic 2>/dev/null || (mkfifo /tmp/micpipe 2>/dev/null; pactl load-module module-pipe-source source_name=android_mic file=/tmp/micpipe format=s16le rate=$SAMPLE_RATE channels=1 2>/dev/null; (nc 127.0.0.1 $port > /tmp/micpipe 2>/dev/null &))); pactl set-default-source android_mic 2>/dev/null && echo '✅ Android microphone attached to PulseAudio as default source'"
    }

    /**
     * Standalone pipe command for parec or ffmpeg inside proot.
     */
    fun getProotCaptureCommand(port: Int = DEFAULT_TCP_PORT): String {
        return "nc 127.0.0.1 $port | parec --format=s16le --rate=$SAMPLE_RATE --channels=1"
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startStreaming(context: Context, port: Int = DEFAULT_TCP_PORT): Boolean {
        if (isRunning) return true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = (minBuf * 2).coerceAtLeast(4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                // Fallback to standard MIC source
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                _statusMessage.value = "Failed to initialize AudioRecord"
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }

            serverSocket = ServerSocket(port)
            isRunning = true
            _isStreaming.value = true
            _statusMessage.value = "VoIP Streaming on 127.0.0.1:$port"

            // Start TCP server listener
            serverThread = thread(name = "VoipMic-Server", isDaemon = true) {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        socket.tcpNoDelay = true
                        val out = socket.getOutputStream()
                        clientStreams.add(out)
                        _connectedClientsCount.value = clientStreams.size
                        Log.i(TAG, "New proot VoIP client connected: ${socket.remoteSocketAddress}")

                        // Watch client disconnection
                        thread(name = "VoipMic-ClientWatch", isDaemon = true) {
                            try {
                                val input = socket.getInputStream()
                                val dummy = ByteArray(64)
                                while (input.read(dummy) != -1 && isRunning) {
                                    // read until closed
                                }
                            } catch (_: Exception) {
                            } finally {
                                clientStreams.remove(out)
                                _connectedClientsCount.value = clientStreams.size
                                try { socket.close() } catch (_: Exception) {}
                                Log.i(TAG, "Proot VoIP client disconnected")
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Server socket accept error", e)
                    }
                }
            }

            // Start Audio recording and broadcasting thread
            audioRecord?.startRecording()
            recordThread = thread(name = "VoipMic-Record", isDaemon = true) {
                val buffer = ByteArray(bufSize)
                val shorts = ShortArray(bufSize / 2)

                while (isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        // Calculate RMS level for UI visualizer
                        var sum = 0.0
                        val shortCount = read / 2
                        for (i in 0 until shortCount) {
                            val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
                            shorts[i] = sample.toShort()
                            sum += sample * sample
                        }
                        val rms = sqrt(sum / shortCount) / 32768.0
                        _audioLevel.value = rms.toFloat().coerceIn(0f, 1f)

                        // Broadcast to all connected proot VoIP sockets
                        for (out in clientStreams) {
                            try {
                                out.write(buffer, 0, read)
                                out.flush()
                            } catch (e: Exception) {
                                clientStreams.remove(out)
                                _connectedClientsCount.value = clientStreams.size
                            }
                        }
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VoIP streaming", e)
            _statusMessage.value = "Error: ${e.message}"
            stopStreaming()
            return false
        }
    }

    @Synchronized
    fun stopStreaming() {
        isRunning = false
        _isStreaming.value = false
        _connectedClientsCount.value = 0
        _audioLevel.value = 0f
        _statusMessage.value = "VoIP Mic Bridge Stopped"

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (out in clientStreams) {
            try { out.close() } catch (_: Exception) {}
        }
        clientStreams.clear()

        recordThread = null
        serverThread = null
    }

    fun toggleStreaming(context: Context, port: Int = DEFAULT_TCP_PORT): Boolean {
        return if (_isStreaming.value) {
            stopStreaming()
            false
        } else {
            startStreaming(context, port)
        }
    }
}
