package com._6.resumeiq.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com._6.resumeiq.dto.ReviewResult.CategoryScore;

// The grading rubric lives here, in code, instead of trusting the model's overall number.
// Gemini scores each category; we compute the weighted overall score and letter grade ourselves,
// so the same category scores always produce the same grade.
public final class ScoreCalculator {

    // category -> weight (weights add up to 100)
    public static final Map<String, Integer> WEIGHTS;

    static {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("Impact & Achievements", 25);
        weights.put("Relevance to Target Role", 20);
        weights.put("Clarity & Writing", 20);
        weights.put("Structure & Formatting", 15);
        weights.put("Skills & Technical Depth", 10);
        weights.put("ATS Compatibility", 10);
        WEIGHTS = Collections.unmodifiableMap(weights);
    }

    private ScoreCalculator() {
    }

    public static List<String> categories() {
        return new ArrayList<>(WEIGHTS.keySet());
    }

    // Maps whatever category name the model used onto one of our exact rubric names (or null if it matches none)
    public static String canonicalCategory(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toLowerCase(Locale.ROOT);
        for (String category : WEIGHTS.keySet()) {
            if (category.toLowerCase(Locale.ROOT).equals(name)) {
                return category;
            }
        }
        if (name.contains("impact") || name.contains("achievement") || name.contains("result")) {
            return "Impact & Achievements";
        }
        if (name.contains("relevan") || name.contains("target") || name.contains("role fit")) {
            return "Relevance to Target Role";
        }
        if (name.contains("clarity") || name.contains("writing") || name.contains("grammar") || name.contains("language")) {
            return "Clarity & Writing";
        }
        if (name.contains("structure") || name.contains("format") || name.contains("layout")) {
            return "Structure & Formatting";
        }
        if (name.contains("ats") || name.contains("applicant tracking") || name.contains("keyword")) {
            return "ATS Compatibility";
        }
        if (name.contains("skill") || name.contains("technical")) {
            return "Skills & Technical Depth";
        }
        return null;
    }

    public static int clamp(Integer score) {
        if (score == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, score));
    }

    // Weighted average of the categories that are present (weights are re-normalised if one is missing).
    // Returns null when there is nothing to average.
    public static Integer weightedOverall(List<CategoryScore> categoryScores) {
        if (categoryScores == null || categoryScores.isEmpty()) {
            return null;
        }
        double total = 0;
        int weightSum = 0;
        for (CategoryScore categoryScore : categoryScores) {
            Integer weight = WEIGHTS.get(categoryScore.category());
            if (weight == null || categoryScore.score() == null) {
                continue;
            }
            total += clamp(categoryScore.score()) * weight;
            weightSum += weight;
        }
        if (weightSum == 0) {
            return null;
        }
        return (int) Math.round(total / weightSum);
    }

    public static String letterGrade(int score) {
        if (score >= 93) return "A";
        if (score >= 90) return "A-";
        if (score >= 87) return "B+";
        if (score >= 83) return "B";
        if (score >= 80) return "B-";
        if (score >= 77) return "C+";
        if (score >= 73) return "C";
        if (score >= 70) return "C-";
        if (score >= 67) return "D+";
        if (score >= 63) return "D";
        if (score >= 60) return "D-";
        return "F";
    }
}
