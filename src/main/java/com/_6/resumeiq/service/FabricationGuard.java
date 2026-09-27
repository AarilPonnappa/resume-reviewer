package com._6.resumeiq.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com._6.resumeiq.dto.ResumeData;
import com._6.resumeiq.dto.ResumeData.Contact;
import com._6.resumeiq.dto.ResumeData.Entry;
import com._6.resumeiq.dto.ResumeData.Link;
import com._6.resumeiq.dto.ResumeData.Section;
import com._6.resumeiq.dto.ResumeData.SkillGroup;

// Makes sure the rewritten resume never claims anything the original resume doesn't.
// The prompt already tells Gemini not to invent anything; this class CHECKS it in code, because a prompt is a request, not a guarantee.
//
// Everything in the rewrite is compared against the text we extracted from the uploaded file:
//   - company / school / project names must appear in the original
//   - every number (dates, GPA, percentages, user counts...) must appear in the original
//   - capitalised terms inside bullets (technologies, tools, organisations) must appear in the original
//   - every skill, email, phone number and link must appear in the original
// findViolations() lists the problems (used to ask Gemini for a corrected version),
// sanitize() removes whatever is still unsupported (used right before building the PDF).
@Service
public class FabricationGuard {

    // any number: 5, 2024, 3.85, 1,000
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    // small words ignored when measuring how much of a name/title matches the original
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "the", "of", "in", "at", "for", "to", "on", "with", "by", "from", "as", "or", "via");

    // capitalised words that are never "claims" (months, date words...)
    private static final Set<String> GENERIC_WORDS = Set.of(
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
            "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "present", "current", "now", "remote", "hybrid", "onsite", "summer", "fall", "spring", "winter", "autumn",
            "i", "my");

    // below this much text we can't meaningfully verify anything (e.g. a scanned image PDF with no text layer)
    private static final int MIN_VERIFIABLE_CHARS = 150;

    public boolean canVerify(String sourceText) {
        return sourceText != null && normalize(sourceText).length() >= MIN_VERIFIABLE_CHARS;
    }

    // Lists every unsupported item without changing anything
    public List<String> findViolations(ResumeData resume, String sourceText) {
        List<String> issues = new ArrayList<>();
        process(resume, new SourceIndex(sourceText), issues);
        return issues;
    }

    // Returns a copy with every unsupported item removed; each removal is described in removedLog
    public ResumeData sanitize(ResumeData resume, String sourceText, List<String> removedLog) {
        return process(resume, new SourceIndex(sourceText), removedLog);
    }

    // true when a bullet the review says it "copied from the resume" really is (mostly word-for-word) in the original
    public boolean isQuotedFromSource(String quote, String sourceText) {
        SourceIndex index = new SourceIndex(sourceText);
        String normalized = normalize(quote);
        if (normalized.isEmpty()) {
            return false;
        }
        if (index.containsPhrase(normalized)) {
            return true;
        }
        List<String> tokens = tokens(normalized);
        long found = tokens.stream().filter(index::hasToken).count();
        return (double) found / tokens.size() >= 0.8;
    }

    // Numbers in the text that are not in the original. Anything inside [square brackets] can be ignored:
    // the review uses "[X%]" style placeholders to tell the user where to add their OWN real numbers.
    public Set<String> unsupportedNumbers(String text, String sourceText, boolean ignoreBracketed) {
        return unsupportedNumbers(text, new SourceIndex(sourceText), ignoreBracketed);
    }

    public boolean appearsInSource(String phrase, String sourceText) {
        SourceIndex index = new SourceIndex(sourceText);
        String normalized = normalize(phrase);
        String compact = normalized.replace(" ", "");
        return index.containsPhrase(normalized) || (compact.length() >= 5 && index.compact.contains(compact));
    }

    // Cleans up the SHAPE of Gemini's output without judging content: trims text, drops blank bullets,
    // never leaves a list null, works out each section's type. Always run before rendering.
    public ResumeData tidy(ResumeData resume) {
        if (resume == null) {
            return new ResumeData(null, null, new Contact(null, null, null, List.of()), null, List.of(), List.of());
        }

        Contact contact = resume.contact();
        List<Link> links = new ArrayList<>();
        if (contact != null && contact.links() != null) {
            for (Link link : contact.links()) {
                if (link == null || clean(link.url()) == null) {
                    continue;
                }
                String url = clean(link.url());
                String label = clean(link.label()) != null ? clean(link.label()) : shortUrl(url);
                links.add(new Link(label, url));
            }
        }
        Contact tidyContact = contact == null
                ? new Contact(null, null, null, links)
                : new Contact(clean(contact.email()), clean(contact.phone()), clean(contact.location()), links);

        List<Section> sections = new ArrayList<>();
        if (resume.sections() != null) {
            for (Section section : resume.sections()) {
                if (section == null) {
                    continue;
                }
                List<Entry> entries = new ArrayList<>();
                if (section.entries() != null) {
                    for (Entry entry : section.entries()) {
                        if (entry == null) {
                            continue;
                        }
                        List<String> bullets = new ArrayList<>();
                        if (entry.bullets() != null) {
                            for (String bullet : entry.bullets()) {
                                String cleanBullet = cleanBullet(bullet);
                                if (cleanBullet != null) {
                                    bullets.add(cleanBullet);
                                }
                            }
                        }
                        String heading = clean(entry.heading());
                        if (heading == null && bullets.isEmpty()) {
                            continue;
                        }
                        entries.add(new Entry(heading, clean(entry.subheading()), clean(entry.location()),
                                clean(entry.dates()), bullets));
                    }
                }
                List<SkillGroup> groups = new ArrayList<>();
                if (section.skillGroups() != null) {
                    for (SkillGroup group : section.skillGroups()) {
                        if (group == null || group.items() == null) {
                            continue;
                        }
                        List<String> items = new ArrayList<>();
                        for (String item : group.items()) {
                            String cleanItem = clean(item);
                            if (cleanItem != null) {
                                items.add(cleanItem);
                            }
                        }
                        if (!items.isEmpty()) {
                            groups.add(new SkillGroup(clean(group.label()) != null ? clean(group.label()) : "Skills", items));
                        }
                    }
                }
                if (entries.isEmpty() && groups.isEmpty()) {
                    continue;
                }
                boolean skills = "skills".equalsIgnoreCase(clean(section.type())) ? !groups.isEmpty() : entries.isEmpty();
                String title = clean(section.title()) != null ? clean(section.title()) : (skills ? "Skills" : "Experience");
                sections.add(new Section(title, skills ? "skills" : "entries",
                        skills ? List.of() : entries, skills ? groups : List.of()));
            }
        }

        List<String> notes = new ArrayList<>();
        if (resume.restructuringNotes() != null) {
            for (String note : resume.restructuringNotes()) {
                if (clean(note) != null) {
                    notes.add(clean(note));
                }
            }
        }

        return new ResumeData(clean(resume.name()), clean(resume.headline()), tidyContact, clean(resume.summary()),
                sections, notes);
    }

    // ---------------------------------------------------------------------------------------------
    // The actual checking. Every problem is written into log in plain English, and a cleaned copy is returned.
    // ---------------------------------------------------------------------------------------------
    private ResumeData process(ResumeData input, SourceIndex index, List<String> log) {
        ResumeData resume = tidy(input);

        String headline = resume.headline();
        if (headline != null && (tokenSupport(headline, index) < 0.6
                || !unsupportedNumbers(headline, index, false).isEmpty()
                || !unsupportedTerms(headline, index).isEmpty())) {
            log.add("Headline \"" + headline + "\" is not supported by the original resume");
            headline = null;
        }

        String summary = resume.summary();
        if (summary != null) {
            Set<String> problems = new LinkedHashSet<>(unsupportedNumbers(summary, index, false));
            problems.addAll(unsupportedTerms(summary, index));
            if (!problems.isEmpty()) {
                log.add("Summary uses " + quoteAll(problems) + ", which the original resume does not contain");
                summary = null;
            }
        }

        Contact contact = checkContact(resume.contact(), index, log);

        List<Section> sections = new ArrayList<>();
        for (Section section : resume.sections()) {
            String where = "[" + section.title() + "] ";

            List<Entry> entries = new ArrayList<>();
            for (Entry entry : section.entries()) {
                if (entry.heading() != null && !isSupportedName(entry.heading(), index)) {
                    log.add(where + "\"" + entry.heading() + "\" does not appear in the original resume (whole entry)");
                    continue;
                }
                String label = entry.heading() != null ? "\"" + entry.heading() + "\"" : "an entry";

                String subheading = entry.subheading();
                if (subheading != null && (tokenSupport(subheading, index) < 0.5
                        || !unsupportedNumbers(subheading, index, false).isEmpty())) {
                    log.add(where + "subtitle \"" + subheading + "\" under " + label + " is not in the original resume");
                    subheading = null;
                }

                String location = entry.location();
                if (location != null && tokenSupport(location, index) < 0.6) {
                    log.add(where + "location \"" + location + "\" under " + label + " is not in the original resume");
                    location = null;
                }

                String dates = entry.dates();
                if (dates != null) {
                    Set<String> badNumbers = unsupportedNumbers(dates, index, false);
                    if (!badNumbers.isEmpty()) {
                        log.add(where + "dates \"" + dates + "\" under " + label + " use " + quoteAll(badNumbers)
                                + ", which the original resume does not contain");
                        dates = null;
                    }
                }

                List<String> bullets = new ArrayList<>();
                for (String bullet : entry.bullets()) {
                    Set<String> problems = new LinkedHashSet<>(unsupportedNumbers(bullet, index, false));
                    problems.addAll(unsupportedTerms(bullet, index));
                    if (problems.isEmpty()) {
                        bullets.add(bullet);
                    } else {
                        log.add(where + "bullet under " + label + ": \"" + bullet + "\" uses " + quoteAll(problems)
                                + ", which the original resume does not contain");
                    }
                }
                entries.add(new Entry(entry.heading(), subheading, location, dates, bullets));
            }

            List<SkillGroup> groups = new ArrayList<>();
            for (SkillGroup group : section.skillGroups()) {
                List<String> items = new ArrayList<>();
                for (String item : group.items()) {
                    if (isSupportedSkill(item, index)) {
                        items.add(item);
                    } else {
                        log.add(where + "skill \"" + item + "\" is not listed anywhere in the original resume");
                    }
                }
                if (!items.isEmpty()) {
                    groups.add(new SkillGroup(group.label(), items));
                }
            }

            if (!entries.isEmpty() || !groups.isEmpty()) {
                sections.add(new Section(section.title(), section.type(), entries, groups));
            }
        }

        return new ResumeData(resume.name(), headline, contact, summary, sections, resume.restructuringNotes());
    }

    private Contact checkContact(Contact contact, SourceIndex index, List<String> log) {
        String email = contact.email();
        if (email != null && !index.lowerNoSpace.contains(email.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""))) {
            log.add("Email \"" + email + "\" is not in the original resume");
            email = null;
        }

        String phone = contact.phone();
        if (phone != null) {
            String digits = phone.replaceAll("\\D", "");
            // allow an added country code (+1) as long as the rest of the number matches
            boolean found = digits.length() >= 7 && (index.digits.contains(digits)
                    || (digits.length() > 10 && index.digits.contains(digits.substring(digits.length() - 10))));
            if (!found) {
                log.add("Phone number \"" + phone + "\" is not in the original resume");
                phone = null;
            }
        }

        String location = contact.location();
        if (location != null && tokenSupport(location, index) < 0.6) {
            log.add("Location \"" + location + "\" is not in the original resume");
            location = null;
        }

        List<Link> links = new ArrayList<>();
        for (Link link : contact.links()) {
            String canonical = canonicalUrl(link.url());
            if (!canonical.isEmpty() && index.lowerNoSpace.contains(canonical)) {
                links.add(link);
            } else {
                log.add("Link \"" + link.url() + "\" is not in the original resume");
            }
        }
        return new Contact(email, phone, location, links);
    }

    // ---------------------------------------------------------------------------------------------
    // Matching helpers
    // ---------------------------------------------------------------------------------------------

    // A name (company, school, project) counts as supported if it appears as-is, or most of its words do
    private boolean isSupportedName(String name, SourceIndex index) {
        String normalized = normalize(name);
        String compact = normalized.replace(" ", "");
        return index.containsPhrase(normalized)
                || (compact.length() >= 5 && index.compact.contains(compact))
                || tokenSupport(name, index) >= 0.6;
    }

    private boolean isSupportedSkill(String skill, SourceIndex index) {
        String normalized = normalize(skill);
        if (normalized.isEmpty()) {
            return false;
        }
        String compact = normalized.replace(" ", "");
        return index.containsPhrase(normalized)
                || (compact.length() >= 5 && index.compact.contains(compact))
                || tokenSupport(skill, index) == 1.0;
    }

    // share of the meaningful words in text that also appear in the original (0.0 - 1.0)
    private double tokenSupport(String text, SourceIndex index) {
        List<String> tokens = new ArrayList<>();
        for (String token : tokens(normalize(text))) {
            if (!STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty()) {
            return 1.0;
        }
        long found = tokens.stream().filter(index::hasToken).count();
        return (double) found / tokens.size();
    }

    private Set<String> unsupportedNumbers(String text, SourceIndex index, boolean ignoreBracketed) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null) {
            return result;
        }
        String checked = ignoreBracketed ? text.replaceAll("\\[[^\\]]*\\]", " ") : text;
        Matcher matcher = NUMBER.matcher(checked);
        while (matcher.find()) {
            if (!index.numbers.contains(canonicalNumber(matcher.group()))) {
                result.add(matcher.group());
            }
        }
        return result;
    }

    // Capitalised or technical-looking words (React, AWS, Kubernetes, Node.js, C++, Google...) that are not in the original.
    // The first word of each sentence is skipped because bullets start with a capitalised verb ("Built", "Led").
    private Set<String> unsupportedTerms(String text, SourceIndex index) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null) {
            return result;
        }
        String[] rawWords = text.trim().split("\\s+");
        boolean sentenceStart = true;
        for (String rawWord : rawWords) {
            boolean firstWordOfSentence = sentenceStart;
            sentenceStart = rawWord.matches(".*[.!?:;]$") || rawWord.matches("[-–—•|/]+");

            String word = stripEdgePunctuation(rawWord);
            if (word.isEmpty() || firstWordOfSentence || !looksLikeTerm(word)) {
                continue;
            }
            String normalized = normalize(word);
            String compact = normalized.replace(" ", "");
            if (compact.length() <= 2 || GENERIC_WORDS.contains(normalized)) {
                continue; // "AI", "UI", "Go", months... too short/generic to judge
            }
            boolean found = index.containsPhrase(normalized)
                    || (compact.length() >= 5 && index.compact.contains(compact))
                    || (normalized.endsWith("es") && index.containsPhrase(normalized.substring(0, normalized.length() - 2)))
                    || (normalized.endsWith("s") && index.containsPhrase(normalized.substring(0, normalized.length() - 1)));
            if (!found) {
                result.add(word);
            }
        }
        return result;
    }

    private static boolean looksLikeTerm(String word) {
        if (!word.matches(".*[A-Za-z].*")) {
            return false;
        }
        boolean capitalised = Character.isUpperCase(word.charAt(0));
        boolean innerCapital = word.length() > 1 && !word.substring(1).equals(word.substring(1).toLowerCase(Locale.ROOT));
        boolean symbols = word.contains("+") || word.contains("#");
        boolean lettersAndDigits = word.matches(".*\\d.*");
        boolean dotted = word.matches(".*[A-Za-z]\\.[A-Za-z].*");
        return capitalised || innerCapital || symbols || lettersAndDigits || dotted;
    }

    private static String stripEdgePunctuation(String word) {
        String stripped = word.replaceAll("^[(\\[{\"'“‘*•]+", "")
                .replaceAll("[)\\]}\"'”’,.;:!?]+$", "");
        return stripped.replaceAll("(?i)['’]s$", "");
    }


    // lowercase, accents removed (é -> e), everything except letters/digits/+/# turned into single spaces
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String result = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        result = result.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#]+", " ").trim();
        return result;
    }

    private static List<String> tokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    // "1,000" -> "1000", "4.0" -> "4", "06" -> "6", so the same number written two ways still matches
    static String canonicalNumber(String number) {
        String plain = number.replace(",", "");
        try {
            return new BigDecimal(plain).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return plain;
        }
    }

    static String canonicalUrl(String url) {
        if (url == null) {
            return "";
        }
        String result = url.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        result = result.replaceFirst("^(https?://|mailto:)", "").replaceFirst("^www\\.", "");
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String shortUrl(String url) {
        String canonical = canonicalUrl(url);
        return canonical.isEmpty() ? url : canonical;
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String result = text.replaceAll("\\s+", " ").trim();
        if (result.isEmpty() || result.equalsIgnoreCase("null") || result.equalsIgnoreCase("n/a")) {
            return null;
        }
        return result;
    }

    // strips leading bullet symbols the model sometimes includes ("• Built...", "- Built...")
    private static String cleanBullet(String bullet) {
        String result = clean(bullet);
        if (result == null) {
            return null;
        }
        result = result.replaceFirst("^[•●▪·*\\-–]+\\s*", "").trim();
        return result.isEmpty() ? null : result;
    }

    private static String quoteAll(Set<String> items) {
        List<String> quoted = new ArrayList<>();
        for (String item : items) {
            quoted.add("\"" + item + "\"");
        }
        return String.join(", ", quoted);
    }

    // Pre-computed views of the original resume text so each check is a quick lookup
    private static final class SourceIndex {
        final String spaced;       // " normalized text " for whole-phrase matching
        final String compact;      // normalized text with no spaces ("nodejs" matches "Node.js")
        final Set<String> tokens;  // every normalized word
        final Set<String> numbers; // every number, canonical form
        final String lowerNoSpace; // raw lowercase text without whitespace (emails, URLs split across lines)
        final String digits;       // only the digits (phone numbers written with any formatting)

        SourceIndex(String source) {
            String text = source == null ? "" : source;
            String normalized = normalize(text);
            this.spaced = " " + normalized + " ";
            this.compact = normalized.replace(" ", "");
            this.tokens = new HashSet<>(Arrays.asList(normalized.split(" ")));
            this.numbers = new HashSet<>();
            Matcher matcher = NUMBER.matcher(text);
            while (matcher.find()) {
                numbers.add(canonicalNumber(matcher.group()));
            }
            this.lowerNoSpace = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            this.digits = text.replaceAll("\\D", "");
        }

        boolean containsPhrase(String normalizedPhrase) {
            return !normalizedPhrase.isEmpty() && spaced.contains(" " + normalizedPhrase + " ");
        }

        // word match that tolerates simple plurals ("api" vs "apis")
        boolean hasToken(String token) {
            return tokens.contains(token)
                    || (token.endsWith("s") && tokens.contains(token.substring(0, token.length() - 1)))
                    || tokens.contains(token + "s");
        }
    }
}
