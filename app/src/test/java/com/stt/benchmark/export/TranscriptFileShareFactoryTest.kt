package com.stt.benchmark.export

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceSection
import com.stt.benchmark.data.TranscriptSourceType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class TranscriptFileShareFactoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val exportDir get() = File(context.cacheDir, TranscriptFileShareFactory.CACHE_DIRECTORY)

    @Before
    @After
    fun clean() {
        exportDir.deleteRecursively()
    }

    @Test
    fun sharePayloadContainsOnlyGrantedContentUriAttachment() {
        val chooser = TranscriptFileShareFactory(context).createChooser(document(), nowMs = 2_000_000L)
        val intent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(TranscriptFileShareFactory.SHARE_TITLE, chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(setOf(Intent.EXTRA_STREAM), intent.extras?.keySet())
        assertNotNull(uri)
        assertEquals("content", uri?.scheme)
        assertTrue(uri.toString().startsWith("content://${context.packageName}.fileprovider/"))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertFalse(uri.toString().contains(document().source.id))
        assertTrue(exportDir.listFiles().orEmpty().single().readText(Charsets.UTF_8).contains("공유할 전체 전사"))
    }

    @Test
    fun cleanupDeletesOnlyExpiredDirectCacheFiles() {
        val cacheRoot = File(context.cacheDir, "cleanup_contract").apply { deleteRecursively(); mkdirs() }
        val directory = File(cacheRoot, TranscriptFileShareFactory.CACHE_DIRECTORY).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val expired = File(directory, "expired.txt").apply {
            writeText("expired")
            assertTrue(setLastModified(now - TranscriptFileShareFactory.CACHE_RETENTION_MS - 1_000L))
        }
        val retained = File(directory, "retained.txt").apply {
            writeText("retained")
            assertTrue(setLastModified(now - 1_000L))
        }

        try {
            val deleted = TranscriptFileShareFactory(context, cacheRoot = cacheRoot).cleanupExpired(now)

            assertEquals(1, deleted)
            assertFalse(expired.exists())
            assertTrue(retained.exists())
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun providerFailureRemovesPartiallyPreparedShareFile() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptFileShareFactory(
                context = context,
                authority = "${context.packageName}.missing-provider",
            ).create(document(), nowMs = System.currentTimeMillis())
        }

        assertTrue(exportDir.listFiles().orEmpty().isEmpty())
    }

    private fun document() = TranscriptSourceDocument(
        source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "stt_secret_source"),
        updatedAtMs = 1_000L,
        sections = listOf(
            TranscriptSourceSection("U0001", "구간 1/1", 0L, 1_000L, "공유할 전체 전사"),
        ),
    )
}
