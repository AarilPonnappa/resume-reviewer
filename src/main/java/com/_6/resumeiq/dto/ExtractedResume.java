package com._6.resumeiq.dto;

// What ResumeTextExtractor pulls out of an uploaded file.
// pdfBytes is only set for PDFs: Gemini reads PDFs natively (layout included), so we send the original file too.
// text is always the plain text we could extract. FabricationGuard compares the rewrite against it.
public record ExtractedResume(String fileName, boolean pdf, byte[] pdfBytes, String text) {
}
