package com._6.resumeiq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com._6.resumeiq.dto.ResumeData;
import com._6.resumeiq.dto.ResumeData.Contact;
import com._6.resumeiq.dto.ResumeData.Entry;
import com._6.resumeiq.dto.ResumeData.Link;
import com._6.resumeiq.dto.ResumeData.Section;
import com._6.resumeiq.dto.ResumeData.SkillGroup;

class FabricationGuardTest {

    private final FabricationGuard guard = new FabricationGuard();

    @Test
    void honestRewriteHasNoViolations() {
        List<String> issues = guard.findViolations(SampleResume.honestRewrite(), SampleResume.TEXT);
        assertEquals(List.of(), issues);
    }

    @Test
    void honestRewriteSurvivesSanitizeUnchanged() {
        List<String> removed = new ArrayList<>();
        ResumeData clean = guard.sanitize(SampleResume.honestRewrite(), SampleResume.TEXT, removed);
        assertTrue(removed.isEmpty(), "nothing should be removed: " + removed);
        assertEquals(4, clean.sections().size());
        assertEquals(3, clean.sections().get(1).entries().get(0).bullets().size());
        assertEquals(2, clean.contact().links().size());
    }

    @Test
    void inventedMetricIsFlaggedAndRemoved() {
        ResumeData resume = withExperienceBullets(List.of(
                "Developed backend services in Java and Spring Boot, reducing latency by 45%",
                "Wrote JUnit unit tests for the REST API"));

        List<String> issues = guard.findViolations(resume, SampleResume.TEXT);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("\"45\""), issues.get(0));

