package com._6.resumeiq.dto;

import java.util.List;

// Result of FabricationGuard checking the rewritten resume against the original text.
// Shown to the user so they can see exactly what (if anything) was removed and why.
public record IntegrityReport(
        Boolean checked,              // false when the original had no readable text layer (e.g. a scanned image PDF)
        String note,                  // one-line summary shown in the UI
        Integer issuesFoundFirstPass, // how many unsupported items Gemini's first draft had
        Boolean retried,              // true if we sent the draft back to Gemini to fix those items
        List<String> removedItems,    // anything still unsupported after the retry, removed before building the PDF
        List<String> warnings) {
}
