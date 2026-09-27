package com._6.resumeiq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com._6.resumeiq.dto.ExtractedResume;
import com._6.resumeiq.dto.ResumeData;
import com._6.resumeiq.dto.RewriteOutcome;

class ResumeRewriteServiceTest {

    // a first draft with three fabrications: an invented metric, an invented project and an invented skill
    private static final String DRAFT_WITH_FABRICATIONS = """
            {
              "name": "Jordan Lee",
              "headline": null,
              "contact": { "email": "jordan.lee@berkeley.edu", "phone": "(510) 555-0147", "location": "Berkeley, CA",
                           "links": [ { "label": "GitHub", "url": "github.com/jordanlee" } ] },
              "summary": null,
              "sections": [
                { "title": "Experience", "type": "entries", "skillGroups": [],
                  "entries": [ { "heading": "Brightline Analytics", "subheading": "Software Engineering Intern",
                                 "location": "San Francisco, CA", "dates": "Jun 2025 - Aug 2025",
                                 "bullets": [ "Cut report delays from 30 minutes to 5 minutes by moving reports to a RabbitMQ queue",
                                              "Developed Java and Spring Boot services used by 10,000 customers" ] } ] },
                { "title": "Projects", "type": "entries", "skillGroups": [],
                  "entries": [ { "heading": "Extractly", "subheading": "Java, Spring Boot, Gemini API", "location": null,
                                 "dates": null, "bullets": [ "Built a web app that turns meeting recordings into structured product data" ] },
                               { "heading": "Distributed Cache", "subheading": "Go", "location": null, "dates": null,
                                 "bullets": [ "Built a distributed cache" ] } ] },
                { "title": "Skills", "type": "skills", "entries": [],
                  "skillGroups": [ { "label": "Languages", "items": [ "Java", "Python", "Go" ] } ] }
              ],
              "restructuringNotes": [ "Led with the internship." ]
            }
            """;

    // the same draft with the three problems fixed
    private static final String CORRECTED_DRAFT = """
            {
              "name": "Jordan Lee",
              "contact": { "email": "jordan.lee@berkeley.edu", "phone": "(510) 555-0147", "location": "Berkeley, CA",
                           "links": [ { "label": "GitHub", "url": "github.com/jordanlee" } ] },
              "sections": [
                { "title": "Experience", "type": "entries",
                  "entries": [ { "heading": "Brightline Analytics", "subheading": "Software Engineering Intern",
                                 "location": "San Francisco, CA", "dates": "Jun 2025 - Aug 2025",
                                 "bullets": [ "Cut report delays from 30 minutes to 5 minutes by moving reports to a RabbitMQ queue",
                                              "Developed backend services in Java and Spring Boot" ] } ] },
                { "title": "Projects", "type": "entries",
                  "entries": [ { "heading": "Extractly", "subheading": "Java, Spring Boot, Gemini API",
                                 "bullets": [ "Built a web app that turns meeting recordings into structured product data" ] } ] },
                { "title": "Skills", "type": "skills",
                  "skillGroups": [ { "label": "Languages", "items": [ "Java", "Python" ] } ] }
              ],
              "restructuringNotes": [ "Led with the internship." ]
            }
            """;

    @Test
    void fabricationsAreSentBackAndFixed() {
        FakeGeminiService gemini = new FakeGeminiService(DRAFT_WITH_FABRICATIONS, CORRECTED_DRAFT);
        RewriteOutcome outcome = new ResumeRewriteService(gemini, new FabricationGuard())
                .rewrite("key", SampleResume.extracted(), "Software Engineering Intern");

        assertEquals(2, gemini.prompts.size(), "one rewrite + one correction request");
        String correction = gemini.prompts.get(1);
        assertTrue(correction.contains("10,000"), correction);
        assertTrue(correction.contains("Distributed Cache"), correction);
        assertTrue(correction.contains("\"Go\""), correction);

        assertTrue(outcome.integrity().checked());
        assertTrue(outcome.integrity().retried());
        assertEquals(3, outcome.integrity().issuesFoundFirstPass());
        assertTrue(outcome.integrity().removedItems().isEmpty());
        assertEquals(3, outcome.resume().sections().size());
    }

    @Test
    void anythingStillUnsupportedAfterTheRetryIsRemoved() {
        FakeGeminiService gemini = new FakeGeminiService(DRAFT_WITH_FABRICATIONS, DRAFT_WITH_FABRICATIONS);
        RewriteOutcome outcome = new ResumeRewriteService(gemini, new FabricationGuard())
                .rewrite("key", SampleResume.extracted(), null);

        assertEquals(3, outcome.integrity().removedItems().size());
        ResumeData resume = outcome.resume();
        assertEquals(1, resume.sections().get(0).entries().get(0).bullets().size());
        assertEquals(1, resume.sections().get(1).entries().size());
        assertEquals(List.of("Java", "Python"), resume.sections().get(2).skillGroups().get(0).items());
    }

    @Test
    void honestDraftNeedsNoRetry() {
        FakeGeminiService gemini = new FakeGeminiService(CORRECTED_DRAFT);
        RewriteOutcome outcome = new ResumeRewriteService(gemini, new FabricationGuard())
                .rewrite("key", SampleResume.extracted(), null);

        assertEquals(1, gemini.prompts.size());
        assertFalse(outcome.integrity().retried());
        assertEquals(0, outcome.integrity().issuesFoundFirstPass());
    }

    @Test
    void scannedPdfIsNotVerifiedButStillReturned() {
        FakeGeminiService gemini = new FakeGeminiService(CORRECTED_DRAFT);
        ExtractedResume scanned = new ExtractedResume("scan.pdf", true, new byte[] { '%', 'P', 'D', 'F' }, "");
        RewriteOutcome outcome = new ResumeRewriteService(gemini, new FabricationGuard()).rewrite("key", scanned, null);

        assertFalse(outcome.integrity().checked());
        assertFalse(outcome.integrity().warnings().isEmpty());
        assertEquals("Jordan Lee", outcome.resume().name());
    }
}
