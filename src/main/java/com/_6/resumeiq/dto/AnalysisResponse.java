package com._6.resumeiq.dto;

// Everything the review page needs, sent as one JSON object (from POST /analyze and GET /reviews/{id})
public record AnalysisResponse(
        Long id,
        String fileName,
        String targetRole,
        String createdAt,
        ReviewResult review,
        ResumeData resume,
        IntegrityReport integrity) {
}
