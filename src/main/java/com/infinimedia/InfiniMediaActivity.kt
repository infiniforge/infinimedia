package com.infinimedia

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import io.github.gretchycat.infiniselect.InfiniSelect
import io.github.gretchycat.infiniselect.FileSelectionContract
import com.infinifolder.viewer.ViewerActivity

class InfiniMediaActivity : ViewerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openMediaPlayer(intent)
        finish()
    }
    /** Forward selected URIs and directory identities to Media's app-owned playlist builder. */
    private fun openMediaPlayer(source: Intent?) {
        val action = source?.action
        val uris = linkedSetOf<Uri>()
        source?.data?.let(uris::add)
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            source?.getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM)?.forEach(uris::add)
            source?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add)
            }
            source?.getParcelableExtraCompat(Intent.EXTRA_STREAM)?.let(uris::add)
        }
        val sourceReferences = source?.getStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_AUTHORITATIVE_URIS)
            ?: source?.getStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_SOURCE_REFERENCES)
        val singleReference = source?.getStringExtra(FileSelectionContract.EXTRA_RESULT_AUTHORITATIVE_URI)
            ?: source?.getStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_SOURCE_REFERENCES)?.firstOrNull()
        fun authoritativeReference(uri: Uri, index: Int): String? =
            sourceReferences?.getOrNull(index)?.takeIf(String::isNotBlank)
                ?: singleReference?.takeIf { uri == source?.data || uris.size == 1 }
                ?: runCatching { InfiniSelect.authoritativeUri(this, uri.toString())?.toString() }.getOrNull()
                ?: InfiniSelect.authoritativeCandidate(null, uri)?.toString()
        fun mimeType(uri: Uri, index: Int): String =
            source?.getStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_MIMES)?.getOrNull(index)
                ?.takeIf(String::isNotBlank)
                ?: runCatching { contentResolver.getType(uri) }.getOrNull()
                ?: "application/octet-stream"

        val only = uris.singleOrNull()
        val mime = only?.let { source?.type?.takeIf(String::isNotBlank) ?: mimeType(it, 0) }
        val directoryUri = source?.getStringExtra(FileSelectionContract.EXTRA_RESULT_DIRECTORY)
            ?.takeIf(String::isNotBlank)?.let(Uri::parse)
            ?: only?.takeIf { mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR }
        val isDirectory = directoryUri != null
        val playerIntent = when {
            directoryUri != null -> Intent(this, com.infinifolder.viewer.AudioPlayerActivity::class.java).apply {
                setAction(Intent.ACTION_VIEW)
                setDataAndType(directoryUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                val directoryName = source?.getStringExtra("displayName")
                    ?: source?.getStringExtra("extra_name") ?: displayName(directoryUri)
                putExtra("displayName", directoryName)
                putExtra("mimeType", android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                val directoryReference = source?.getStringExtra(FileSelectionContract.EXTRA_RESULT_DIRECTORY_REFERENCE)
                    ?: source?.getStringExtra(FileSelectionContract.EXTRA_RESULT_AUTHORITATIVE_URI)
                    ?: authoritativeReference(directoryUri, uris.indexOf(directoryUri).coerceAtLeast(0))
                    ?: directoryUri.toString()
                putExtra(FileSelectionContract.EXTRA_RESULT_DIRECTORY_REFERENCE, directoryReference)
                putExtra(FileSelectionContract.EXTRA_RESULT_AUTHORITATIVE_URI, directoryReference)
                putExtra(FileSelectionContract.EXTRA_RESULT_DIRECTORY, directoryUri.toString())
                val directoryPathName = source?.getStringExtra(FileSelectionContract.EXTRA_RESULT_DIRECTORY_NAME)
                    ?: source?.getStringExtra("displayName")?.removePrefix("Media from ")
                directoryPathName?.takeIf(String::isNotBlank)?.let {
                    putExtra(FileSelectionContract.EXTRA_RESULT_DIRECTORY_NAME, it)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            only == null -> Intent(this, com.infinifolder.viewer.AudioPlayerActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
            uris.size > 1 -> Intent(this, com.infinifolder.viewer.AudioPlayerActivity::class.java).apply {
                setAction(Intent.ACTION_SEND_MULTIPLE)
                setType(source?.type?.takeIf(String::isNotBlank) ?: "*/*")
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                sourceReferences?.let {
                    putStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_AUTHORITATIVE_URIS, it)
                    putStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_SOURCE_REFERENCES, it)
                }
                source?.getStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_NAMES)?.let {
                    putStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_NAMES, it)
                }
                source?.getStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_MIMES)?.let {
                    putStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_MIMES, it)
                }
            }
            else -> Intent(this, com.infinifolder.viewer.AudioPlayerActivity::class.java).apply {
                setAction(Intent.ACTION_VIEW)
                setDataAndType(only, mime ?: "application/octet-stream")
                putExtra("displayName", source?.getStringExtra("displayName")
                    ?: source?.getStringExtra("extra_name") ?: displayName(only))
                putExtra("mimeType", mime)
                authoritativeReference(only, 0)?.let {
                    putExtra(FileSelectionContract.EXTRA_RESULT_AUTHORITATIVE_URI, it)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        if (directoryUri != null) {
            val directoryName = source?.getStringExtra("displayName") ?: displayName(directoryUri)
            playerIntent.clipData = android.content.ClipData.newRawUri(directoryName, directoryUri).apply {
                source?.clipData?.let { existing ->
                    for (index in 0 until existing.itemCount) {
                        val item = existing.getItemAt(index)
                        if (item.uri != directoryUri) addItem(item)
                    }
                }
            }
        } else {
            source?.clipData?.let { playerIntent.clipData = it }
        }
        playerIntent.addFlags(source?.flags?.and(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ) ?: 0)
        if (only != null && !isDirectory &&
            com.infinifolder.viewer.MediaTypeDetector.detect(displayName(only), mime.orEmpty()) ==
                com.infinifolder.viewer.MediaType.VIDEO) {
            playerIntent.setClass(this, com.infinifolder.viewer.VideoPlayerActivity::class.java)
        }
        runCatching { startActivity(playerIntent) }.onFailure { error ->
            Toast.makeText(this, "Could not open selected media: ${error.message ?: "player launch failed"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Media item"

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(key: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<Uri>? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java)
        else getParcelableArrayListExtra(key)
}
