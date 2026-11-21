package com.infinifolder.viewer

import java.util.Locale

/** Formats the player-owned lower third as the classic five-line metadata stack. */
internal object MtvMetadataLabelFormatter {
    fun format(
        fileName: String,
        title: String?,
        artist: String?,
        album: String?,
        recordCompany: String? = null,
        producer: String? = null,
    ): List<String> {
        val fallbackName = fileName.trim().ifBlank { title.orEmpty().trim() }.ifBlank { "Unknown track" }
        val cleanArtist = clean(artist)
        val titleCandidate = clean(title)
        val cleanAlbum = clean(album)
        val cleanCompany = clean(recordCompany)
        val cleanProducer = clean(producer)
        val otherMetadataExists = cleanArtist != null || cleanAlbum != null ||
            cleanCompany != null || cleanProducer != null
        val cleanTitle = titleCandidate?.takeUnless {
            !otherMetadataExists && isFileNameTitle(it, fallbackName)
        }
        if (cleanArtist == null && cleanTitle == null && cleanAlbum == null &&
            cleanCompany == null && cleanProducer == null
        ) {
            return listOf(fallbackName)
        }
        return listOf(
            cleanArtist.orEmpty(),
            cleanTitle?.let { "\"$it\"" }.orEmpty(),
            cleanAlbum.orEmpty(),
            cleanCompany.orEmpty(),
            cleanProducer.orEmpty(),
        )
    }

    private fun clean(value: String?): String? =
        value?.trim()?.takeIf {
            it.isNotEmpty() && it.lowercase(Locale.ROOT) !in IGNORED_VALUES
        }

    private fun isFileNameTitle(title: String, fileName: String): Boolean {
        fun withoutExtension(value: String): String =
            value.substringAfterLast('/').substringBeforeLast('.', value.substringAfterLast('/'))
                .trim().lowercase(Locale.ROOT)
        val normalizedTitle = title.trim().lowercase(Locale.ROOT)
        val normalizedFileName = fileName.trim().lowercase(Locale.ROOT)
        return normalizedTitle == normalizedFileName || normalizedTitle == withoutExtension(fileName)
    }

    private val IGNORED_VALUES = setOf("unknown", "unknown artist", "unknown album", "<unknown>", "n/a")
}
