package com._6.resumeiq.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import com._6.resumeiq.dto.ExtractedResume;

// Turns an uploaded resume (PDF, DOCX or TXT) into plain text.
// The text is used two ways: Gemini reads it, and FabricationGuard compares the rewrite against it.
@Service
public class ResumeTextExtractor {

    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    // guards against "zip bombs": a tiny .docx that expands into gigabytes
    private static final int MAX_DOCX_PART_BYTES = 20 * 1024 * 1024;

    private static final Pattern RELATIONSHIP_URL = Pattern.compile("Target=\"(https?://[^\"]+|mailto:[^\"]+)\"");

    public ExtractedResume extract(String fileName, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The uploaded file is empty.");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Resume files must be 5 MB or smaller.");
        }

        String name = (fileName == null || fileName.isBlank()) ? "resume" : fileName;
        String lowerName = name.toLowerCase(Locale.ROOT);

        // check the file's first bytes (its "magic number") instead of trusting the extension alone
        if (startsWith(bytes, "%PDF")) {
            return new ExtractedResume(name, true, bytes, tidyText(extractPdfText(bytes)));
        }
        if (lowerName.endsWith(".docx") && startsWith(bytes, "PK")) {
            return new ExtractedResume(name, false, null, tidyText(extractDocxText(bytes)));
        }
        if (lowerName.endsWith(".txt") || lowerName.endsWith(".md")) {
            return new ExtractedResume(name, false, null, tidyText(new String(bytes, StandardCharsets.UTF_8)));
        }
        if (lowerName.endsWith(".doc")) {
            throw new IllegalArgumentException("Old .doc files aren't supported. Save the resume as PDF or .docx and upload again.");
        }
        throw new IllegalArgumentException("Unsupported file type. Upload your resume as a PDF, DOCX or TXT file.");
    }

    // ---------------------------------------------------------------------------------------------
    // PDF: text layer via PDFBox, plus the targets of clickable links (e.g. a "GitHub" word that links to github.com/you)
    // ---------------------------------------------------------------------------------------------
    private String extractPdfText(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            Set<String> links = new LinkedHashSet<>();
            for (PDPage page : document.getPages()) {
                for (PDAnnotation annotation : page.getAnnotations()) {
                    if (annotation instanceof PDAnnotationLink link) {
                        PDAction action = link.getAction();
                        if (action instanceof PDActionURI uriAction && uriAction.getURI() != null) {
                            links.add(uriAction.getURI());
                        }
                    }
                }
            }
            return appendLinks(text, links);
        } catch (InvalidPasswordException e) {
            throw new IllegalArgumentException("This PDF is password protected. Remove the password and upload again.");
        } catch (IOException e) {
            throw new IllegalArgumentException("This PDF could not be read. Try exporting it again from your editor.");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DOCX: a .docx is a zip file; the text lives in word/document.xml (plus headers/footers) as <w:t> elements
    // ---------------------------------------------------------------------------------------------
    private String extractDocxText(byte[] bytes) throws IOException {
        byte[] documentXml = null;
        byte[] relationshipsXml = null;
        List<byte[]> headerFooterXml = new ArrayList<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.equals("word/document.xml")) {
                    documentXml = readLimited(zip);
                } else if (entryName.matches("word/(header|footer)\\d*\\.xml")) {
                    headerFooterXml.add(readLimited(zip));
                } else if (entryName.equals("word/_rels/document.xml.rels")) {
                    relationshipsXml = readLimited(zip);
                }
            }
        }
        if (documentXml == null) {
            throw new IllegalArgumentException("This .docx file could not be read (no document body found).");
        }

        StringBuilder text = new StringBuilder();
        // many resume templates put the name and contact line in the header
        for (byte[] part : headerFooterXml) {
            text.append(wordXmlToText(part)).append('\n');
        }
        text.append(wordXmlToText(documentXml));

        // hyperlink targets live in the relationships file, not in the visible text
        Set<String> links = new LinkedHashSet<>();
        if (relationshipsXml != null) {
            Matcher matcher = RELATIONSHIP_URL.matcher(new String(relationshipsXml, StandardCharsets.UTF_8));
            while (matcher.find()) {
                links.add(matcher.group(1).replace("&amp;", "&"));
            }
        }
        return appendLinks(text.toString(), links);
    }

    private String wordXmlToText(byte[] xml) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // never resolve DTDs or external entities from an uploaded file (XXE protection)
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        StringBuilder text = new StringBuilder();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            boolean insideText = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String element = reader.getLocalName();
                    if ("t".equals(element)) {
                        insideText = true;
                    } else if ("tab".equals(element)) {
                        text.append('\t');
                    } else if ("br".equals(element) || "cr".equals(element)) {
                        text.append('\n');
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && insideText) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String element = reader.getLocalName();
                    if ("t".equals(element)) {
                        insideText = false;
                    } else if ("p".equals(element)) {
                        text.append('\n'); // end of a paragraph
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("This .docx file could not be read (invalid document XML).");
        }
        return text.toString();
    }

    private byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_DOCX_PART_BYTES) {
                throw new IllegalArgumentException("This .docx file is too large to process.");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------
    private static String appendLinks(String text, Set<String> links) {
        if (links.isEmpty()) {
            return text;
        }
        return text + "\n\nLinks in the document:\n" + String.join("\n", links);
    }

    // normalise line endings, drop invisible characters and squash big gaps of blank lines
    static String tidyText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u200B\\uFEFF]", "")
                .replaceAll("[ \\t\\u00A0]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static boolean startsWith(byte[] bytes, String prefix) {
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < prefixBytes.length) {
            return false;
        }
        for (int i = 0; i < prefixBytes.length; i++) {
            if (bytes[i] != prefixBytes[i]) {
                return false;
            }
        }
        return true;
    }
}
