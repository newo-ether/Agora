package com.newoether.agora.fileserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.util.DebugLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Serves a user-chosen directory over the LAN as a foreground HTTP(S) file server, mirroring
 * [com.newoether.agora.webui.WebUiService]: a specialUse foreground service (no natural end, so
 * not dataSync) started only while the app is in the foreground. Optional self-signed TLS and a
 * PIN gate are supported. Uploads are streamed to disk with a hard size cap.
 */
class FileServerService : Service() {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var rootDir: File = File("/")
    private var port: Int = DEFAULT_PORT
    private var useHttps: Boolean = false
    private var pin: String? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    var serverUrl: String? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = runCatching {
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "agora:fileserver")
                .apply { acquire() }
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val rootPath = intent?.getStringExtra(EXTRA_ROOT_PATH)
        if (rootPath == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        useHttps = intent.getBooleanExtra(EXTRA_USE_HTTPS, false)
        pin = intent.getStringExtra(EXTRA_PIN)?.ifBlank { null }
        rootDir = File(rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            DebugLog.w(TAG, "File server root is not a directory: $rootPath")
            stopSelf()
            return START_NOT_STICKY
        }
        val ip = wifiIp()
        serverUrl = "${if (useHttps) "https" else "http"}://$ip:$port"
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(serverUrl!!),
            foregroundServiceType(),
        )
        startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        serverUrl = null
        runCatching { serverSocket?.close() }
        serverThread?.interrupt()
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
        super.onDestroy()
    }

    private fun startServer() {
        serverThread = thread(name = "AgoraFileServer") {
            try {
                serverSocket = if (useHttps) {
                    FileServerTls.createServerSocket(port)
                } else {
                    ServerSocket(port)
                }
                while (!Thread.interrupted()) {
                    val socket = serverSocket?.accept() ?: break
                    thread { handleClient(socket) }
                }
            } catch (e: Exception) {
                DebugLog.w(TAG, "File server loop ended: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.sendBufferSize = 262_144
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val header = readHeader(input) ?: return
                val lines = header.split("\r\n")
                val requestLine = lines.firstOrNull() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = URLDecoder.decode(parts[1], "UTF-8")
                val safePath = FileServerHttp.safeRelativePath(rawPath)
                val headers = parseHeaders(lines)
                val out = BufferedOutputStream(s.getOutputStream(), 262_144)
                if (!authorize(headers, out)) {
                    out.flush()
                    return
                }
                routeRequest(method, rawPath, safePath, headers, input, out)
                out.flush()
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "File server client error: ${e.javaClass.simpleName}")
        }
    }

    /** Reads the request head byte-by-byte so a buffered reader never swallows the body bytes. */
    private fun readHeader(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var prev = 0
        var curr: Int
        while (input.read().also { curr = it } != -1) {
            sb.append(curr.toChar())
            if (prev == '\r'.code && curr == '\n'.code && sb.endsWith("\r\n\r\n")) {
                return sb.toString()
            }
            prev = curr
        }
        return null
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                headers[lines[i].substring(0, colon).trim().lowercase()] =
                    lines[i].substring(colon + 1).trim()
            }
        }
        return headers
    }

    /** Returns true if the request may proceed; writes a 302/login response and returns false otherwise. */
    private fun authorize(headers: Map<String, String>, out: BufferedOutputStream): Boolean {
        val required = pin ?: return true
        val authHeader = headers["authorization"]
        val cookiePin = headers["cookie"]?.let { Regex("pin=([^;]+)").find(it)?.groupValues?.get(1) }
        if (authHeader == "Bearer $required" || cookiePin == required) return true
        if (headers["x-pin"] == required) {
            val secureFlag = if (useHttps) "; Secure" else ""
            out.write(
                (
                    "HTTP/1.1 302 Found\r\nSet-Cookie: pin=$required; Path=/; HttpOnly$secureFlag\r\n" +
                        "Location: /\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
            )
            return false
        }
        FileServerHttp.sendPinPrompt(out)
        return false
    }

    private fun routeRequest(
        method: String,
        rawPath: String,
        safePath: String,
        headers: Map<String, String>,
        input: java.io.InputStream,
        out: BufferedOutputStream,
    ) {
        if (method == "POST" &&
            headers["content-type"]?.contains("multipart/form-data") == true
        ) {
            handleUpload(input, headers, safePath, out)
            return
        }
        val file = File(rootDir, safePath)
        when {
            !file.exists() -> FileServerHttp.sendError(out, 404, "Not Found")
            file.isDirectory -> FileServerHttp.sendDirectoryListing(out, file, rawPath.substringBefore("?"))
            else -> FileServerHttp.sendFile(out, file)
        }
    }

    private fun handleUpload(
        input: java.io.InputStream,
        headers: Map<String, String>,
        path: String,
        out: BufferedOutputStream,
    ) {
        try {
            val contentType = headers["content-type"] ?: ""
            val boundary = "--" + contentType.substringAfter("boundary=").trim()
            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0
            if (contentLength <= 0 || contentLength > MAX_UPLOAD_BYTES) {
                FileServerHttp.sendError(out, 413, "Payload Too Large")
                return
            }
            val uploadDir = File(rootDir, path)
            if (!ensureWritableDir(uploadDir)) {
                FileServerHttp.sendError(
                    out,
                    500,
                    "Upload failed: the shared folder is not writable by Agora. " +
                        "Android scoped storage blocks apps from writing to folders owned by " +
                        "other apps or the media store. Pick a folder Agora owns (its own " +
                        "storage or Download) in File Server settings.",
                )
                return
            }
            val spool = File.createTempFile("agora-upload", ".tmp", cacheDir)
            try {
                BufferedOutputStream(FileOutputStream(spool), FileServerMultipart.SPOOL_BLOCK).use { sink ->
                    val buf = ByteArray(FileServerMultipart.SPOOL_BLOCK)
                    var total = 0L
                    while (total < contentLength) {
                        val want = minOf(buf.size.toLong(), contentLength - total).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        total += n
                    }
                }
                val saved = FileServerMultipart.parseInto(spool, boundary, uploadDir)
                val redirect = if (path.isBlank()) "/" else "/$path"
                val msg = "$saved file(s) uploaded"
                out.write(
                    (
                        "HTTP/1.1 303 See Other\r\nLocation: $redirect\r\n" +
                            "Content-Length: ${msg.length}\r\nConnection: close\r\n\r\n$msg"
                        ).toByteArray(),
                )
            } finally {
                spool.delete()
            }
        } catch (e: Exception) {
            FileServerHttp.sendError(out, 500, "Upload failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Verifies the app can actually create and write files in [dir] under scoped storage. Owning
     * a SAF tree grant does not imply filesystem write access, so this probes with a temp file
     * instead of trusting [File.canWrite], which lies on some media-store paths.
     */
    private fun ensureWritableDir(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        if (!dir.isDirectory) return false
        val probe = File(dir, ".agora-write-probe-${System.nanoTime()}")
        try {
            probe.outputStream().use { it.write(0) }
            true
        } finally {
            probe.delete()
        }
    }.getOrDefault(false)

    private fun wifiIp(): String {
        runCatching {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                val name = intf.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("wg") || name.startsWith("lo")) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress || addr is Inet6Address) continue
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                        return host
                    }
                }
            }
        }
        @Suppress("DEPRECATION")
        return runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        }.getOrDefault("0.0.0.0")
    }

    private fun notification(url: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.fileserver_notification_title))
        .setContentText(getString(R.string.fileserver_notification_running, url))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            getString(R.string.fileserver_notification_stop),
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, FileServerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fileserver_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    companion object {
        private const val TAG = "FileServerService"
        private const val CHANNEL_ID = "agora_fileserver"
        private const val NOTIFICATION_ID = 4_742
        private const val REQUEST_OPEN = 4_742
        private const val REQUEST_STOP = 4_743
        private const val ACTION_STOP = "com.newoether.agora.fileserver.STOP"

        const val EXTRA_ROOT_PATH = "root_path"
        const val EXTRA_PORT = "port"
        const val EXTRA_USE_HTTPS = "use_https"
        const val EXTRA_PIN = "pin"
        const val DEFAULT_PORT = 8080
        const val MAX_UPLOAD_BYTES = 2_000_000_000L // 2GB cap

        @Volatile
        private var instance: FileServerService? = null

        fun isRunning(): Boolean = instance != null

        fun currentUrl(): String? = instance?.serverUrl

        fun start(
            context: Context,
            rootPath: String,
            port: Int = DEFAULT_PORT,
            useHttps: Boolean = false,
            pin: String? = null,
        ) {
            val intent = Intent(context, FileServerService::class.java).apply {
                putExtra(EXTRA_ROOT_PATH, rootPath)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USE_HTTPS, useHttps)
                putExtra(EXTRA_PIN, pin)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { DebugLog.w(TAG, "File server could not start", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FileServerService::class.java))
        }
    }
}
