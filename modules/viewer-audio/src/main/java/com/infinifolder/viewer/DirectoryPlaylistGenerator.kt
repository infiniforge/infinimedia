package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import com.infinifolder.archive.ArchiveFormats
import io.github.gretchycat.infiniselect.FolderStorage
import io.github.gretchycat.infiniselect.InfiniSelect
import java.io.InterruptedIOException
import java.util.ArrayDeque
import java.util.Locale

/** Builds a Media-owned queue from Folder's public, paged directory-listing API. */
internal object DirectoryPlaylistGenerator {
    data class Result(
        val tracks: List<AudioTrack>,
        val foldersVisited: Int,
        val unavailableItems: Int,
        val truncated: Boolean,
    )

    private data class FolderNode(
        val uri: Uri,
        val name: String,
        val relativePath: String,
        val depth: Int,
    )

    private data class Candidate(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long?,
        val directoryUri: Uri,
        val directoryPath: String,
    ) {
        val filePath: String
            get() = if (directoryPath.isBlank()) name else "$directoryPath/$name"
    }

    private data class ResolvedCandidate(
        val candidate: Candidate,
        val accessUri: Uri,
        val authoritativeUri: Uri,
    )

    fun generate(context: Context, root: Uri, rootNameHint: String? = null): Result {
        require(FolderStorage.available(context)) {
            "InfiniFolder's directory-listing provider is unavailable"
        }
        val pending = ArrayDeque<FolderNode>()
        val visitedDirectories = hashSetOf<String>()
        val seenResources = hashSetOf<String>()
        val candidates = mutableListOf<Candidate>()
        val rootName = rootNameHint?.takeIf(String::isNotBlank)
            ?: InfiniSelect.displayName(context, root)?.takeIf(String::isNotBlank)
                ?.takeUnless(::looksLikeOpaqueId)
            ?: "Media directory"
        pending.add(FolderNode(root, rootName, "", 0))
        var foldersVisited = 0
        var unavailableItems = 0
        var truncated = false

        while (pending.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Directory scan cancelled")
            val folder = pending.removeFirst()
            val folderIdentity = folder.uri.toString()
            if (!visitedDirectories.add(folderIdentity)) continue
            if (folder.depth > MAX_DEPTH || ++foldersVisited > MAX_FOLDERS) {
                truncated = true
                break
            }

            val listing = try {
                completeListing(context, folder.uri)
            } catch (error: Exception) {
                unavailableItems++
                if (folder.depth == 0) throw error
                com.infinifolder.storage.DiagnosticLog.record(
                    "audio.playlist.directory-list.failed",
                    "directory=${folder.uri}",
                    error,
                )
                continue
            }
            if (!listing.notice.isNullOrBlank()) {
                com.infinifolder.storage.DiagnosticLog.record(
                    "audio.playlist.directory-list.notice",
                    "directory=${folder.uri} notice=${listing.notice}",
                )
            }
            val directoryIdentity = InfiniSelect.authoritativeUri(context, folder.uri.toString()) ?: folder.uri
            for (entry in listing.entries) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Directory scan cancelled")
                val entryIdentity = entry.uri.toString()
                if (entry.directory) {
                    // The public listing marks archive files as directories too. Only the
                    // selected root may itself be an archive; nested archives stay files.
                    if (ArchiveFormats.isArchive(entry.name, entry.mime) && folder.depth > 0) continue
                    if (folder.depth + 1 > MAX_DEPTH) {
                        truncated = true
                        continue
                    }
                    val childPath = if (folder.relativePath.isEmpty()) entry.name
                        else "${folder.relativePath}/${entry.name}"
                    pending.add(FolderNode(entry.uri, entry.name, childPath, folder.depth + 1))
                } else if (seenResources.add(entryIdentity) && isSupportedMedia(entry.name, entry.mime)) {
                    candidates += Candidate(
                        uri = entry.uri,
                        name = entry.name,
                        mimeType = entry.mime,
                        sizeBytes = entry.size,
                        directoryUri = directoryIdentity,
                        directoryPath = folder.relativePath,
                    )
                    if (candidates.size >= MAX_MEDIA) {
                        truncated = pending.isNotEmpty()
                        break
                    }
                }
            }
            if (candidates.size >= MAX_MEDIA) break
        }

        val ordered = candidates.sortedWith(compareBy<Candidate>(
            { it.name.lowercase(Locale.ROOT) },
            { it.name },
            { it.directoryPath.lowercase(Locale.ROOT) },
            { it.directoryPath },
            { it.uri.toString() },
        ))
        val resolved = ordered.mapNotNull { item ->
            try {
                val source = InfiniSelect.requireSource(context, item.uri.toString(),
                    item.name, item.mimeType, item.sizeBytes)
                ResolvedCandidate(item, source.contentUri, source.authoritativeUri)
            } catch (error: Exception) {
                unavailableItems++
                com.infinifolder.storage.DiagnosticLog.record(
                    "audio.playlist.directory-item.unavailable",
                    "name=${item.name} source=${item.uri}",
                    error,
                )
                null
            }
        }
        val coversByDirectory = resolved.asSequence()
            .filter { it.candidate.name.startsWith("cover.", ignoreCase = true) }
            .groupBy { it.candidate.directoryUri.toString() }
            .mapValues { (_, items) -> items.first() }

        val tracks = resolved.map { item ->
            val candidate = item.candidate
            val cover = coversByDirectory[candidate.directoryUri.toString()]
            val kind = when (MediaTypeDetector.detect(candidate.name, candidate.mimeType)) {
                MediaType.VIDEO -> PlaylistMediaKind.VIDEO
                MediaType.IMAGE -> PlaylistMediaKind.IMAGE
                else -> PlaylistMediaKind.AUDIO
            }
            AudioTrack(
                uri = item.accessUri,
                title = candidate.name,
                fileName = candidate.name,
                filePath = "$rootName/${candidate.filePath}",
                sourceReference = item.authoritativeUri.toString(),
                directoryUri = candidate.directoryUri,
                directoryName = if (candidate.directoryPath.isBlank()) rootName
                    else "$rootName/${candidate.directoryPath}",
                directoryReference = candidate.directoryUri.toString(),
                directoryCoverUri = cover?.accessUri,
                directoryCoverReference = cover?.authoritativeUri?.toString(),
                directoryArtworkMetadataResolved = true,
                mediaKind = kind,
                sourceMimeType = candidate.mimeType,
            )
        }
        return Result(tracks, foldersVisited, unavailableItems, truncated)
    }

    private fun completeListing(context: Context, uri: Uri): FolderStorage.Listing {
        var listing = FolderStorage.list(context, uri)
        var attempts = 0
        while (listing.loading && attempts < LOADING_RETRIES) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Directory scan cancelled")
            try {
                Thread.sleep(LOADING_RETRY_MS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Directory scan cancelled").apply { initCause(error) }
            }
            listing = FolderStorage.list(context, uri)
            attempts++
        }
        return listing
    }

    private fun looksLikeOpaqueId(value: String): Boolean =
        value.matches(Regex("(?i)[0-9a-f]{32,64}"))

    private fun isSupportedMedia(name: String, mimeType: String): Boolean {
        if (PlaylistExpander.isPlaylist(name, mimeType)) return false
        return MediaTypeDetector.detect(name, mimeType) in setOf(MediaType.AUDIO, MediaType.VIDEO, MediaType.IMAGE)
    }

    private const val MAX_DEPTH = 128
    private const val MAX_FOLDERS = 50_000
    private const val MAX_MEDIA = 50_000
    private const val LOADING_RETRIES = 6
    private const val LOADING_RETRY_MS = 150L
}
