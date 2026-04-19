package com.hermitech.hermivision.data.inference

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ModelCache {
    /**
     * Extracts a model file from assets to the cache directory and returns its absolute path.
     * If the file already exists in the cache, it returns the existing path to save time.
     */
    fun extractModelToCache(context: Context, modelName: String): String {
        val cacheDir = File(context.cacheDir, "models")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val outFile = File(cacheDir, modelName)

        // Return cached file if it already exists
        if (outFile.exists() && outFile.length() > 0) {
            return outFile.absolutePath
        }

        try {
            val assetPath = if (modelName.startsWith("models/")) modelName else "models/$modelName"
            context.assets.open(assetPath).use { inputStream ->
                FileOutputStream(outFile).use { outputStream ->
                    val buffer = ByteArray(4 * 1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.flush()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return ""
        }
        return outFile.absolutePath
    }
}