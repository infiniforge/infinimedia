package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class MtvMetadataLabelFormatterTest {
    @Test fun emitsTheFiveRequestedRowsAndQuotesTrackTitle() {
        assertEquals(
            listOf("Pink Floyd", "\"Money\"", "The Dark Side of the Moon", "Harvest", "Alan Parsons"),
            MtvMetadataLabelFormatter.format(
                fileName = "05 - Money.mp3",
                title = "Money",
                artist = "Pink Floyd",
                album = "The Dark Side of the Moon",
                recordCompany = "Harvest",
                producer = "Alan Parsons",
            ),
        )
    }

    @Test fun missingLabelAndProducerRemainBlankRows() {
        assertEquals(
            listOf("Artist", "\"Track\"", "Album", "", ""),
            MtvMetadataLabelFormatter.format("track.mp3", "Track", "Artist", "Album"),
        )
    }

    @Test fun fileNameIsShownAloneWhenThereIsNoActualMetadata() {
        assertEquals(
            listOf("05 - Money.mp3"),
            MtvMetadataLabelFormatter.format("05 - Money.mp3", "05 - Money", "Unknown Artist", null),
        )
    }

    @Test fun titleOnlyMetadataRetainsFiveRows() {
        assertEquals(
            listOf("", "\"Money\"", "", "", ""),
            MtvMetadataLabelFormatter.format("05 - Money.mp3", "Money", null, null),
        )
    }
}
