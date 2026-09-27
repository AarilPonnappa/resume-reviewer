package com._6.resumeiq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com._6.resumeiq.dto.ExtractedResume;
import com._6.resumeiq.model.ResumeReview;
import com._6.resumeiq.model.User;
import com._6.resumeiq.repositories.ResumeReviewRepository;
import com._6.resumeiq.repositories.UserRepository;

// Starts the whole Spring application (in-memory database, no Gemini calls) and checks
// the wiring, the database mappings and the real Thymeleaf -> PDF pipeline
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:resumeiq-test;DB_CLOSE_DELAY=-1",
        "gemini.api.key="
})
class ApplicationIntegrationTest {

    @Autowired
    private ResumePdfService pdfService;

    @Autowired
    private ResumeTextExtractor textExtractor;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ResumeReviewRepository reviewRepository;

    @Test
    void rendersTheImprovedResumeAsARealPdf() throws IOException {
        byte[] pdf = pdfService.render(SampleResume.honestRewrite());

        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Jordan Lee"), text);
            assertTrue(text.contains("Brightline Analytics"), text);
            assertTrue(text.contains("RabbitMQ"), text);
            assertTrue(text.contains("Languages:"), text);
            assertEquals(1, document.getNumberOfPages(), "a student resume should fit on one page");
        }
    }

    @Test
    void generatedPdfCanBeReadBackByTheExtractor() throws IOException {
        byte[] pdf = pdfService.render(SampleResume.honestRewrite());
        ExtractedResume extracted = textExtractor.extract("improved.pdf", pdf);

        assertTrue(extracted.pdf());
        assertTrue(extracted.text().contains("University of California, Berkeley"), extracted.text());
        // the contact links are clickable in the PDF, so their targets are picked up too
        assertTrue(extracted.text().contains("https://github.com/jordanlee"), extracted.text());
    }

    @Test
    void reviewsAreOnlyVisibleToTheirOwner() {
        User owner = new User();
        owner.setName("Jordan Lee");
        owner.setEmail("owner@example.com");
        owner.setPasswordHash("hash");
        owner.setVerified(true);
        owner = userRepository.save(owner);

        ResumeReview review = new ResumeReview();
        review.setUserId(owner.getId());
        review.setFileName("resume.pdf");
        review.setOverallScore(74);
        review.setLetterGrade("C");
        review.setReviewJson("{}");
        review.setResumeJson("{}");
        review.setIntegrityJson("{}");
        review = reviewRepository.save(review);

        assertTrue(userRepository.findByEmail("owner@example.com").isPresent());
        assertTrue(reviewRepository.findByIdAndUserId(review.getId(), owner.getId()).isPresent());
        assertFalse(reviewRepository.findByIdAndUserId(review.getId(), owner.getId() + 1000).isPresent());
        assertEquals(1, reviewRepository.findByUserIdOrderByCreatedAtDesc(owner.getId()).size());
    }

    @Test
    void pdfTextIsMadeSafeForBuiltInFonts() {
        assertEquals("A -> B", ResumePdfService.safe("A \u2192 B \u2713"));
        assertEquals("Jordan_Lee_Resume.pdf", pdfService.fileNameFor(SampleResume.honestRewrite()));
    }

    @Test
    void uploadedFileNamesAreCleaned() {
        assertEquals("resume.pdf", ResumeAnalysisService.safeFileName("C:\\Users\\me\\Desktop\\resume.pdf"));
        assertEquals("resume", ResumeAnalysisService.safeFileName("  "));
    }
}
