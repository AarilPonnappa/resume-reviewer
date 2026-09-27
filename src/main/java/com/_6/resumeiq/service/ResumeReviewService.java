package com._6.resumeiq.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

import com._6.resumeiq.dto.ExtractedResume;
import com._6.resumeiq.dto.ReviewResult;
import com._6.resumeiq.dto.ReviewResult.BulletRewrite;
import com._6.resumeiq.dto.ReviewResult.CategoryScore;
import com._6.resumeiq.dto.ReviewResult.FutureRecommendation;
import com._6.resumeiq.dto.ReviewResult.Suggestion;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

// Gemini call #1: grade the resume and explain how to improve it.
// (Gemini call #2, the rewrite, lives in ResumeRewriteService. The two run at the same time.)
@Service
public class ResumeReviewService {

    private static final Set<String> PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Set<String> RECOMMENDATION_TYPES = Set.of(
            "PROJECT", "SKILL", "CERTIFICATION", "EXPERIENCE", "OPEN_SOURCE", "OTHER");

    private static final String SYSTEM_PROMPT = """
            You are a senior technical recruiter and resume coach. You have screened thousands of resumes for
            software engineering, AI/ML engineering and data internships and new-grad roles at startups and large
            tech companies. You give specific, honest, actionable feedback and you never invent facts about a candidate.
            """;

    private final GeminiService geminiService;
    private final FabricationGuard fabricationGuard;

    // ignore any extra keys Gemini adds so one unexpected field can't break parsing
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // accept "strengths": "one item" as if it were ["one item"]
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .build();

    public ResumeReviewService(GeminiService geminiService, FabricationGuard fabricationGuard) {
        this.geminiService = geminiService;
        this.fabricationGuard = fabricationGuard;
    }

    public ReviewResult review(String apiKey, ExtractedResume resume, String targetRole, String jobDescription) {
        String prompt = buildPrompt(resume, targetRole, jobDescription);

        ReviewResult raw = null;
        // one retry if the answer can't be parsed as JSON
        for (int attempt = 1; attempt <= 2 && raw == null; attempt++) {
            String answer = geminiService.generateJson(apiKey, SYSTEM_PROMPT, prompt, resume.pdf() ? resume.pdfBytes() : null);
            String json = JsonText.extractObject(answer);
            if (json == null) {
                continue;
            }
            try {
                raw = jsonMapper.readValue(json, ReviewResult.class);
            } catch (JacksonException e) {
                raw = null;
            }
        }
        if (raw == null) {
            throw new GeminiException("Gemini's review could not be read as JSON. Please try again.", 502);
        }
        return normalize(raw, resume.text());
    }

