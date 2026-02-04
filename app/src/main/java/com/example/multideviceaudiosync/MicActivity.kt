package com.example.multideviceaudiosync

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MicActivity : AppCompatActivity() {

    private val TAG = "MicActivityDebug"
    private val REQUEST_RECORD_AUDIO_PERMISSION = 200
    private val PORT = 50005
    
    private var isStreaming = false
    private var audioRecord: AudioRecord? = null
    private var socket: DatagramSocket? = null
    
    // Audio settings
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0

    private lateinit var etHostIp: EditText
    private lateinit var btnToggleStream: Button
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mic)

        etHostIp = findViewById(R.id.etHostIp)
        btnToggleStream = findViewById(R.id.btnToggleStream)
        statusTextView = findViewById(R.id.micStatusText)

        // Pre-fill with a common prefix for convenience, but no hardcoded full IP
        etHostIp.setText("192.168.1.")

        // Calculate minimum buffer size for AudioRecord
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize < 2048) bufferSize = 2048 

        btnToggleStream.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        } else {
            startStreaming()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startStreaming()
            } else {
                Toast.makeText(this, "Mic Permission Required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startStreaming() {
        val hostIp = etHostIp.text.toString().trim()
        
        // Basic validation for the IP address
        if (hostIp.isEmpty() || hostIp == "192.168.1.") {
            Toast.makeText(this, "Please enter a valid Host IP Address", Toast.LENGTH_SHORT).show()
            return
        }

        isStreaming = true
        btnToggleStream.text = "Stop Streaming"
        statusTextView.text = "Streaming to $hostIp..."

        thread(name = "MicStreamingThread", priority = Thread.MAX_PRIORITY) {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    return@thread
                }

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    return@thread
                }

                socket = DatagramSocket()
                val address = InetAddress.getByName(hostIp)
                
                // Use a smaller fixed buffer for network transmission (1024 bytes)
                val networkBuffer = ByteArray(1024)

                audioRecord?.startRecording()
                Log.d(TAG, "Capture started. Target Host IP: $hostIp")

                while (isStreaming) {
                    val bytesRead = audioRecord?.read(networkBuffer, 0, networkBuffer.size) ?: 0
                    
                    if (bytesRead > 0) {
                        val packet = DatagramPacket(networkBuffer, bytesRead, address, PORT)
                        socket?.send(packet)
                        
                        // Detailed log showing the destination IP
                        if (System.currentTimeMillis() % 1000 < 20) {
                            Log.d(TAG, "Sent $bytesRead bytes to $hostIp")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming Error: ${e.message}")
                runOnUiThread { 
                    stopStreaming()
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                cleanupResources()
            }
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        runOnUiThread {
            btnToggleStream.text = "Start Streaming"
            statusTextView.text = "Ready"
        }
    }

    private fun cleanupResources() {
        try {
            audioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
            }
            audioRecord = null
            socket?.close()
            socket = null
            Log.d(TAG, "Streaming Resources Cleaned Up")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false
        cleanupResources()
    }
}