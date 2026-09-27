package com._6.resumeiq.dto;

import java.util.List;

// The graded review Gemini sends back (after ResumeReviewService cleans it up).
// Field names match the JSON keys in the review prompt exactly, so Jackson can map Gemini's JSON straight onto it.
public record ReviewResult(
        Integer overallScore,
        String letterGrade,
        String detectedRole,
        String verdict,
        List<CategoryScore> categoryScores,
        List<String> strengths,
        List<Suggestion> suggestions,
        List<BulletRewrite> bulletRewrites,
        List<String> missingKeywords,
        List<FutureRecommendation> futureRecommendations) {

    // one rubric category, e.g. "Impact & Achievements" -> 72
    public record CategoryScore(String category, Integer score, Integer weight, String feedback) {
    }

    // something to fix now. priority is HIGH, MEDIUM or LOW
    public record Suggestion(String priority, String section, String issue, String recommendation) {
    }

    // a weak bullet copied from the resume and a stronger version of it
    public record BulletRewrite(String section, String original, String improved, String reason) {
    }

    // something to DO in the future (build, learn, join) that would make the resume stronger later
    public record FutureRecommendation(String type, String title, String description, String impact) {
    }
}