    // Instructions given to Gemini, with the resume text appended at the end (same approach as Extractly's prompt)
    String buildPrompt(ExtractedResume resume, String targetRole, String jobDescription) {
        String role = (targetRole == null || targetRole.isBlank())
                ? "Not specified. Infer the most likely target role from the resume and review against that."
                : targetRole;
        String job = (jobDescription == null || jobDescription.isBlank()) ? "Not provided." : jobDescription;

        return """
                Review the candidate's resume below and return ONLY a valid JSON object, no markdown, no explanation, no code fences.

                Ground rules:
                - Base every observation ONLY on what is written in the resume. Never assume experience that isn't written.
                - Refer to real sections, entries and bullets from the resume so the feedback is specific.
                - Scores are integers from 0 to 100. Calibrate honestly: 90+ exceptional and ready for top companies,
                  75-89 strong with minor fixes, 60-74 average with several clear problems, below 60 needs major rework.
                  Most student resumes land between 55 and 80. Do not inflate scores.

                "categoryScores" must contain EXACTLY these 6 categories, spelled exactly like this:
                - "Impact & Achievements": are bullets outcome-driven (what changed because of the work) with concrete results?
                - "Relevance to Target Role": does the content match the target role and job description?
                - "Clarity & Writing": strong action verbs, concise bullets, consistent tense, no filler, no grammar mistakes.
                - "Structure & Formatting": section order, consistency of dates/titles, length (1 page for students), scannability.
                - "Skills & Technical Depth": are technologies shown being USED in experience/projects, not just listed?
                - "ATS Compatibility": standard section headings, parseable layout, relevant keywords present.

                Rules for each field:
                - "detectedRole": the role and level this resume is best suited to right now (e.g. "Software Engineering Intern").
                - "verdict": 2-3 sentences with the overall assessment and the single most important fix.
                - "strengths": 3-6 specific things the resume already does well.
                - "suggestions": 6-12 specific fixes. "priority" is "HIGH" (seriously hurts chances), "MEDIUM" or "LOW".
                  "section" is the resume section it applies to. "issue" says what is wrong, "recommendation" says exactly what to do.
                - "bulletRewrites": 3-8 of the weakest bullets. "original" must be copied VERBATIM from the resume.
                  "improved" rewrites it with a strong action verb, the technical detail and the outcome, using ONLY facts
                  already in that bullet. If a number would make it stronger but the resume doesn't give one, write a
                  placeholder in square brackets such as [X%%] or [N users] for the candidate to replace with a real number.
                  NEVER state an invented number, technology, company or result as fact.
                - "missingKeywords": up to 15 important skills/keywords for the target role (and job description, if given)
                  that do NOT appear anywhere in the resume. The candidate should only add them if they genuinely have them.
                - "futureRecommendations": 4-8 concrete things the candidate should DO next to become a stronger candidate for
                  the target role: projects to build (give a specific idea, its scope and tech stack), skills to learn,
                  certifications, open-source contributions, hackathons, research or work experience to seek.
                  "type" is one of "PROJECT", "SKILL", "CERTIFICATION", "EXPERIENCE", "OPEN_SOURCE", "OTHER".
                  "impact" explains why it would strengthen the resume. These are future actions, not claims about the candidate.

                The JSON object must have EXACTLY this structure and these keys:
                {
                  "detectedRole": string,
                  "verdict": string,
                  "categoryScores": [ { "category": string, "score": integer, "feedback": string } ],
                  "strengths": [ string ],
                  "suggestions": [ { "priority": string, "section": string, "issue": string, "recommendation": string } ],
                  "bulletRewrites": [ { "section": string, "original": string, "improved": string, "reason": string } ],
                  "missingKeywords": [ string ],
                  "futureRecommendations": [ { "type": string, "title": string, "description": string, "impact": string } ]
                }

                Target role: %s

                Job description:
                %s

                %s
                """.formatted(role, job, resumeBlock(resume));
    }

    // For PDFs Gemini also receives the file itself; the extracted text is added so quotes can be copied exactly
    static String resumeBlock(ExtractedResume resume) {
        String text = resume.text() == null ? "" : resume.text();
        if (resume.pdf()) {
            if (text.isBlank()) {
                return "The resume is the attached PDF (it has no selectable text, so read it visually).";
            }
            return "The resume is the attached PDF. Its extracted text is below for copying exact wording:\n<<<RESUME\n"
                    + text + "\nRESUME>>>";
        }
        return "Resume text:\n<<<RESUME\n" + text + "\nRESUME>>>";
    }