        List<String> removed = new ArrayList<>();
        ResumeData clean = guard.sanitize(resume, SampleResume.TEXT, removed);
        List<String> bullets = clean.sections().get(0).entries().get(0).bullets();
        assertEquals(List.of("Wrote JUnit unit tests for the REST API"), bullets);
        assertEquals(1, removed.size());
    }

    @Test
    void inventedTechnologyInBulletIsFlagged() {
        ResumeData resume = withExperienceBullets(List.of(
                "Built backend services in Java, Spring Boot and Kafka"));
        List<String> issues = guard.findViolations(resume, SampleResume.TEXT);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("Kafka"), issues.get(0));
    }

    @Test
    void sentenceStartingVerbsAreNotTreatedAsClaims() {
        ResumeData resume = withExperienceBullets(List.of(
                "Migrated reports to RabbitMQ. Reduced report delays from 30 minutes to 5 minutes"));
        assertEquals(List.of(), guard.findViolations(resume, SampleResume.TEXT));
    }

    @Test
    void inventedProjectIsRemovedEntirely() {
        ResumeData resume = new ResumeData("Jordan Lee", null, new Contact(null, null, null, List.of()), null,
                List.of(new Section("Projects", "entries", List.of(
                        new Entry("Extractly", null, null, null, List.of("Built a web app with the Gemini API")),
                        new Entry("Kubernetes Autoscaler", "Go, Kubernetes", null, "2024",
                                List.of("Designed an autoscaler"))), List.of())),
                List.of());

        List<String> removed = new ArrayList<>();
        ResumeData clean = guard.sanitize(resume, SampleResume.TEXT, removed);
        List<Entry> entries = clean.sections().get(0).entries();
        assertEquals(1, entries.size());
        assertEquals("Extractly", entries.get(0).heading());
        assertTrue(removed.get(0).contains("Kubernetes Autoscaler"), removed.toString());
    }

    @Test
    void unlistedSkillsAreRemoved() {
        ResumeData resume = new ResumeData("Jordan Lee", null, new Contact(null, null, null, List.of()), null,
                List.of(new Section("Skills", "skills", List.of(), List.of(
                        new SkillGroup("Languages", List.of("Java", "Rust", "Python")),
                        new SkillGroup("Cloud", List.of("AWS", "Kubernetes"))))),
                List.of());

        List<String> removed = new ArrayList<>();
        ResumeData clean = guard.sanitize(resume, SampleResume.TEXT, removed);
        List<SkillGroup> groups = clean.sections().get(0).skillGroups();
        assertEquals(1, groups.size(), "the Cloud group is empty after removals and should disappear");
        assertEquals(List.of("Java", "Python"), groups.get(0).items());
        assertEquals(3, removed.size());
    }

    @Test
    void fakeContactDetailsAreRemoved() {
        ResumeData resume = new ResumeData("Jordan Lee", null,
                new Contact("jordan@gmail.com", "+1 (510) 555-0147", "Berkeley, CA",
                        List.of(new Link("GitHub", "github.com/jordanlee"),
                                new Link("Portfolio", "https://jordanlee.dev"))),
                null, List.of(), List.of());

        List<String> removed = new ArrayList<>();
        ResumeData clean = guard.sanitize(resume, SampleResume.TEXT, removed);
        assertNull(clean.contact().email(), "email is not the one on the resume");
        assertNotNull(clean.contact().phone(), "adding the +1 country code is fine");
        assertEquals(1, clean.contact().links().size());
        assertEquals(2, removed.size());
    }

    @Test
    void inventedDatesAreBlanked() {
        ResumeData resume = new ResumeData("Jordan Lee", null, new Contact(null, null, null, List.of()), null,
                List.of(new Section("Experience", "entries", List.of(
                        new Entry("Brightline Analytics", "Software Engineering Intern", null, "Jan 2023 - Aug 2025",
                                List.of())), List.of())),
                List.of());
        List<String> removed = new ArrayList<>();
        ResumeData clean = guard.sanitize(resume, SampleResume.TEXT, removed);
        assertNull(clean.sections().get(0).entries().get(0).dates());
        assertEquals(1, removed.size());
    }

    @Test
    void quotedBulletsAreRecognised() {
        assertTrue(guard.isQuotedFromSource("worked on backend services in Java and Spring Boot", SampleResume.TEXT));
        assertTrue(guard.isQuotedFromSource("Worked on backend services in Java and Spring Boot.", SampleResume.TEXT));
        assertFalse(guard.isQuotedFromSource("Led a team of engineers building a mobile app", SampleResume.TEXT));
    }

    @Test
    void bracketedPlaceholdersAreAllowedInSuggestions() {
        assertTrue(guard.unsupportedNumbers("Cut delays by [X%] across [N] reports", SampleResume.TEXT, true).isEmpty());
        assertFalse(guard.unsupportedNumbers("Cut delays by 83%", SampleResume.TEXT, true).isEmpty());
        assertTrue(guard.unsupportedNumbers("GPA 3.720 in 2027", SampleResume.TEXT, false).isEmpty(),
                "3.720 is the same number as 3.72");
    }

    @Test
    void shortOrMissingTextCannotBeVerified() {
        assertFalse(guard.canVerify(""));
        assertFalse(guard.canVerify(null));
        assertFalse(guard.canVerify("Jordan Lee resume"));
        assertTrue(guard.canVerify(SampleResume.TEXT));
    }

    @Test
    void tidyHandlesMissingPieces() {
        ResumeData messy = new ResumeData("  Jordan   Lee ", "null", null, "", null, null);
        ResumeData tidy = guard.tidy(messy);
        assertEquals("Jordan Lee", tidy.name());
        assertNull(tidy.headline());
        assertNull(tidy.summary());
        assertNotNull(tidy.contact());
        assertTrue(tidy.sections().isEmpty());
        assertTrue(tidy.restructuringNotes().isEmpty());
    }

    @Test
    void tidyStripsBulletSymbolsAndFixesSectionType() {
        ResumeData resume = new ResumeData("Jordan Lee", null, null, null,
                List.of(new Section("Skills", "entries", List.of(), List.of(new SkillGroup(null, List.of("Java", " ")))),
                        new Section("Experience", null,
                                List.of(new Entry("Brightline Analytics", null, null, null, List.of("• Wrote tests", "  "))),
                                null)),
                null);
        ResumeData tidy = guard.tidy(resume);
        assertEquals("skills", tidy.sections().get(0).type());
        assertEquals("Skills", tidy.sections().get(0).skillGroups().get(0).label());
        assertEquals(List.of("Java"), tidy.sections().get(0).skillGroups().get(0).items());
        assertEquals("entries", tidy.sections().get(1).type());
        assertEquals(List.of("Wrote tests"), tidy.sections().get(1).entries().get(0).bullets());
    }

    private static ResumeData withExperienceBullets(List<String> bullets) {
        return new ResumeData("Jordan Lee", null, new Contact(null, null, null, List.of()), null,
                List.of(new Section("Experience", "entries", List.of(
                        new Entry("Brightline Analytics", "Software Engineering Intern", null, "Jun 2025 - Aug 2025", bullets)),
                        List.of())),
                List.of());
    }
}
