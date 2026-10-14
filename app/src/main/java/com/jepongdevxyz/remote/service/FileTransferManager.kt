package com.jepongdevxyz.remote.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.jepongdevxyz.remote.core.FileTransferProgress
import com.jepongdevxyz.remote.core.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * File transfer over the "devxyz-file" DataChannel.
 *
 * Outgoing: sends {"t":"fmeta",...} then 16KB base64 "fchunk" frames with a
 * 4-in-flight acknowledgement window ("fack" per chunk).
 * Incoming: appends chunks to a cache temp file, acks each chunk, and on
 * completion moves the file to Download/DevxyzRemote via MediaStore
 * (API 29+) or the public Downloads dir (API 26-28).
 *
 * Internal — SessionCoordinator owns the single instance and exposes
 * [transfers]; [sendFile] returns the transfer id (or throws when the
 * content URI cannot be opened).
 */
internal class FileTransferManager(
    private val send: (String) -> Boolean,
    private val scope: CoroutineScope
) {

    companion object {
        const val CHUNK_SIZE = 16 * 1024
        const val WINDOW = 4
    }

    /** Set by SessionCoordinator before any transfer runs. */
    @Volatile
    var appContext: Context? = null

    private val _transfers = MutableStateFlow<List<FileTransferProgress>>(emptyList())
    val transfers: StateFlow<List<FileTransferProgress>> = _transfers.asStateFlow()

    private data class Outgoing(
        val id: String,
        val name: String,
        val size: Long,
        val input: InputStream,
        val totalChunks: Int,
        val acked: MutableSet<Int> = mutableSetOf(),
        var nextSeq: Int = 0,
        @Volatile var failed: Boolean = false,
        var job: Job? = null
    )

    private data class Incoming(
        val id: String,
        val name: String,
        val size: Long,
        val mime: String,
        val tmpFile: File,
        var received: Long = 0L
    )

    private val outgoing = mutableMapOf<String, Outgoing>()
    private val incoming = mutableMapOf<String, Incoming>()

    /**
     * Starts sending the content at [uri]. Returns the transfer id.
     * Progress is published on [transfers].
     */
    fun sendFile(context: Context, uri: Uri, name: String, size: Long, mime: String): String {
        val id = UUID.randomUUID().toString()
        val ctx = context.applicationContext
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open content: $uri")
        val totalChunks = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1)
        val og = Outgoing(id, name, size, input, totalChunks)
        synchronized(outgoing) { outgoing[id] = og }
        publish(FileTransferProgress(id, name, size, 0L, done = false, failed = false, incoming = false, savedPath = null))

        og.job = scope.launch(Dispatchers.IO) {
            try {
                if (!send(Protocol.fmeta(id, name, size, mime))) {
                    throw IllegalStateException("file data channel is not open")
                }
                val buf = ByteArray(CHUNK_SIZE)
                while (true) {
                    while (og.nextSeq - og.acked.size >= WINDOW && !og.failed) delay(50)
                    if (og.failed) throw IllegalStateException("transfer aborted")
                    val read = og.input.read(buf)
                    if (read <= 0) break
                    val b64 = Base64.encodeToString(buf, 0, read, Base64.NO_WRAP)
                    var attempts = 0
                    while (!send(Protocol.fchunk(id, og.nextSeq, b64))) {
                        if (og.failed || ++attempts > 40) throw IllegalStateException("file data channel is not open")
                        delay(250)
                    }
                    og.nextSeq++
                }
                var waits = 0
                while (og.acked.size < og.nextSeq && !og.failed && waits < 200) {
                    delay(100)
                    waits++
                }
                if (og.failed || og.acked.size < og.nextSeq) {
                    throw IllegalStateException("not all chunks were acknowledged")
                }
                publish(FileTransferProgress(id, name, size, size, done = true, failed = false, incoming = false, savedPath = null))
            } catch (e: Exception) {
                publish(FileTransferProgress(id, name, size, og.acked.size.toLong() * CHUNK_SIZE, done = false, failed = true, incoming = false, savedPath = null))
            } finally {
                try {
                    og.input.close()
                } catch (e: Exception) {
                }
                synchronized(outgoing) { outgoing.remove(id) }
            }
        }
        return id
    }

    /** Routes one incoming file-channel message (fmeta/fchunk/fack). */
    fun onMessage(json: String) {
        val msg = try {
            Protocol.parse(json)
        } catch (e: Exception) {
            return
        }
        when (msg["t"] as? String) {
            "fmeta" -> onMeta(msg)
            "fchunk" -> onChunk(msg)
            "fack" -> onAck(msg)
        }
    }

    private fun onMeta(msg: Map<String, Any?>) {
        val id = msg["id"] as? String ?: return
        val name = (msg["name"] as? String).orEmpty().ifBlank { "file" }
        val size = (msg["size"] as? Number)?.toLong() ?: return
        val mime = (msg["mime"] as? String).orEmpty().ifBlank { "application/octet-stream" }
        val ctx = appContext ?: return
        val dir = File(ctx.cacheDir, "devxyz_incoming").apply { mkdirs() }
        val tmp = File(dir, "$id.part")
        synchronized(incoming) { incoming[id] = Incoming(id, name, size, mime, tmp) }
        publish(FileTransferProgress(id, name, size, 0L, done = false, failed = false, incoming = true, savedPath = null))
    }

    private fun onChunk(msg: Map<String, Any?>) {
        val id = msg["id"] as? String ?: return
        val seq = (msg["seq"] as? Number)?.toInt() ?: return
        val data = msg["data"] as? String ?: return
        val inc = synchronized(incoming) { incoming[id] } ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = Base64.decode(data, Base64.NO_WRAP)
                FileOutputStream(inc.tmpFile, true).use { it.write(bytes) }
                inc.received += bytes.size
                publish(
                    FileTransferProgress(
                        id, inc.name, inc.size,
                        minOf(inc.size, inc.received),
                        done = false, failed = false, incoming = true, savedPath = null
                    )
                )
                send(Protocol.fack(id, seq))
                if (inc.received >= inc.size) finalizeIncoming(inc)
            } catch (e: Exception) {
                synchronized(incoming) { incoming.remove(id) }
                publish(FileTransferProgress(id, inc.name, inc.size, inc.received, done = false, failed = true, incoming = true, savedPath = null))
            }
        }
    }

    private fun onAck(msg: Map<String, Any?>) {
        val id = msg["id"] as? String ?: return
        val seq = (msg["seq"] as? Number)?.toInt() ?: return
        val og = synchronized(outgoing) { outgoing[id] } ?: return
        og.acked.add(seq)
        publish(
            FileTransferProgress(
                id, og.name, og.size,
                minOf(og.size, og.acked.size.toLong() * CHUNK_SIZE),
                done = false, failed = false, incoming = false, savedPath = null
            )
        )
    }

    private fun finalizeIncoming(inc: Incoming) {
        val ctx = appContext
        try {
            requireNotNull(ctx) { "no context" }
            val savedUri = saveToDownloads(ctx, inc.tmpFile, inc.name, inc.mime)
            inc.tmpFile.delete()
            synchronized(incoming) { incoming.remove(inc.id) }
            publish(
                FileTransferProgress(
                    inc.id, inc.name, inc.size, inc.size,
                    done = true, failed = false, incoming = true,
                    savedPath = savedUri?.toString()
                )
            )
        } catch (e: Exception) {
            synchronized(incoming) { incoming.remove(inc.id) }
            publish(FileTransferProgress(inc.id, inc.name, inc.size, inc.received, done = false, failed = true, incoming = true, savedPath = null))
        }
    }

    private fun saveToDownloads(ctx: Context, tmp: File, name: String, mime: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DevxyzRemote")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tmp).use { it.copyTo(out) }
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "DevxyzRemote"
            ).apply { mkdirs() }
            val dest = uniqueFile(dir, name)
            tmp.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var dest = File(dir, name)
        var n = 1
        while (dest.exists()) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            dest = File(dir, "$stem ($n)$ext")
            n++
        }
        return dest
    }

    /** Fails every in-flight transfer (called on hangup). */
    fun failAll() {
        val ogs = synchronized(outgoing) {
            val copy = outgoing.values.toList()
            outgoing.clear()
            copy
        }
        ogs.forEach {
            it.failed = true
            it.job?.cancel()
            try {
                it.input.close()
            } catch (e: Exception) {
            }
        }
        synchronized(incoming) { incoming.clear() }
        _transfers.update { list ->
            list.map { if (!it.done && !it.failed) it.copy(failed = true) else it }
        }
    }

    private fun publish(p: FileTransferProgress) {
        _transfers.update { list -> list.filterNot { it.id == p.id } + p }
    }
}
