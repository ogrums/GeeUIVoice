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

    @Synchronized
    fun ensure(context: Context): File? {
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
            download(ZIP_URL, tmpZip)
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

    private fun download(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        conn.disconnect()
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
