package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TreeDocumentFileOt
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scanDfRecursive
import com.dd3boh.outertune.utils.scanners.documentFileFromUri
import java.io.File
import java.io.IOException
import java.io.InputStream

class DownloadDirectoryManagerOt(private var context: Context, private var dir: Uri, extraDirs: List<Uri>) {
    val TAG = DownloadDirectoryManagerOt::class.simpleName.toString()
    var mainDir: DocumentFile? = null
    var allDirs: List<DocumentFile> = mutableListOf()

    var availableFiles: Set<DocumentFile> = mutableSetOf()

    init {
        doInit(context, dir, extraDirs)
    }

    fun doInit(context: Context, dir: Uri, extraDirs: List<Uri>) {
        Log.i(TAG, "Initializing download manager: $dir")
        this.context = context
        this.dir = dir
        try {
            // UPDATED: Support File URIs (Default) vs Content URIs (SAF)
            mainDir = if (dir.scheme == "file") {
                DocumentFile.fromFile(File(dir.path!!))
            } else {
                documentFileFromUri(context, dir)
            }

            if (mainDir == null || !mainDir!!.isDirectory) {
                // If default file path doesn't exist, try creating it
                if (dir.scheme == "file") {
                    val f = File(dir.path!!)
                    if (f.mkdirs()) {
                        mainDir = DocumentFile.fromFile(f)
                    }
                }

                if (mainDir == null || !mainDir!!.isDirectory)
                    throw IOException("Invalid directory")
            }

            // TODO: .nomedia for downloads folder (permission denied)
//            if (!mainDir!!.listFiles().any { it.name == ".nomedia" }) {
//                documentFileFromUri(context, dir)?.createFile("audio/mka", ".nomedia")
//            }

            val newAllDirs = mutableListOf<DocumentFile>()
            newAllDirs.add(mainDir!!)
            if (extraDirs.isNotEmpty()) {
                newAllDirs.addAll(
                    documentFileFromUri(context, extraDirs.filterNot { it == dir }).filter { it.isDirectory }
                )
            }
            allDirs = newAllDirs.toList()
            Log.i(TAG, "Download manager initialized successfully. ${allDirs.size}")
        } catch (e: Exception) {
            if (mainDir == null) {
                Log.w(TAG, "Failed to initiate download manager: No directory provided")
            } else if (!mainDir!!.isDirectory) {
                Log.w(TAG, "Failed to initiate download manager: Not a valid directory")
            } else {
                Log.e(TAG, "Failed to initiate download manager: " + e.message)
            }

            mainDir = null
            allDirs = mutableListOf()
        }
    }

    fun deleteFile(mediaId: String): Boolean {
        val file = isExists(mediaId)
        return file?.delete() == true
    }

    fun saveFile(mediaId: String, input: InputStream, displayName: String?): Uri? {
        val directory = mainDir
        Log.d(TAG, "saveFile: mainDir=$directory, scheme=${directory?.uri?.scheme}")

        if (directory == null || !directory.isDirectory) {
            Log.e(TAG, "saveFile: Directory is INVALID or NULL")
            throw IOException("Invalid directory")
        }

        val fileName = "$displayName [$mediaId].mka"

        // FAST PATH: Direct File I/O for file:// URIs
        if (directory.uri.scheme == "file") {
            val dirPath = File(directory.uri.path!!)
            val outputFile = File(dirPath, fileName)

            // Delete existing file if present
            if (outputFile.exists()) {
                outputFile.delete()
            }

            Log.d(TAG, "saveFile: Starting write for $fileName")
            val startTime = System.currentTimeMillis()
            var bytesWritten = 0L

            // Write directly to File
            outputFile.outputStream().buffered(65536).use { out ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                var lastLogTime = startTime

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    bytesWritten += bytesRead

                    // Log progress every 2 seconds
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime > 2000) {
                        val elapsed = (now - startTime) / 1000.0
                        val speedKBps = (bytesWritten / 1024.0) / elapsed
                        Log.d(TAG, "saveFile: $fileName - ${bytesWritten / 1024}KB written in ${elapsed}s (${speedKBps.toInt()} KB/s)")
                        lastLogTime = now
                    }
                }
            }

            val totalTime = System.currentTimeMillis() - startTime
            val speedKBps = (bytesWritten / 1024.0) / (totalTime / 1000.0)
            Log.d(TAG, "saveFile: COMPLETE $fileName - ${bytesWritten / 1024}KB in ${totalTime}ms (${speedKBps.toInt()} KB/s)")
            Log.d(TAG, "saveFile: Returning absolute path: ${outputFile.absolutePath}")
            return Uri.fromFile(outputFile)
        }

        // SLOW PATH: DocumentFile for content:// URIs (SAF)
        val existing = directory.findFile(fileName)
        existing?.delete()

        val newFile = directory.createFile("audio/mka", fileName)

        newFile?.uri?.let { uri ->
            context.contentResolver.openOutputStream(uri)?.use { out ->
                input.copyTo(out, bufferSize = 65536)
            }
            return uri
        }

        return null
    }


    fun isExists(mediaId: String): DocumentFile? {
        // Optimized check: scan triggers if list empty
        if (availableFiles.isEmpty()) getAvailableFiles()

        // Check by ID in filename
        return availableFiles.find { it.name?.contains("[$mediaId]") == true }
    }

    fun getFilePathIfExists(mediaId: String): Uri? {
        return isExists(mediaId)?.uri
    }

    fun getMissingFiles(mediaId: List<Song>): List<Song> {
        val missingFiles = mediaId.toMutableSet()
        val result = getAvailableFiles(false)
        missingFiles.removeIf { f -> result.any { it.key == f.id } }
        return missingFiles.toList()
    }

    fun getAvailableFiles() = getAvailableFiles(true)

    fun getAvailableFiles(useCache: Boolean = true): Map<String, Uri> {
        val availableFiles = HashMap<String, Uri>()
        val result = ArrayList<DocumentFile>()
        if (useCache && this.availableFiles.isNotEmpty()) {
            result.addAll(this.availableFiles.toList())
        } else {
            for (dir in allDirs) {
                scanDfRecursive(dir, result, true)
            }
        }

        for (file in result) {
            val path = file.name ?: continue
            if (path.contains("[") && path.contains("]")) {
                availableFiles.put(path.substringAfterLast('[').substringBeforeLast(']'), file.uri)
            }
        }
        if (!useCache || this.availableFiles.isEmpty()) {
            this.availableFiles = result.toSet()
        }
        return availableFiles
    }

    fun getMainDlStorageUsage(): Long {
        if (mainDir == null) return -1L
        val result = ArrayList<DocumentFile>()
        scanDfRecursive(mainDir!!, result, true)

        return result.filter { it.name != null }.sumOf { it.length() }
    }

    fun getTotalDlStorageUsage(): Long {
        if (allDirs.isEmpty()) return 0

        // Recalculate to be safe
        val result = ArrayList<DocumentFile>()
        for (dir in allDirs) {
            scanDfRecursive(dir, result, true)
        }
        return result.sumOf { it.length() }
    }

    fun getExtraDlStorageUsage(): Long {
        val dirs = allDirs.filter { it != mainDir }
        if (dirs.isEmpty()) return 0
        val result = ArrayList<DocumentFile>()
        for (dir in dirs) {
            scanDfRecursive(dir, result, true)
        }

        return result.filter { it.name != null }.sumOf { it.length() }
    }
}