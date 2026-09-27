package com._6.resumeiq.dto;

// The verified rewritten resume plus the report of what the integrity check found
public record RewriteOutcome(ResumeData resume, IntegrityReport integrity) {
}
