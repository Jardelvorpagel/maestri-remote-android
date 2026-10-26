package app.maestri.live

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentKindTest {

    @Test
    fun kindAndExtensionFollowTheReferenceLabels() {
        assertEquals("Imagem · JPG", attachmentKindLabel("sala.jpg", "image/jpeg"))
        assertEquals("Áudio · M4A", attachmentKindLabel("voice-20261025.m4a", "audio/mp4"))
        assertEquals("Arquivo · PDF", attachmentKindLabel("report.pdf", "application/pdf"))
        assertEquals("Texto · MD", attachmentKindLabel("notes.md", "text/plain"))
        assertEquals("Vídeo · MP4", attachmentKindLabel("clip.mp4", "video/mp4"))
    }

    @Test
    fun headerOnlyImageStillReadsAsAnImage() {
        assertEquals("Imagem", attachmentKindLabel("IMG-20261025-WA0047", "image/jpeg"))
        assertEquals("Áudio", attachmentKindLabel("voice.20261025", "audio/mp4"))
    }

    @Test
    fun aLongTailIsNotMistakenForAnExtension() {
        assertEquals("Arquivo", attachmentKindLabel("archive.tar.gz.numbers", "application/octet-stream"))
    }
}
