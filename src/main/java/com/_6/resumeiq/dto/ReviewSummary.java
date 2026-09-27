package com._6.resumeiq.dto;

// One row on the history page
public record ReviewSummary(
        Long id,
        String fileName,
        String targetRole,
        String createdAt,
        Integer overallScore,
        String letterGrade) {
}
