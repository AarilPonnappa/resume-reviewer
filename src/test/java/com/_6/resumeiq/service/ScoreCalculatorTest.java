package com._6.resumeiq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com._6.resumeiq.dto.ReviewResult.CategoryScore;

class ScoreCalculatorTest {

    @Test
    void weightsAddUpTo100() {
        assertEquals(100, ScoreCalculator.WEIGHTS.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void overallIsTheWeightedAverage() {
        List<CategoryScore> scores = List.of(
                new CategoryScore("Impact & Achievements", 60, null, ""),      // 25
                new CategoryScore("Relevance to Target Role", 80, null, ""),   // 20
                new CategoryScore("Clarity & Writing", 70, null, ""),          // 20
                new CategoryScore("Structure & Formatting", 90, null, ""),     // 15
                new CategoryScore("Skills & Technical Depth", 75, null, ""),   // 10
                new CategoryScore("ATS Compatibility", 85, null, ""));         // 10
        // (60*25 + 80*20 + 70*20 + 90*15 + 75*10 + 85*10) / 100 = 74.5 -> 75
        assertEquals(75, ScoreCalculator.weightedOverall(scores));
    }

    @Test
    void missingCategoriesAreLeftOutOfTheAverage() {
        List<CategoryScore> scores = List.of(
                new CategoryScore("Impact & Achievements", 50, null, ""),
                new CategoryScore("Clarity & Writing", 100, null, ""));
        // (50*25 + 100*20) / 45 = 72.2 -> 72
        assertEquals(72, ScoreCalculator.weightedOverall(scores));
        assertNull(ScoreCalculator.weightedOverall(List.of()));
    }

    @Test
    void scoresAreClamped() {
        assertEquals(100, ScoreCalculator.clamp(140));
        assertEquals(0, ScoreCalculator.clamp(-5));
        assertEquals(0, ScoreCalculator.clamp(null));
    }

    @Test
    void letterGradeBoundaries() {
        assertEquals("A", ScoreCalculator.letterGrade(93));
        assertEquals("A-", ScoreCalculator.letterGrade(92));
        assertEquals("B+", ScoreCalculator.letterGrade(87));
        assertEquals("B", ScoreCalculator.letterGrade(83));
        assertEquals("C", ScoreCalculator.letterGrade(75));
        assertEquals("D-", ScoreCalculator.letterGrade(60));
        assertEquals("F", ScoreCalculator.letterGrade(59));
    }

    @Test
    void categoryNamesAreMatchedLoosely() {
        assertEquals("Impact & Achievements", ScoreCalculator.canonicalCategory("impact & achievements"));
        assertEquals("ATS Compatibility", ScoreCalculator.canonicalCategory("ATS compatibility / keywords"));
        assertEquals("Structure & Formatting", ScoreCalculator.canonicalCategory("Formatting"));
        assertEquals("Skills & Technical Depth", ScoreCalculator.canonicalCategory("Technical depth"));
        assertNull(ScoreCalculator.canonicalCategory("Vibes"));
    }
}
