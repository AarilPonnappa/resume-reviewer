package com._6.resumeiq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com._6.resumeiq.dto.ReviewResult;

class ResumeReviewServiceTest {

    // what Gemini might realistically send back, including a few things our clean-up has to fix
    private static final String GEMINI_REVIEW = """
            ```json
            {
              "detectedRole": "Software Engineering Intern",
              "verdict": "Solid technical base, but most bullets describe tasks instead of results.",
              "overallScore": 99,
              "categoryScores": [
                { "category": "impact & achievements", "score": 58, "feedback": "Few outcomes." },
                { "category": "Relevance to Target Role", "score": 78, "feedback": "Good match." },
                { "category": "Clarity & Writing", "score": 66, "feedback": "Weak verbs." },
                { "category": "Formatting", "score": 80, "feedback": "Clean." },
                { "category": "Skills & Technical Depth", "score": 72, "feedback": "Tools are used in projects." },
                { "category": "ATS Compatibility", "score": 150, "feedback": "Standard headings." }
              ],
              "strengths": ["Relevant internship", "  ", "Real projects"],
              "suggestions": [
                { "priority": "low", "section": "Skills", "issue": "Long list", "recommendation": "Group skills." },
                { "priority": "HIGH", "section": "Experience", "issue": "No results", "recommendation": "Add outcomes." },
                { "priority": "urgent", "section": "Projects", "issue": "Vague", "recommendation": "Name the stack." }
              ],
              "bulletRewrites": [
                { "section": "Experience", "original": "worked on backend services in Java and Spring Boot",
                  "improved": "Developed Java and Spring Boot backend services handling [N] requests per day", "reason": "Stronger verb." },
                { "section": "Experience", "original": "Graded assignments",
                  "improved": "Graded 300 assignments per week", "reason": "Invented number, must be dropped." },
                { "section": "Experience", "original": "Managed a team of five engineers at Google",
                  "improved": "Led five engineers", "reason": "Not in the resume, must be dropped." }
              ],
              "missingKeywords": ["Kubernetes", "Docker", "CI/CD", "kubernetes"],
              "futureRecommendations": [
                { "type": "project", "title": "RAG study assistant", "description": "Build it.", "impact": "Shows AI engineering." },
                { "type": "open source", "title": "Contribute to Spring", "description": "Fix a bug.", "impact": "Real code review." },
                { "type": "whatever", "title": "Hackathon", "description": "Join one.", "impact": "Teamwork." }
              ]
            }
            ```
            """;

    @Test
    void reviewIsParsedScoredAndCleaned() {
        FakeGeminiService gemini = new FakeGeminiService(GEMINI_REVIEW);
        ResumeReviewService service = new ResumeReviewService(gemini, new FabricationGuard());

        ReviewResult review = service.review("key", SampleResume.extracted(), "Software Engineering Intern", null);

        // our weighted score, not the model's 99: (58*25 + 78*20 + 66*20 + 80*15 + 72*10 + 100*10) / 100 = 72.5 -> 73
        assertEquals(73, review.overallScore());
        assertEquals("C", review.letterGrade());
        assertEquals(6, review.categoryScores().size());
        assertEquals("Impact & Achievements", review.categoryScores().get(0).category());
        assertEquals(25, review.categoryScores().get(0).weight());
        assertEquals(100, review.categoryScores().get(5).score(), "150 is clamped to 100");

        assertEquals(List.of("Relevant internship", "Real projects"), review.strengths());

        // HIGH first; unknown priority becomes MEDIUM; LOW last
        assertEquals(List.of("HIGH", "MEDIUM", "LOW"),
                review.suggestions().stream().map(ReviewResult.Suggestion::priority).toList());

        // only the honest rewrite survives
        assertEquals(1, review.bulletRewrites().size());
        assertTrue(review.bulletRewrites().get(0).improved().contains("[N]"));

        // Docker is already on the resume; duplicates removed
        assertEquals(List.of("Kubernetes", "CI/CD"), review.missingKeywords());

        assertEquals(List.of("PROJECT", "OPEN_SOURCE", "OTHER"),
                review.futureRecommendations().stream().map(ReviewResult.FutureRecommendation::type).toList());
    }

    @Test
    void promptContainsTheResumeRoleAndJobDescription() {
        FakeGeminiService gemini = new FakeGeminiService(GEMINI_REVIEW);
        new ResumeReviewService(gemini, new FabricationGuard())
                .review("key", SampleResume.extracted(), "AI Engineer Intern", "Must know PyTorch");

        String prompt = gemini.prompts.get(0);
        assertTrue(prompt.contains("Target role: AI Engineer Intern"));
        assertTrue(prompt.contains("Must know PyTorch"));
        assertTrue(prompt.contains("Brightline Analytics"));
        assertTrue(prompt.contains("[X%]"), "the %% escape in the prompt template must print a single %");
    }

    @Test
    void invalidJsonIsRetriedOnceThenFails() {
        FakeGeminiService retryThenOk = new FakeGeminiService("not json at all", GEMINI_REVIEW);
        ReviewResult review = new ResumeReviewService(retryThenOk, new FabricationGuard())
                .review("key", SampleResume.extracted(), null, null);
        assertEquals(73, review.overallScore());
        assertEquals(2, retryThenOk.prompts.size());

        FakeGeminiService alwaysBad = new FakeGeminiService("nope", "{ broken");
        assertThrows(GeminiException.class, () -> new ResumeReviewService(alwaysBad, new FabricationGuard())
                .review("key", SampleResume.extracted(), null, null));
    }

    @Test
    void aSingleStringWhereAListIsExpectedIsAccepted() {
        String answer = """
                { "verdict": "Fine.", "strengths": "Clear formatting",
                  "categoryScores": [ { "category": "Clarity & Writing", "score": "81", "feedback": "Good." } ] }
                """;
        ReviewResult review = new ResumeReviewService(new FakeGeminiService(answer), new FabricationGuard())
                .review("key", SampleResume.extracted(), null, null);
        assertEquals(List.of("Clear formatting"), review.strengths());
        assertEquals(1, review.categoryScores().size());
        assertTrue(review.suggestions().isEmpty());
        assertTrue(review.futureRecommendations().isEmpty());
    }
}
