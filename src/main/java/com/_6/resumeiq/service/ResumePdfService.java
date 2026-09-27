package com._6.resumeiq.service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com._6.resumeiq.dto.ResumeData;
import com._6.resumeiq.dto.ResumeData.Contact;
import com._6.resumeiq.dto.ResumeData.Entry;
import com._6.resumeiq.dto.ResumeData.Link;
import com._6.resumeiq.dto.ResumeData.Section;
import com._6.resumeiq.dto.ResumeData.SkillGroup;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

// Builds the downloadable resume PDF:
//   ResumeData -> Thymeleaf fills templates/resume-pdf.html -> openhtmltopdf turns that HTML into a PDF
// Designing the resume as an HTML/CSS template means the layout can be changed without touching Java.
@Service
public class ResumePdfService {

    // One piece of the contact line under the name. url is null for plain text (phone, location)
    public record ContactPart(String text, String url) {
    }

    private final ITemplateEngine templateEngine;
    private final FabricationGuard fabricationGuard;

    public ResumePdfService(ITemplateEngine templateEngine, FabricationGuard fabricationGuard) {
        this.templateEngine = templateEngine;
        this.fabricationGuard = fabricationGuard;
    }

    public byte[] render(ResumeData resume) {
        ResumeData clean = pdfSafe(fabricationGuard.tidy(resume));

        Context context = new Context(Locale.US);
        context.setVariable("resume", clean);
        context.setVariable("contactParts", contactParts(clean.contact()));
        String html = templateEngine.process("resume-pdf", context);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            // jsoup parses the HTML5 output into a proper DOM, which openhtmltopdf then lays out as pages
            builder.withW3cDocument(new W3CDom().fromJsoup(Jsoup.parse(html)), "/");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the resume PDF: " + e.getMessage(), e);
        }
    }

    // "Aaril_Ponnappa_Resume.pdf"
    public String fileNameFor(ResumeData resume) {
        String name = resume == null || resume.name() == null ? "" : resume.name();
        String safe = name.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return (safe.isEmpty() ? "Improved" : safe) + "_Resume.pdf";
    }

    List<ContactPart> contactParts(Contact contact) {
        List<ContactPart> parts = new ArrayList<>();
        if (contact == null) {
            return parts;
        }
        if (contact.email() != null) {
            parts.add(new ContactPart(contact.email(), "mailto:" + contact.email()));
        }
        if (contact.phone() != null) {
            parts.add(new ContactPart(contact.phone(), null));
        }
        if (contact.location() != null) {
            parts.add(new ContactPart(contact.location(), null));
        }
        for (Link link : contact.links()) {
            String url = link.url();
            String href = url.matches("(?i)^(https?://|mailto:).*") ? url : "https://" + url;
            // show the address itself (it is what a recruiter reading a printed copy needs)
            String shown = url.replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^www\\.", "").replaceAll("/+$", "");
            parts.add(new ContactPart(shown, href));
        }
        return parts;
    }

    // ---------------------------------------------------------------------------------------------
    // The PDF uses the standard built-in PDF fonts (Helvetica), which only cover Western European characters.
    // Anything else (arrows, emoji, check marks...) would print as '#', so it is swapped or removed here.
    // ---------------------------------------------------------------------------------------------
    private ResumeData pdfSafe(ResumeData resume) {
        Contact contact = resume.contact();
        List<Link> links = new ArrayList<>();
        for (Link link : contact.links()) {
            links.add(new Link(safe(link.label()), link.url()));
        }
        Contact safeContact = new Contact(safe(contact.email()), safe(contact.phone()), safe(contact.location()), links);

        List<Section> sections = new ArrayList<>();
        for (Section section : resume.sections()) {
            List<Entry> entries = new ArrayList<>();
            for (Entry entry : section.entries()) {
                List<String> bullets = new ArrayList<>();
                for (String bullet : entry.bullets()) {
                    if (safe(bullet) != null) {
                        bullets.add(safe(bullet));
                    }
                }
                entries.add(new Entry(safe(entry.heading()), safe(entry.subheading()), safe(entry.location()),
                        safe(entry.dates()), bullets));
            }
            List<SkillGroup> groups = new ArrayList<>();
            for (SkillGroup group : section.skillGroups()) {
                List<String> items = new ArrayList<>();
                for (String item : group.items()) {
                    if (safe(item) != null) {
                        items.add(safe(item));
                    }
                }
                if (!items.isEmpty()) {
                    groups.add(new SkillGroup(safe(group.label()) != null ? safe(group.label()) : "Skills", items));
                }
            }
            String title = safe(section.title()) != null ? safe(section.title()) : "Section";
            sections.add(new Section(title, section.type(), entries, groups));
        }
        return new ResumeData(safe(resume.name()), safe(resume.headline()), safeContact, safe(resume.summary()),
                sections, resume.restructuringNotes());
    }

    static String safe(String text) {
        if (text == null) {
            return null;
        }
        String replaced = text
                .replace("→", "->").replace("←", "<-").replace("⇒", "=>")
                .replace("−", "-").replace("‐", "-").replace("‑", "-")
                .replace(" ", " ").replace(" ", " ").replace(" ", " ");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < replaced.length(); i++) {
            char c = replaced.charAt(i);
            boolean latin = (c >= 0x20 && c <= 0x7E) || (c >= 0xA0 && c <= 0xFF);
            boolean winAnsiExtra = "–—‘’‚“”„•…€™†‡‰ŒœŠšŽžŸƒ"
                    .indexOf(c) >= 0;
            if (latin || winAnsiExtra) {
                result.append(c);
            }
        }
        String cleaned = result.toString().replaceAll(" {2,}", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
