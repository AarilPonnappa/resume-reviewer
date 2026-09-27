package com._6.resumeiq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com._6.resumeiq.dto.ExtractedResume;

class ResumeTextExtractorTest {

    private final ResumeTextExtractor extractor = new ResumeTextExtractor();

    @Test
    void readsTextFiles() throws IOException {
        ExtractedResume resume = extractor.extract("resume.txt", "Jordan Lee\r\nJava, Python\r\n\r\n\r\n\r\nBerkeley".getBytes(StandardCharsets.UTF_8));
        assertFalse(resume.pdf());
        assertEquals("Jordan Lee\nJava, Python\n\nBerkeley", resume.text());
    }

    @Test
    void readsDocxParagraphsHeadersAndLinks() throws IOException {
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>EXPERIENCE</w:t></w:r></w:p>
                    <w:p><w:r><w:t xml:space="preserve">Brightline Analytics </w:t></w:r><w:r><w:tab/><w:t>Jun 2025</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Built services in Java &amp; Spring Boot</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """;
        String header = """
                <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:p><w:r><w:t>Jordan Lee</w:t></w:r></w:p>
                </w:hdr>
                """;
        String rels = """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                     Target="https://github.com/jordanlee" TargetMode="External"/>
                </Relationships>
                """;

        byte[] docx = zip(new String[][] {
                { "[Content_Types].xml", "<Types/>" },
                { "word/document.xml", document.strip() },
                { "word/header1.xml", header },
                { "word/_rels/document.xml.rels", rels } });

        ExtractedResume resume = extractor.extract("resume.docx", docx);
        String text = resume.text();

        assertFalse(resume.pdf());
        assertTrue(text.startsWith("Jordan Lee"), text);
        assertTrue(text.contains("Brightline Analytics \tJun 2025"), text);
        assertTrue(text.contains("Built services in Java & Spring Boot"), text);
        assertTrue(text.contains("https://github.com/jordanlee"), text);
    }

    @Test
    void rejectsUnsupportedAndEmptyFiles() {
        assertThrows(IllegalArgumentException.class, () -> extractor.extract("photo.png", new byte[] { 1, 2, 3 }));
        assertThrows(IllegalArgumentException.class, () -> extractor.extract("resume.pdf", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> extractor.extract("resume.doc", "old".getBytes(StandardCharsets.UTF_8)));
        // named .docx but isn't a zip file
        assertThrows(IllegalArgumentException.class, () -> extractor.extract("resume.docx", "not a zip".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] zip(String[][] files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] file : files) {
                zip.putNextEntry(new ZipEntry(file[0]));
                zip.write(file[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
