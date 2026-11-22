package com.geeui.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Locates or installs vosk-model-small-fr-0.22 (Apache-2.0, Alpha Cephei).
 * The model is not shipped in the APK. Order: already extracted, an adb-pushed
 * folder or zip under /data/local/tmp/geeui, then a download from alphacephei.com.
 */
object VoskModelStore {
    const val MODEL_NAME = "vosk-model-small-fr-0.22"
    const val ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
    const val LICENSE = "Apache-2.0"
    const val ATTRIBUTION = "Vosk small French 0.22 © Alpha Cephei, licence Apache-2.0"
    private const val TAG = "GeeUIVoiceTest"
    private const val TMP = "/data/local/tmp/geeui"
    /** vosk-model-small-fr-0.22.zip is about 45 Mo; this leaves headroom without allowing a huge payload. */
    private const val MAX_BYTES = 150L * 1024 * 1024

    fun isComplete(dir: File): Boolean =
        File(dir, "am/final.mdl").isFile && File(dir, "conf/model.conf").isFile

    fun locate(context: Context): File? {
        val candidates = listOf(
            File(context.filesDir, "vosk/$MODEL_NAME"),
            File(TMP, MODEL_NAME),
            File(context.getExternalFilesDir(null), "vosk/$MODEL_NAME"),
        )
        return candidates.firstOrNull { isComplete(it) }
    }

    /** [cancelled] is checked during download so a service stop can drop a partial zip. */
    @Synchronized
    fun ensure(context: Context, cancelled: () -> Boolean = { false }): File? {
        locate(context)?.let { return it }
        val destParent = File(context.filesDir, "vosk")
        destParent.mkdirs()
        val dest = File(destParent, MODEL_NAME)
        val zips = listOf(
            File(TMP, "$MODEL_NAME.zip"),
            File(context.getExternalFilesDir(null), "vosk/$MODEL_NAME.zip"),
        )
        for (zip in zips) {
            if (!zip.isFile) continue
            Log.i(TAG, "vosk unzip ${zip.absolutePath}")
            if (installZip(zip, destParent, dest)) return dest
        }
        Log.i(TAG, "vosk download $ZIP_URL")
        val tmpZip = File(destParent, "$MODEL_NAME.zip.part")
        return try {
            download(ZIP_URL, tmpZip, cancelled)
            if (cancelled()) {
                tmpZip.delete()
                return null
            }
            val ready = installZip(tmpZip, destParent, dest)
            tmpZip.delete()
            if (ready) dest else null
        } catch (e: Exception) {
            Log.w(TAG, "vosk download failed", e)
            tmpZip.delete()
            null
        }
    }

    private fun installZip(zip: File, destParent: File, dest: File): Boolean {
        if (isComplete(dest)) return true
        val staging = File(destParent, "$MODEL_NAME.staging")
        if (staging.exists()) staging.deleteRecursively()
        if (!unzip(zip, staging)) {
            staging.deleteRecursively()
            return false
        }
        // Zip root is vosk-model-small-fr-0.22/… ; accept that or a flat extract.
        val extracted = when {
            isComplete(File(staging, MODEL_NAME)) -> File(staging, MODEL_NAME)
            isComplete(staging) -> staging
            else -> null
        }
        if (extracted == null) {
            staging.deleteRecursively()
            return false
        }
        if (dest.exists()) dest.deleteRecursively()
        return if (extracted.renameTo(dest) || (extracted.copyRecursively(dest) && isComplete(dest))) {
            if (staging.exists()) staging.deleteRecursively()
            true
        } else {
            staging.deleteRecursively()
            false
        }
    }

    private fun download(url: String, dest: File, cancelled: () -> Boolean = { false }) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            val length = conn.contentLengthLong
            if (code !in 200..299) {
                try { conn.errorStream?.use { it.readBytes() } } catch (_: Exception) {}
                throw java.io.IOException("vosk download HTTP $code")
            }
            if (length > MAX_BYTES) {
                throw java.io.IOException("vosk download too large: $length")
            }
            var copied = 0L
            val stream = conn.inputStream
            stream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw java.io.IOException("vosk download cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_BYTES) throw java.io.IOException("vosk download too large: $copied")
                        output.write(buf, 0, n)
                    }
                }
            }
            if (length > 0 && copied != length) {
                throw java.io.IOException("vosk download incomplete: $copied / $length")
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    private fun unzip(zip: File, destDir: File): Boolean {
        destDir.mkdirs()
        return try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    val canonical = out.canonicalPath
                    if (!canonical.startsWith(destDir.canonicalPath + File.separator) &&
                        canonical != destDir.canonicalPath
                    ) {
                        throw IllegalStateException("zip path ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "vosk unzip failed", e)
            false
        }
    }
}
