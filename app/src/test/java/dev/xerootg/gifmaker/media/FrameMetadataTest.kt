package dev.xerootg.gifmaker.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FrameMetadataTest {
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    @Test fun parsesCameraStyleNames() {
        assertEquals(utc(2024, 3, 9, 14, 5, 33), FrameMetadata.dateFromName("IMG_20240309_140533.jpg"))
        assertEquals(utc(2023, 12, 31, 23, 59, 59), FrameMetadata.dateFromName("PXL_20231231_235959123.jpg"))
        assertEquals(utc(2022, 7, 4, 0, 0, 0), FrameMetadata.dateFromName("2022-07-04 picnic.jpg"))
    }

    @Test fun rejectsNonsense() {
        assertNull(FrameMetadata.dateFromName("DSC_0001.JPG"))
        assertNull(FrameMetadata.dateFromName("IMG_20241399_000000.jpg"))
    }
}
