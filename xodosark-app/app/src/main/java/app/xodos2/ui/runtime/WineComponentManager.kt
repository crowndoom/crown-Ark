package app.xodos2.ui.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans a wincomponents category folder for archives (.tzst / .tar.zst / .7z),
 * remembers the last-selected one, and extracts it into a Wine prefix using
 * the Android-native tar / 7z binaries that setupNativeEnvironment() already
 * symlinks into $filesDir/usr/bin.
 */
object WineComponentManager {

    private const val TAG = "WineComponentManager"
    private const val PREFS = "wine_components"
    private const val PREF_LAST = "last_selected_"

    // ─────────────── Paths ───────────────

    /** e.g. <filesDir>/usr/wincomponents/d3d */
    fun componentsDir(context: Context, category: String): File =
        File(context.filesDir, "usr/wincomponents/$category")

    /** Default Wine prefix; override if yours lives elsewhere. */
    fun defaultWinePrefix(context: Context): File =
        File(context.filesDir, "home/.wine/drive_c/windows/")

    // ─────────────── Scanning ───────────────

    /**
     * Returns a name-sorted list of every archive in the category folder.
     * Recognises .tzst, .tar.zst, and .7z.
     */
    fun listArchives(context: Context, category: String): List<File> {
        val dir = componentsDir(context, category)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && isSupported(it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    private fun isSupported(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".tzst") || n.endsWith(".tar.zst") || n.endsWith(".7z")
    }

    // ─────────────── Preference persistence ───────────────

    fun lastSelected(context: Context, category: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_LAST + category, null)

    fun saveSelected(context: Context, category: String, archiveName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST + category, archiveName)
            .apply()
    }

    // ─────────────── Extraction ───────────────

    /**
     * Extracts [archive] into [winePrefix] using the bundled native tar/7z.
     * Reports 0..100 progress; returns true on success.
     */
    suspend fun extractToWinePrefix(
        context: Context,
        category: String,
        archiveName: String,
        winePrefix: File = defaultWinePrefix(context),
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val archive = File(componentsDir(context, category), archiveName)
        if (!archive.isFile) {
            Log.e(TAG, "Archive not found: ${archive.absolutePath}")
            onProgress(-1, "Archive missing: $archiveName")
            return@withContext false
        }

        if (!winePrefix.exists()) winePrefix.mkdirs()

        val usrBin = File(context.filesDir, "usr/bin")
        val usrLib = File(context.filesDir, "usr/lib")

        val name = archive.name.lowercase()
        val cmd: List<String> = when {
            name.endsWith(".tzst") || name.endsWith(".tar.zst") -> {
                val tar = File(usrBin, "tar")
                if (!tar.exists()) {
                    onProgress(-1, "tar binary not found")
                    return@withContext false
                }
                // Try --zstd first; if tar was built without it, the shell fallback
                // pipes through unzstd/zstd. We wrap in sh -c so both paths work.
                listOf(
                    "/system/bin/sh", "-c",
                    "\"${tar.absolutePath}\" --zstd -xf \"${archive.absolutePath}\" -C \"${winePrefix.absolutePath}\" " +
                    "|| (command -v unzstd >/dev/null 2>&1 && " +
                    "   unzstd -c \"${archive.absolutePath}\" | \"${tar.absolutePath}\" -xf - -C \"${winePrefix.absolutePath}\") " +
                    "|| (command -v zstd >/dev/null 2>&1 && " +
                    "   zstd -dc \"${archive.absolutePath}\" | \"${tar.absolutePath}\" -xf - -C \"${winePrefix.absolutePath}\")"
                )
            }
            name.endsWith(".7z") -> {
                val sevenZip = listOf("7z", "7za", "7zr")
                    .map { File(usrBin, it) }
                    .firstOrNull { it.exists() }
                if (sevenZip == null) {
                    onProgress(-1, "7z binary not found in ${usrBin.absolutePath}")
                    return@withContext false
                }
                listOf(
                    sevenZip.absolutePath,
                    "x", "-y",
                    "-o${winePrefix.absolutePath}",
                    archive.absolutePath
                )
            }
            else -> {
                onProgress(-1, "Unsupported archive: ${archive.name}")
                return@withContext false
            }
        }

        val env = mapOf(
            "LD_LIBRARY_PATH" to "${usrLib.absolutePath}:${context.applicationInfo.nativeLibraryDir}",
            "PATH"            to "${usrBin.absolutePath}:/system/bin:/system/xbin",
            "TMPDIR"          to context.cacheDir.absolutePath
        )

        try {
            onProgress(0, "Extracting ${archive.name} ...")

            val pb = ProcessBuilder(cmd)
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .apply { environment().putAll(env) }

            val proc = pb.start()
            val log = StringBuilder()
            val drain = Thread {
                proc.inputStream.bufferedReader().forEachLine { log.appendLine(it) }
            }.apply { isDaemon = true; start() }

            // Poll for a coarse progress indicator
            var pct = 5
            while (proc.isAlive) {
                Thread.sleep(400)
                pct = (pct + 4).coerceAtMost(95)
                onProgress(pct, "Extracting ${archive.name} ...")
            }
            val exit = proc.exitValue()
            drain.join(2_000)

            if (exit != 0) {
                Log.e(TAG, "Extraction failed (exit=$exit)\n$log")
                onProgress(-1, "Extraction failed")
                return@withContext false
            }

            Log.i(TAG, "Extracted ${archive.name} -> ${winePrefix.absolutePath}")
            onProgress(100, "Installed ${archive.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Extraction error", e)
            onProgress(-1, "Error: ${e.message}")
            false
        }
    }
}