package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.RectF
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderBorderCropPolicyTest {
    @Test
    fun `sparse pages disable rendering crop and OCR crop together`() {
        val decision = ReaderBorderCropPolicy.decide(
            enabled = true,
            isAnimated = false,
            isSparse = { true },
            detectCrop = { error("Sparse pages must not run native cropping") },
        )
        assertFalse(decision.enabled)
        assertNull(decision.rect)
    }

    @Test
    fun `normal pages preserve the native crop rectangle`() {
        val rect = mockk<RectF>()
        val decision = ReaderBorderCropPolicy.decide(true, false, { false }, { rect })
        assertTrue(decision.enabled)
        assertSame(rect, decision.rect)
    }

    @Test
    fun `analysis failure preserves existing crop behavior`() {
        val rect = mockk<RectF>()
        val decision = ReaderBorderCropPolicy.decide(true, false, { error("Decode failed") }, { rect })
        assertTrue(decision.enabled)
        assertSame(rect, decision.rect)
    }

    @Test
    fun `no detected border preserves rendering flag without OCR remapping`() {
        val decision = ReaderBorderCropPolicy.decide(true, false, { false }, { null })
        assertTrue(decision.enabled)
        assertNull(decision.rect)
    }

    @Test
    fun `disabled crop and animated pages bypass analysis`() {
        for ((enabled, animated) in listOf(false to false, false to true, true to true)) {
            val decision = ReaderBorderCropPolicy.decide(
                enabled,
                animated,
                { error("Thumbnail analysis must be skipped") },
                { error("Native crop must be skipped") },
            )
            assertTrue(decision.enabled == enabled)
            assertNull(decision.rect)
        }
    }

    @Test
    fun `cancellation is propagated instead of falling back to cropping`() {
        val cancellation = CancellationException("Page unloaded")
        val thrown = assertThrows(CancellationException::class.java) {
            ReaderBorderCropPolicy.decide(
                true,
                false,
                { throw cancellation },
                { error("Cancelled work must stop") },
            )
        }
        assertSame(cancellation, thrown)
    }
}
