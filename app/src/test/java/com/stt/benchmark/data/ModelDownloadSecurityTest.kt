package com.stt.benchmark.data

import java.io.File
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadSecurityTest {
    @Test
    fun catalogUsesPinnedRevisionSizeAndSha256ForEveryModel() {
        assertEquals("5359861c739e955e79d9a303bcbc70fb988958b1", ModelDownloader.MODEL_REVISION)
        assertEquals(6, ModelDownloader.MODELS.size)
        val expected = mapOf(
            "ggml-tiny.bin" to (77_691_713L to "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"),
            "ggml-base.bin" to (147_951_465L to "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"),
            "ggml-base-q5_1.bin" to (59_707_625L to "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
            "ggml-small.bin" to (487_601_967L to "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"),
            "ggml-small-q5_1.bin" to (190_085_487L to "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
            "ggml-medium.bin" to (1_533_763_059L to "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208"),
        )

        ModelDownloader.MODELS.forEach { model ->
            assertTrue(model.url.contains("/resolve/${ModelDownloader.MODEL_REVISION}/"))
            assertFalse(model.url.contains("/resolve/main/"))
            assertTrue(model.expectedBytes > 0)
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
            assertEquals(expected[model.fileName]?.first, model.expectedBytes)
            assertEquals(expected[model.fileName]?.second, model.sha256)
        }
    }

    @Test
    fun trustedModelUrlsRequireHttpsAndHuggingFaceControlledHosts() {
        assertTrue(ModelDownloadSecurity.isTrustedModelUrl(URL("https://huggingface.co/path")))
        assertTrue(ModelDownloadSecurity.isTrustedModelUrl(URL("https://us.aws.cdn.hf.co/path")))

        assertFalse(ModelDownloadSecurity.isTrustedModelUrl(URL("http://huggingface.co/path")))
        assertFalse(ModelDownloadSecurity.isTrustedModelUrl(URL("https://huggingface.co.attacker.example/path")))
        assertFalse(ModelDownloadSecurity.isTrustedModelUrl(URL("https://attacker.example/path")))
    }

    @Test
    fun verifiedModelRequiresExactByteSizeAndDigest() {
        val directory = Files.createTempDirectory("long-stt-model-integrity").toFile()
        val file = File(directory, "ggml-test.bin")
        try {
            file.writeText("verified model")
            val model = ModelDownloader.ModelInfo(
                displayName = "test",
                fileName = file.name,
                sizeMb = 1,
                description = "test",
                expectedBytes = file.length(),
                sha256 = ModelDownloadSecurity.sha256(file),
            )

            assertTrue(model.isVerified(file))
            assertNotNull(model.installedFile(directory))

            file.appendText("tampered")
            assertFalse(model.isVerified(file))
            assertFalse(model.existsIn(directory))
        } finally {
            directory.deleteRecursively()
        }
    }
}
