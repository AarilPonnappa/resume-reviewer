package com._6.resumeiq.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com._6.resumeiq.dto.ExtractedResume;
import com._6.resumeiq.dto.IntegrityReport;
import com._6.resumeiq.dto.ResumeData;
import com._6.resumeiq.dto.RewriteOutcome;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

// Gemini call #2: rebuild the resume from the user's OWN content (reordered, tightened, better bullets),
// then verify it with FabricationGuard:
//   1. check Gemini's draft against the original text
//   2. if anything is unsupported, send the draft back once with the exact problems listed and ask for a fix
//   3. remove anything that is STILL unsupported
// The user sees what happened in the integrity report.
@Service
public class ResumeRewriteService {

    private static final String SYSTEM_PROMPT = """
            You are an expert resume writer for software engineering and AI/ML candidates.
            You restructure and rewrite resumes to present the candidate's real experience as strongly as possible.
            You are strictly forbidden from inventing, inferring or exaggerating anything: every fact you write must
            already be present in the candidate's resume.
            """;

    private final GeminiService geminiService;
    private final FabricationGuard fabricationGuard;

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // accept "strengths": "one item" as if it were ["one item"]
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .build();

    public ResumeRewriteService(GeminiService geminiService, FabricationGuard fabricationGuard) {
        this.geminiService = geminiService;
        this.fabricationGuard = fabricationGuard;
    }

    // The job description is deliberately NOT given to the rewrite: its keywords would tempt the model to add
    // skills the candidate never listed. It only affects the review's advice.
    public RewriteOutcome rewrite(String apiKey, ExtractedResume resume, String targetRole) {
        String prompt = buildPrompt(resume, targetRole);
        ResumeData draft = fabricationGuard.tidy(callAndParse(apiKey, prompt, resume));

        String source = resume.text();
        List<String> warnings = new ArrayList<>();

        // Scanned/image PDFs have no text layer, so there is nothing to compare against
        if (!fabricationGuard.canVerify(source)) {
            warnings.add("Your file has little or no selectable text (for example a scanned image), so the rewrite could "
                    + "not be checked automatically. Read it carefully before using it.");
            return new RewriteOutcome(draft, new IntegrityReport(false,
                    "Not automatically verified: no readable text in the original file.", 0, false, List.of(), warnings));
        }

        List<String> firstPassIssues = fabricationGuard.findViolations(draft, source);
        boolean retried = false;
        if (!firstPassIssues.isEmpty()) {
            retried = true;
            try {
                ResumeData corrected = callAndParse(apiKey, buildCorrectionPrompt(prompt, draft, firstPassIssues), resume);
                draft = fabricationGuard.tidy(corrected);
            } catch (GeminiException e) {
                warnings.add("The automatic correction request failed, so unsupported items were removed instead.");
            }
        }

        List<String> removed = new ArrayList<>();
        ResumeData verified = fabricationGuard.sanitize(draft, source, removed);

        String note;
        if (firstPassIssues.isEmpty()) {
            note = "Every name, date, number, skill and link in the rewritten resume was matched against your original.";
        } else if (removed.isEmpty()) {
            note = "Gemini's first draft included " + firstPassIssues.size() + " item(s) that weren't in your resume. "
                    + "They were sent back and corrected, and the final version matches your original.";
        } else {
            note = removed.size() + " item(s) could not be traced back to your original resume and were removed.";
        }

        return new RewriteOutcome(verified, new IntegrityReport(true, note, firstPassIssues.size(), retried, removed, warnings));
    }

    private ResumeData callAndParse(String apiKey, String prompt, ExtractedResume resume) {
        // one retry if the answer can't be parsed as JSON
        for (int attempt = 1; attempt <= 2; attempt++) {
            String answer = geminiService.generateJson(apiKey, SYSTEM_PROMPT, prompt, resume.pdf() ? resume.pdfBytes() : null);
            String json = JsonText.extractObject(answer);
            if (json == null) {
                continue;
            }
            try {
                return jsonMapper.readValue(json, ResumeData.class);
            } catch (JacksonException e) {
                // try again
            }
        }
        throw new GeminiException("Gemini's rewritten resume could not be read as JSON. Please try again.", 502);
    }

