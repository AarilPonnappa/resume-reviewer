package com._6.resumeiq.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

import com._6.resumeiq.dto.AnalysisResponse;
import com._6.resumeiq.dto.ExtractedResume;
import com._6.resumeiq.dto.IntegrityReport;
import com._6.resumeiq.dto.ResumeData;
import com._6.resumeiq.dto.ReviewResult;
import com._6.resumeiq.dto.ReviewSummary;
import com._6.resumeiq.dto.RewriteOutcome;
import com._6.resumeiq.model.ResumeReview;
import com._6.resumeiq.repositories.ResumeReviewRepository;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

// Coordinates one full analysis so the controller stays small:
//   read the file -> review + rewrite (both Gemini calls run at the same time) -> save -> return everything
// Also loads/deletes saved reviews for the history page.
@Service
public class ResumeAnalysisService {

    private final ResumeTextExtractor textExtractor;
    private final ResumeReviewService reviewService;
    private final ResumeRewriteService rewriteService;
    private final ResumeReviewRepository reviewRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    // Java 21 virtual threads: cheap threads that mostly sit waiting on Gemini's HTTP response
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResumeAnalysisService(ResumeTextExtractor textExtractor, ResumeReviewService reviewService,
                                 ResumeRewriteService rewriteService, ResumeReviewRepository reviewRepository) {
        this.textExtractor = textExtractor;
        this.reviewService = reviewService;
        this.rewriteService = rewriteService;
        this.reviewRepository = reviewRepository;
    }

    public AnalysisResponse analyze(Long userId, String apiKey, String fileName, byte[] fileBytes,
                                    String targetRole, String jobDescription) throws IOException {

        ExtractedResume resume = textExtractor.extract(safeFileName(fileName), fileBytes);
        if (!resume.pdf() && resume.text().isBlank()) {
            throw new IllegalArgumentException("No text was found in this file.");
        }

        String role = limit(targetRole, 120);
        String job = limit(jobDescription, 8000);

        // The review and the rewrite don't depend on each other, so run both Gemini calls in parallel
        // (total wait is the slower of the two instead of both added together)
        CompletableFuture<ReviewResult> reviewFuture = CompletableFuture.supplyAsync(
                () -> reviewService.review(apiKey, resume, role, job), executor);
        CompletableFuture<RewriteOutcome> rewriteFuture = CompletableFuture.supplyAsync(
                () -> rewriteService.rewrite(apiKey, resume, role), executor);

        ReviewResult review;
        RewriteOutcome rewrite;
        try {
            review = reviewFuture.join();
            rewrite = rewriteFuture.join();
        } catch (CompletionException e) {
            reviewFuture.cancel(true);
            rewriteFuture.cancel(true);
            // unwrap so the controller sees the real GeminiException / IllegalArgumentException
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }

        ResumeReview entity = new ResumeReview();
        entity.setUserId(userId);
        entity.setFileName(resume.fileName());
        entity.setTargetRole(role);
        entity.setOverallScore(review.overallScore());
        entity.setLetterGrade(review.letterGrade());
        entity.setReviewJson(jsonMapper.writeValueAsString(review));
        entity.setResumeJson(jsonMapper.writeValueAsString(rewrite.resume()));
        entity.setIntegrityJson(jsonMapper.writeValueAsString(rewrite.integrity()));
        ResumeReview saved = reviewRepository.save(entity);

        return new AnalysisResponse(saved.getId(), saved.getFileName(), saved.getTargetRole(),
                saved.getCreatedAt().toString(), review, rewrite.resume(), rewrite.integrity());
    }

    public List<ReviewSummary> history(Long userId) {
        List<ReviewSummary> summaries = new ArrayList<>();
        for (ResumeReview review : reviewRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            summaries.add(new ReviewSummary(review.getId(), review.getFileName(), review.getTargetRole(),
                    review.getCreatedAt().toString(), review.getOverallScore(), review.getLetterGrade()));
        }
        return summaries;
    }

    public Optional<AnalysisResponse> load(Long userId, Long reviewId) {
        return reviewRepository.findByIdAndUserId(reviewId, userId).map(review -> new AnalysisResponse(
                review.getId(),
                review.getFileName(),
                review.getTargetRole(),
                review.getCreatedAt().toString(),
                jsonMapper.readValue(review.getReviewJson(), ReviewResult.class),
                jsonMapper.readValue(review.getResumeJson(), ResumeData.class),
                jsonMapper.readValue(review.getIntegrityJson(), IntegrityReport.class)));
    }

    public Optional<ResumeData> loadResume(Long userId, Long reviewId) {
        return reviewRepository.findByIdAndUserId(reviewId, userId)
                .map(review -> jsonMapper.readValue(review.getResumeJson(), ResumeData.class));
    }

    public boolean delete(Long userId, Long reviewId) {
        Optional<ResumeReview> review = reviewRepository.findByIdAndUserId(reviewId, userId);
        review.ifPresent(reviewRepository::delete);
        return review.isPresent();
    }

    // keep only the file's own name (browsers can send full paths) and make it safe to store/show
    static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "resume";
        }
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            return "resume";
        }
        return name.length() > 150 ? name.substring(name.length() - 150) : name;
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
