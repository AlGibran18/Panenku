package com.panenku.backend

import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.common.storage.LocalPrivateImageStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.springframework.mock.web.MockMultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import javax.imageio.ImageIO

@EnabledOnOs(OS.MAC, OS.LINUX)
class LocalPrivateImageStoreTest {
    @TempDir lateinit var tempDir: Path

    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output)
        output.toByteArray()
    }

    @Test fun `menyimpan membaca dan menghapus file privat`() {
        val root = tempDir.resolve("private")
        val storage = LocalPrivateImageStore(root)
        val image = png()
        val key = storage.upload(MockMultipartFile("file", "../../nama-asli.png", "image/png", image))
        assertFalse(key.contains("nama-asli"))
        assertArrayEquals(image, storage.read(key))
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(root.resolve(key)))
        storage.delete(key)
        assertFalse(Files.exists(root.resolve(key)))
    }

    @Test fun `file palsu kosong terlalu besar dan kunci traversal ditolak`() {
        val storage = LocalPrivateImageStore(tempDir.resolve("private"))
        assertThrows(ApiException::class.java) { storage.upload(MockMultipartFile("file", "a.png", "image/png", byteArrayOf(1, 2, 3))) }
        assertThrows(ApiException::class.java) { storage.upload(MockMultipartFile("file", "a.png", "image/png", ByteArray(0))) }
        assertThrows(ApiException::class.java) { storage.upload(MockMultipartFile("file", "a.png", "image/png", ByteArray(5 * 1024 * 1024 + 1))) }
        assertThrows(ApiException::class.java) { storage.read("../secret") }
        assertThrows(ApiException::class.java) { storage.delete("../secret") }
    }

    @Test fun `direktori yang tidak privat ditolak`() {
        val root = Files.createDirectory(tempDir.resolve("public"))
        Files.setPosixFilePermissions(root, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ))
        assertThrows(IllegalArgumentException::class.java) { LocalPrivateImageStore(root) }
    }

    @Test fun `symlink tidak dibaca sebagai bukti`() {
        val root = tempDir.resolve("private")
        val storage = LocalPrivateImageStore(root)
        val outside = Files.write(tempDir.resolve("secret"), byteArrayOf(1, 2, 3))
        val key = "00000000-0000-0000-0000-000000000000.png"
        Files.createSymbolicLink(root.resolve(key), outside)
        assertThrows(Exception::class.java) { storage.read(key) }
    }
}