    // ---------------------------------------------------------------------------------------------
    // Clean up and double-check whatever Gemini returned
    // ---------------------------------------------------------------------------------------------
    ReviewResult normalize(ReviewResult raw, String sourceText) {
        boolean canVerify = fabricationGuard.canVerify(sourceText);

        // keep one score per rubric category, in rubric order, with our weights attached
        List<CategoryScore> categories = new ArrayList<>();
        for (String category : ScoreCalculator.categories()) {
            if (raw.categoryScores() == null) {
                break;
            }
            for (CategoryScore score : raw.categoryScores()) {
                if (score != null && category.equals(ScoreCalculator.canonicalCategory(score.category()))) {
                    categories.add(new CategoryScore(category, ScoreCalculator.clamp(score.score()),
                            ScoreCalculator.WEIGHTS.get(category), text(score.feedback())));
                    break;
                }
            }
        }

        // the overall score and letter grade are calculated here, not taken from the model
        Integer weighted = ScoreCalculator.weightedOverall(categories);
        int overall = weighted != null ? weighted : ScoreCalculator.clamp(raw.overallScore());
        String grade = ScoreCalculator.letterGrade(overall);

        List<String> strengths = nonBlank(raw.strengths(), 8);

        List<Suggestion> suggestions = new ArrayList<>();
        if (raw.suggestions() != null) {
            for (Suggestion suggestion : raw.suggestions()) {
                if (suggestion == null || isBlank(suggestion.issue()) && isBlank(suggestion.recommendation())) {
                    continue;
                }
                String priority = suggestion.priority() == null ? "MEDIUM" : suggestion.priority().trim().toUpperCase(Locale.ROOT);
                if (!PRIORITIES.contains(priority)) {
                    priority = "MEDIUM";
                }
                suggestions.add(new Suggestion(priority, text(suggestion.section()), text(suggestion.issue()),
                        text(suggestion.recommendation())));
            }
        }
        // HIGH first, then MEDIUM, then LOW
        suggestions.sort(Comparator.comparingInt(s -> List.of("HIGH", "MEDIUM", "LOW").indexOf(s.priority())));
        if (suggestions.size() > 15) {
            suggestions = new ArrayList<>(suggestions.subList(0, 15));
        }

        // A rewrite is only kept if its "original" really is in the resume and the improved version doesn't
        // state any number that isn't in the resume (bracketed placeholders like [X%] are fine)
        List<BulletRewrite> rewrites = new ArrayList<>();
        if (raw.bulletRewrites() != null) {
            for (BulletRewrite rewrite : raw.bulletRewrites()) {
                if (rewrite == null || isBlank(rewrite.original()) || isBlank(rewrite.improved())) {
                    continue;
                }
                if (canVerify && (!fabricationGuard.isQuotedFromSource(rewrite.original(), sourceText)
                        || !fabricationGuard.unsupportedNumbers(rewrite.improved(), sourceText, true).isEmpty())) {
                    continue;
                }
                rewrites.add(new BulletRewrite(text(rewrite.section()), rewrite.original().trim(),
                        rewrite.improved().trim(), text(rewrite.reason())));
                if (rewrites.size() == 10) {
                    break;
                }
            }
        }

        // "missing" keywords that are actually already in the resume are dropped
        Set<String> seen = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (String keyword : nonBlank(raw.missingKeywords(), 40)) {
            String key = keyword.toLowerCase(Locale.ROOT);
            if (seen.add(key) && !(canVerify && fabricationGuard.appearsInSource(keyword, sourceText))) {
                missing.add(keyword);
            }
            if (missing.size() == 15) {
                break;
            }
        }

        List<FutureRecommendation> future = new ArrayList<>();
        if (raw.futureRecommendations() != null) {
            for (FutureRecommendation recommendation : raw.futureRecommendations()) {
                if (recommendation == null || isBlank(recommendation.title())) {
                    continue;
                }
                String type = recommendation.type() == null ? "OTHER"
                        : recommendation.type().trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
                if (!RECOMMENDATION_TYPES.contains(type)) {
                    type = "OTHER";
                }
                future.add(new FutureRecommendation(type, recommendation.title().trim(),
                        text(recommendation.description()), text(recommendation.impact())));
                if (future.size() == 10) {
                    break;
                }
            }
        }

        return new ReviewResult(overall, grade, text(raw.detectedRole()), text(raw.verdict()), categories,
                strengths, suggestions, rewrites, missing, future);
    }

    private static List<String> nonBlank(List<String> items, int limit) {
        List<String> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (String item : items) {
            if (!isBlank(item)) {
                result.add(item.trim());
            }
            if (result.size() == limit) {
                break;
            }
        }
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
