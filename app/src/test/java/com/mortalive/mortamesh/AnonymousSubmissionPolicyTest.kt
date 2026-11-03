package com.mortalive.mortamesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonymousSubmissionPolicyTest {
    @Test fun acceptsOnlyAValidGroupReference() {
        assertTrue(AnonymousSubmissionPolicy.isValidGroupId("group_2026_alpha"))
        assertFalse(AnonymousSubmissionPolicy.isValidGroupId(""))
        assertFalse(AnonymousSubmissionPolicy.isValidGroupId("short"))
        assertFalse(AnonymousSubmissionPolicy.isValidGroupId("bad group id"))
    }

    @Test fun acceptsSupportedPhotoVideoAndAudioTypes() {
        listOf("image/jpeg", "image/webp", "video/mp4", "audio/mpeg", "audio/ogg").forEach {
            assertTrue("$it should be supported", AnonymousSubmissionPolicy.isAcceptedMime(it))
        }
        assertFalse(AnonymousSubmissionPolicy.isAcceptedMime("application/zip"))
        assertFalse(AnonymousSubmissionPolicy.isAcceptedMime("application/vnd.android.package-archive"))
    }

    @Test fun validatesTextAndAttachmentTogether() {
        assertNull(AnonymousSubmissionPolicy.validate("group_2026_alpha", "Context", null, 0L))
        assertNull(AnonymousSubmissionPolicy.validate("group_2026_alpha", "", "image/jpeg", 1L))
        assertNotNull(AnonymousSubmissionPolicy.validate("group_2026_alpha", "", null, 0L))
        assertNotNull(AnonymousSubmissionPolicy.validate("group_2026_alpha", "x", "image/jpeg", 0L))
        assertNotNull(AnonymousSubmissionPolicy.validate("group_2026_alpha", "", "application/zip", 1L))
        assertNotNull(AnonymousSubmissionPolicy.validate("group_2026_alpha", "", "image/jpeg", AnonymousSubmissionPolicy.MAX_ATTACHMENT_BYTES + 1))
    }

    @Test fun boundsTextAndRejectsBrokenUnicode() {
        assertTrue(AnonymousSubmissionPolicy.isValidText("😀".repeat(AnonymousSubmissionPolicy.MAX_TEXT_CODE_POINTS)))
        assertFalse(AnonymousSubmissionPolicy.isValidText("😀".repeat(AnonymousSubmissionPolicy.MAX_TEXT_CODE_POINTS + 1)))
        assertFalse(AnonymousSubmissionPolicy.isValidText("bad\u0000text"))
        assertFalse(AnonymousSubmissionPolicy.isValidText("\uD83D"))
        assertTrue(AnonymousSubmissionPolicy.isValidText("line one\nline two"))
    }
}
