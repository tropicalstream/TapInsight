package com.TapLink.app.smb

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * ExoPlayer DataSource that reads a LAN SMB file directly via jcifs, so the
 * native player (which handles MKV/AVI and AC3/E-AC3 audio the WebView's
 * <video> can't) can stream straight off the NAS — no HTTP proxy in between.
 *
 * URI shape: smbtap://<shareId>?p=<url-encoded relative path>
 */
@UnstableApi
class SmbMediaDataSource(private val store: SmbShareStore) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var raf: SmbRandomAccessFile? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    // Read-ahead buffer: ExoPlayer issues many small reads; serving them from
    // one large SMB read (instead of one SMB round-trip per read) is what keeps
    // throughput well above realtime and stops the periodic re-buffering.
    private val readBuf = ByteArray(1 shl 20)   // 1 MiB
    private var bufPos = 0
    private var bufLen = 0

    override fun open(dataSpec: DataSpec): Long {
        val u = dataSpec.uri
        uri = u
        transferInitializing(dataSpec)
        val shareId = u.host.orEmpty()
        val path = u.getQueryParameter("p").orEmpty()
        val share = store.get(shareId)
            ?: throw IOException("Unknown SMB share: $shareId")

        val r = SmbClient.openRandomAccess(share, path)
        raf = r
        val total = r.length()
        if (dataSpec.position > 0L) r.seek(dataSpec.position)
        bufPos = 0
        bufLen = 0
        bytesRemaining =
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else (total - dataSpec.position).coerceAtLeast(0L)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (bufPos >= bufLen) {
            val want = minOf(readBuf.size.toLong(), bytesRemaining).toInt()
            val n = raf?.read(readBuf, 0, want) ?: return C.RESULT_END_OF_INPUT
            if (n <= 0) return C.RESULT_END_OF_INPUT
            bufPos = 0
            bufLen = n
        }
        val toCopy = minOf(length, bufLen - bufPos)
        System.arraycopy(readBuf, bufPos, buffer, offset, toCopy)
        bufPos += toCopy
        bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { raf?.close() } catch (_: Exception) {}
        raf = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    @UnstableApi
    class Factory(context: Context) : DataSource.Factory {
        private val store = SmbShareStore(context.applicationContext)
        override fun createDataSource(): DataSource = SmbMediaDataSource(store)
    }
}
