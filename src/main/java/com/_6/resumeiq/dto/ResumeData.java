package com._6.resumeiq.dto;

import java.util.List;

// The restructured resume Gemini builds from the user's own content.
// This is what gets rendered into the downloadable PDF (templates/resume-pdf.html).
//
// sections are generic on purpose so the same shape works for any resume:
//   type "entries" -> Education, Experience, Projects, Leadership... (heading / subheading / dates / bullets)
//   type "skills"  -> Skills-style sections made of labelled groups ("Languages: Java, Python")
public record ResumeData(
        String name,
        String headline,
        Contact contact,
        String summary,
        List<Section> sections,
        List<String> restructuringNotes) {

    public record Contact(String email, String phone, String location, List<Link> links) {
    }

    public record Link(String label, String url) {
    }

    public record Section(String title, String type, List<Entry> entries, List<SkillGroup> skillGroups) {
    }

    public record Entry(String heading, String subheading, String location, String dates, List<String> bullets) {
    }

    public record SkillGroup(String label, List<String> items) {
    }
}
