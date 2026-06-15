package com.TapLink.app.smb

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.InputStream
import java.util.Properties

/** A single entry inside an SMB directory listing. */
data class SmbEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * Thin jcifs-ng wrapper for SMB2/3 LAN shares. All calls perform blocking
 * network IO and MUST run off the main thread (callers: CompanionServer's
 * NanoHTTPD worker threads and the WebView media interceptor's background
 * thread — both already off-main).
 */
object SmbClient {

    private fun contextFor(share: SmbShare): CIFSContext {
        val props = Properties().apply {
            // SMB2/3 only — SMB1 is insecure and off by default on modern NAS.
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            // DFS adds round-trips and breaks on plain home NAS; disable it.
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            // Larger socket buffers → bigger SMB2 reads → far fewer round-trips,
            // which fixes the periodic ~5s re-buffer when streaming video.
            setProperty("jcifs.smb.client.rcv_buf_size", "1048576")
            setProperty("jcifs.smb.client.snd_buf_size", "1048576")
            // CRITICAL: resolve the host by DNS / IP only. The default order
            // includes NetBIOS broadcast (BCAST), which on Android answers with
            // 0.0.0.0 — the "0.0.0.0<00>/<ip>" connect failure. DNS-only makes
            // an IP literal connect straight through.
            setProperty("jcifs.resolveOrder", "DNS")
            // No NetBIOS/WINS server; keeps it from trying name service at all.
            setProperty("jcifs.netbios.wins", "")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val auth = NtlmPasswordAuthenticator(
            share.domain.ifBlank { null },
            share.username,
            share.password
        )
        return base.withCredentials(auth)
    }

    /**
     * Resolve a file/dir inside the share by chaining parent→child SmbFiles.
     * The SmbFile(parent, name) constructor takes a RAW child name and does its
     * own encoding, so folder names with spaces/unicode work. Manually
     * %-encoding the path (the old approach) produced literal "The%20Beatles"
     * lookups → "cannot find the file specified" on every spaced subfolder.
     * Directory levels get a trailing "/"; the final file level does not.
     */
    private fun smbFile(share: SmbShare, rel: String, isDir: Boolean): SmbFile {
        val ctx = contextFor(share)
        var f = SmbFile("smb://${share.host}/${share.share.trim('/')}/", ctx)
        val segs = (share.path.trim('/').split("/") + rel.trim('/').split("/"))
            .filter { it.isNotBlank() }
        for (i in segs.indices) {
            val last = i == segs.size - 1
            f = SmbFile(f, if (last && !isDir) segs[i] else segs[i] + "/")
        }
        return f
    }

    fun listDir(share: SmbShare, rel: String): List<SmbEntry> {
        val files = smbFile(share, rel, isDir = true).listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val isDir = f.isDirectory
                SmbEntry(f.name.trimEnd('/'), isDir, if (isDir) 0L else f.length())
            }.getOrNull()
        }.sortedWith(
            compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }

    fun length(share: SmbShare, rel: String): Long =
        runCatching { smbFile(share, rel, isDir = false).length() }.getOrDefault(-1L)

    /** Open a read stream positioned at [offset] (for HTTP Range / seeking). */
    fun openStream(share: SmbShare, rel: String, offset: Long): InputStream {
        val stream = smbFile(share, rel, isDir = false).inputStream
        var remaining = offset
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
        return stream
    }

    /** Seekable handle for ExoPlayer's DataSource (random-access reads). */
    fun openRandomAccess(share: SmbShare, rel: String): jcifs.smb.SmbRandomAccessFile =
        jcifs.smb.SmbRandomAccessFile(smbFile(share, rel, isDir = false), "r")

    /** Connectivity + auth check: open the share root and count entries. */
    fun test(share: SmbShare): Result<Int> = runCatching {
        val root = smbFile(share, "", isDir = true)
        if (!root.exists()) throw java.io.IOException("Share not reachable or not found")
        root.listFiles()?.size ?: 0
    }

    /** A LAN host that answered on the SMB port. */
    data class SmbHost(val ip: String, val name: String)

    /**
     * Discover SMB servers on the local /24 by probing TCP 445 in parallel.
     * Reliable across routers/NAS regardless of mDNS/NetBIOS advertising.
     * [localIpv4] is the glasses' own LAN address (e.g. 192.168.1.42); we scan
     * .1–.254 of that subnet. Bounded by [timeoutMs] total.
     */
    fun discoverHosts(localIpv4: String, timeoutMs: Int = 2800): List<SmbHost> {
        val prefix = localIpv4.substringBeforeLast('.', "")
        if (prefix.isBlank()) return emptyList()
        val found = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(96)
        try {
            val tasks = (1..254).map { i ->
                java.util.concurrent.Callable {
                    val ip = "$prefix.$i"
                    runCatching {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress(ip, 445), 400)
                            found.add(ip)
                        }
                    }
                    null
                }
            }
            runCatching {
                pool.invokeAll(tasks, timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } finally {
            pool.shutdownNow()
        }
        return found.sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
            .map { SmbHost(it, "") }
    }

    /**
     * List the disk shares exposed by [host] using the supplied credentials
     * (blank = guest/anonymous). Admin/IPC shares (ending in `$`) are skipped.
     * Throws on auth failure so the UI can prompt for credentials.
     */
    fun listShares(host: String, domain: String, user: String, pass: String): List<String> {
        val probe = SmbShare("", "", host, "", "", domain, user, pass)
        val root = SmbFile("smb://$host/", contextFor(probe))
        val files = root.listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { f.name.trimEnd('/') }.getOrNull()
        }.filter { it.isNotBlank() && !it.endsWith("$") }
            .distinct()
            .sortedBy { it.lowercase() }
    }
}