    String buildPrompt(ExtractedResume resume, String targetRole) {
        String role = (targetRole == null || targetRole.isBlank())
                ? "Not specified. Use the role the resume is clearly aimed at."
                : targetRole;

        return """
                Rewrite and restructure the candidate's resume below into the strongest honest version of itself.
                Return ONLY a valid JSON object, no markdown, no explanation, no code fences.

                YOU MAY:
                - Reorder sections for the target role (for students: Education first, then the strongest of
                  Experience / Projects, then Skills; drop an Objective section unless it adds real information).
                - Reorder entries and put each entry's strongest bullets first.
                - Rewrite bullets: strong past-tense action verb (present tense for current roles), what was built or done,
                  how (only technologies named in the original), and the result ONLY if the original states it.
                - Tighten wording, fix grammar, spelling and inconsistent tense or capitalisation.
                - Merge duplicate content, drop weak, redundant or irrelevant bullets (e.g. "References available").
                - Group the skills that are already listed into clear labelled categories (e.g. Languages, Frameworks, Tools).
                - Standardise date formats (e.g. "Jun 2024 - Aug 2024") WITHOUT changing any date.

                YOU MUST NEVER:
                - Invent or infer any company, school, degree, job title, project, award, certification, date, number,
                  percentage, metric, user count, technology, tool, language, link or contact detail that is not written
                  in the original resume. Copy names exactly as written (do not expand abbreviations).
                - Add metrics to bullets that don't have them. Add placeholders like [X] or "TBD".
                - Add skills from the target role that the resume doesn't mention, upgrade job titles, or claim
                  leadership, ownership or results that are not stated.

                Length: aim for one page. At most 4 bullets per job and 3 per project, each bullet under 30 words.

                Field rules:
                - "name": exactly as written. "headline": a title line only if the original has one, otherwise null.
                - "contact": copy email, phone and location exactly. "links": every URL or profile in the resume
                  (GitHub, LinkedIn, portfolio...) with a short "label"; "url" exactly as written.
                - "summary": only if the original has a summary/objective with real content; rewrite it in 1-2 sentences
                  using only its facts. Otherwise null.
                - "sections": in the new order. "type" is "entries" for Education/Experience/Projects/Leadership/etc.
                  (fill "entries", leave "skillGroups" empty) or "skills" for skill lists (fill "skillGroups", leave
                  "entries" empty). For an entry, "heading" is the organisation, school or project name,
                  "subheading" is the role, degree or tech stack, "dates" and "location" as written (null if absent).
                - "restructuringNotes": 3-8 short sentences explaining the main changes you made and why.

                The JSON object must have EXACTLY this structure and these keys:
                {
                  "name": string,
                  "headline": string or null,
                  "contact": { "email": string or null, "phone": string or null, "location": string or null,
                               "links": [ { "label": string, "url": string } ] },
                  "summary": string or null,
                  "sections": [
                    { "title": string, "type": "entries" or "skills",
                      "entries": [ { "heading": string, "subheading": string or null, "location": string or null,
                                     "dates": string or null, "bullets": [ string ] } ],
                      "skillGroups": [ { "label": string, "items": [ string ] } ] }
                  ],
                  "restructuringNotes": [ string ]
                }

                Target role: %s

                %s
                """.formatted(role, ResumeReviewService.resumeBlock(resume));
    }

    // Sends Gemini its own draft back with the exact problems FabricationGuard found
    String buildCorrectionPrompt(String originalPrompt, ResumeData draft, List<String> issues) {
        StringBuilder prompt = new StringBuilder(originalPrompt);
        prompt.append("\n\nYour previous answer was:\n")
                .append(jsonMapper.writeValueAsString(draft))
                .append("\n\nAn automatic check found content in it that does NOT appear in the original resume:\n");
        for (String issue : issues) {
            prompt.append("- ").append(issue).append('\n');
        }
        prompt.append("\nReturn the complete corrected JSON object. Remove or reword every item listed above so that ")
                .append("each name, number, technology, skill and link is copied from the original resume exactly as written. ")
                .append("Keep everything else that was correct.");
        return prompt.toString();
    }
}
