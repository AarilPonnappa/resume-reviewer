package com._6.resumeiq.service;

// Small helper for cleaning up the text Gemini returns before we parse it as JSON.
// Even in JSON mode a model can occasionally wrap its answer in ```json fences or add a sentence around it,
// so we cut out the outermost { ... } block (same idea as the fence-stripping in Extractly's parseGeminiResponse).
public final class JsonText {

    private JsonText() {
    }

    public static String extractObject(String raw) {
        if (raw == null) {
            return null;
        }
        String clean = raw.replace("```json", "").replace("```JSON", "").replace("```", "").trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return clean.substring(start, end + 1);
    }
}
