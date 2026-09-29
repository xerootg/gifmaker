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

class ExifDateParsingTest {
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    @Test fun plainDateTime() {
        assertEquals(utc(2024, 3, 9, 14, 5, 33), FrameMetadata.parseExifDateTime("2024:03:09 14:05:33", null, null))
    }

    @Test fun subSecondsAndOffset() {
        // 14:05:33 at +02:00 is 12:05:33 UTC; "7" sub-seconds means 700 ms.
        assertEquals(utc(2024, 3, 9, 12, 5, 33) + 700, FrameMetadata.parseExifDateTime("2024:03:09 14:05:33", "7", "+02:00"))
        assertEquals(utc(2024, 3, 9, 12, 5, 33) + 123, FrameMetadata.parseExifDateTime("2024:03:09 14:05:33", "123456", "+02:00"))
    }

    @Test fun rejectsBlankAndInvalid() {
        assertNull(FrameMetadata.parseExifDateTime("    :  :     :  :  ", null, null))
        assertNull(FrameMetadata.parseExifDateTime("0000:00:00 00:00:00", null, null))
        assertNull(FrameMetadata.parseExifDateTime("2024:13:09 14:05:33", null, null))
        assertNull(FrameMetadata.parseExifDateTime("garbage", null, null))
    }
}
