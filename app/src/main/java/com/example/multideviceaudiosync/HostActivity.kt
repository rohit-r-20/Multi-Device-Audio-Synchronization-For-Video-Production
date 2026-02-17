package com.example.multideviceaudiosync

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class HostActivity : AppCompatActivity() {

    private val TAG = "HostActivityDebug"
    private val PORT = 50005
    
    private var isRunning = false
    private var socket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val frameSize = 1024 // Small frame size for low latency mixing

    // Multi-client management
    private val clientBuffers = ConcurrentHashMap<String, LinkedBlockingQueue<ByteArray>>()
    
    private lateinit var ipTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnVideoRecord: Button
    private lateinit var btnExport: Button
    private lateinit var tvRecordingPath: TextView
    private lateinit var viewFinder: PreviewView

    // Audio Recording variables
    private var isRecording = false
    private var recordFile: File? = null
    private var fileOutputStream: FileOutputStream? = null
    private var totalAudioLen: Long = 0

    // CameraX variables
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)

        ipTextView = findViewById(R.id.tvHostIpDisplay)
        statusTextView = findViewById(R.id.hostStatusText)
        btnRecord = findViewById(R.id.btnRecord)
        btnVideoRecord = findViewById(R.id.btnVideoRecord)
        btnExport = findViewById(R.id.btnExport)
        tvRecordingPath = findViewById(R.id.tvRecordingPath)
        viewFinder = findViewById(R.id.viewFinder)

        ipTextView.text = "Your IP: ${getFormattedIpAddress()}"

        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        btnVideoRecord.setOnClickListener {
            captureVideo()
        }

        btnExport.setOnClickListener {
            shareRecording()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        btnVideoRecord.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(System.currentTimeMillis())
        
        val videoDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val videoFile = File(videoDir, "VIDEO_$name.mp4")

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "VIDEO_$name")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MultiDeviceSync")
                }
            })
            .build()

        // Since the user asked specifically to save to getExternalFilesDir(Movies), 
        // I will use FileDescriptor or File output options instead of MediaStore if preferred, 
        // but MediaStore is more standard for Video. 
        // However, CameraX Recorder also supports FileOutputOptions.
        
        val fileOutputOptions = androidx.camera.video.FileOutputOptions.Builder(videoFile).build()

        recording = videoCapture.output
            .prepareRecording(this, fileOutputOptions)
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        btnVideoRecord.apply {
                            text = "Stop Video Rec"
                            isEnabled = true
                        }
                        Log.d(TAG, "Video recording started: ${videoFile.absolutePath}")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                        btnVideoRecord.apply {
                            text = "Start Video Rec"
                            isEnabled = true
                        }
                    }
                }
            }
    }

    override fun onStart() {
        super.onStart()
        startEngine()
    }

    override fun onStop() {
        super.onStop()
        stopEngine()
        if (isRecording) stopRecording()
    }

    private fun getFormattedIpAddress(): String {
        val wifiIp = getWifiIpAddress()
        return if (wifiIp == "0.0.0.0" || wifiIp == "Unknown") {
            val hotspotIp = getHotspotIpAddress()
            if (hotspotIp != null) "$hotspotIp (Hotspot)" else "Connect to Wi-Fi/Hotspot"
        } else {
            wifiIp
        }
    }

    private fun getWifiIpAddress(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            if (ipAddress == 0) return "0.0.0.0"
            String.format("%d.%d.%d.%d", ipAddress and 0xff, ipAddress shr 8 and 0xff, ipAddress shr 16 and 0xff, ipAddress shr 24 and 0xff)
        } catch (e: Exception) { "Unknown" }
    }

    private fun getHotspotIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.contains("wlan") || networkInterface.name.contains("ap")) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr.hostAddress.contains(".")) {
                            if (addr.hostAddress == "192.168.43.1") return addr.hostAddress
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun startEngine() {
        if (isRunning) return
        isRunning = true

        thread(name = "ReceiverThread") {
            try {
                socket = DatagramSocket(PORT)
                socket?.receiveBufferSize = 64 * 1024
                val buffer = ByteArray(2048)
                
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    val clientIp = packet.address.hostAddress
                    val data = packet.data.copyOf(packet.length)
                    
                    if (data.isNotEmpty()) {
                        val queue = clientBuffers.getOrPut(clientIp) { LinkedBlockingQueue(30) }
                        if (!queue.offer(data)) {
                            queue.poll()
                            queue.offer(data)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Receiver Error", e)
            } finally {
                socket?.close()
            }
        }

        thread(name = "MixerThread") {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true

                val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build())
                    .setBufferSizeInBytes(maxOf(minBufSize, frameSize * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()
                val mixBuffer = IntArray(frameSize / 2)

                while (isRunning) {
                    var activeCount = 0
                    mixBuffer.fill(0)

                    for ((ip, queue) in clientBuffers) {
                        val packetData = queue.poll()
                        if (packetData != null) {
                            activeCount++
                            for (i in 0 until minOf(packetData.size / 2, mixBuffer.size)) {
                                val sample = ((packetData[i * 2 + 1].toInt() shl 8) or (packetData[i * 2].toInt() and 0xFF)).toShort()
                                mixBuffer[i] += sample.toInt()
                            }
                        }
                    }

                    if (activeCount > 0) {
                        val outBuffer = ShortArray(mixBuffer.size)
                        for (i in mixBuffer.indices) {
                            outBuffer[i] = mixBuffer[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                        audioTrack?.write(outBuffer, 0, outBuffer.size)
                        
                        // WRITE TO FILE IF RECORDING
                        if (isRecording) {
                            val byteBuffer = ByteArray(outBuffer.size * 2)
                            for (i in outBuffer.indices) {
                                byteBuffer[i * 2] = (outBuffer[i].toInt() and 0x00FF).toByte()
                                byteBuffer[i * 2 + 1] = (outBuffer[i].toInt() shr 8).toByte()
                            }
                            fileOutputStream?.write(byteBuffer)
                            totalAudioLen += byteBuffer.size
                        }
                    } else {
                        Thread.sleep(10)
                    }

                    if (System.currentTimeMillis() % 2000 < 20) {
                        runOnUiThread { statusTextView.text = "Connected Clients: $activeCount" }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mixer Error", e)
            } finally {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            }
        }
    }

    private fun startRecording() {
        try {
            val dir = File(getExternalFilesDir(null), "Recordings")
            if (!dir.exists()) dir.mkdirs()
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            recordFile = File(dir, "Recording_$timestamp.wav")
            fileOutputStream = FileOutputStream(recordFile)
            totalAudioLen = 0
            
            writeWavHeader(fileOutputStream!!, 0, 0)
            
            isRecording = true
            btnRecord.text = "Stop Audio Rec"
            btnExport.visibility = View.GONE
            tvRecordingPath.text = "Recording to: ${recordFile?.name}"
            Log.d(TAG, "Started recording to: ${recordFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Start Recording Error", e)
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        
        try {
            fileOutputStream?.close()
            updateWavHeader(recordFile!!)
            
            runOnUiThread {
                btnRecord.text = "Start Audio Rec"
                btnExport.visibility = View.VISIBLE
                tvRecordingPath.text = "Saved: ${recordFile?.absolutePath}"
            }
            Log.d(TAG, "Stopped recording. File size: $totalAudioLen")
        } catch (e: Exception) {
            Log.e(TAG, "Stop Recording Error", e)
        }
    }

    private fun writeWavHeader(out: FileOutputStream, totalAudioLen: Long, totalDataLen: Long) {
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8
        val header = ByteArray(44)
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate.toLong() and 0xff).toByte()
        header[25] = (sampleRate.toLong() shr 8 and 0xff).toByte()
        header[26] = (sampleRate.toLong() shr 16 and 0xff).toByte()
        header[27] = (sampleRate.toLong() shr 24 and 0xff).toByte()
        header[28] = (byteRate.toLong() and 0xff).toByte()
        header[29] = (byteRate.toLong() shr 8 and 0xff).toByte()
        header[30] = (byteRate.toLong() shr 16 and 0xff).toByte()
        header[31] = (byteRate.toLong() shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()
        out.write(header, 0, 44)
    }

    private fun updateWavHeader(file: File) {
        val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36
        val raf = RandomAccessFile(file, "rw")
        
        // Update totalDataLen at offset 4
        raf.seek(4)
        raf.writeByte((totalDataLen and 0xff).toInt())
        raf.writeByte((totalDataLen shr 8 and 0xff).toInt())
        raf.writeByte((totalDataLen shr 16 and 0xff).toInt())
        raf.writeByte((totalDataLen shr 24 and 0xff).toInt())
        
        // Update totalAudioLen at offset 40
        raf.seek(40)
        raf.writeByte((totalAudioLen and 0xff).toInt())
        raf.writeByte((totalAudioLen shr 8 and 0xff).toInt())
        raf.writeByte((totalAudioLen shr 16 and 0xff).toInt())
        raf.writeByte((totalAudioLen shr 24 and 0xff).toInt())
        
        raf.close()
    }

    private fun shareRecording() {
        recordFile?.let { file ->
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Recording"))
        }
    }

    private fun stopEngine() {
        isRunning = false
        socket?.close()
        socket = null
        statusTextView.text = "Stopped"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopEngine()
        if (isRecording) stopRecording()
    }
}