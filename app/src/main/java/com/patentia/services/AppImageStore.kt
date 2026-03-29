package com.patentia.services

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

class AppImageStore(
    private val appContext: Context,
) {

    fun persist(sourceUri: Uri): Uri {
        if (sourceUri.scheme == "file") {
            val sourceFile = sourceUri.path?.let(::File)
            if (sourceFile != null && sourceFile.exists() && sourceFile.parentFile == imagesDirectory()) {
                return sourceUri
            }
        }

        val targetFile = createImageFile()
        openInputStream(sourceUri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: error("Image source is not readable")

        return Uri.fromFile(targetFile)
    }

    fun delete(uri: Uri) {
        if (uri.scheme != "file") {
            return
        }

        val targetFile = uri.path?.let(::File) ?: return
        if (targetFile.parentFile == imagesDirectory()) {
            runCatching { targetFile.delete() }
        }
    }

    fun createImageFile(): File {
        return File(imagesDirectory(), "capture_${System.currentTimeMillis()}.jpg")
    }

    private fun imagesDirectory(): File {
        return File(appContext.filesDir, "images").apply { mkdirs() }
    }

    private fun openInputStream(sourceUri: Uri) = when (sourceUri.scheme) {
        "file" -> sourceUri.path?.let(::File)?.takeIf(File::exists)?.inputStream()
        else -> appContext.contentResolver.openInputStream(sourceUri)
    }
}