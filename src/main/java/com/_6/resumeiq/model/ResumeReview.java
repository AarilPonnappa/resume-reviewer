package com._6.resumeiq.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

// One saved analysis. The review, the rewritten resume and the integrity report are stored as JSON text
// so the history page can reopen a review exactly as it was shown (and regenerate the PDF) without calling Gemini again.
// The uploaded file itself is NOT stored.
@Entity
@Table(name = "resume_reviews", indexes = @Index(name = "idx_resume_reviews_user", columnList = "userId"))
public class ResumeReview {

    // varchar(200000) works the same on H2 and PostgreSQL (unlike @Lob, which PostgreSQL stores as an "oid")
    public static final int JSON_COLUMN_LENGTH = 200_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(length = 120)
    private String targetRole;

    private Integer overallScore;

    @Column(length = 4)
    private String letterGrade;

    @Column(length = JSON_COLUMN_LENGTH)
    private String reviewJson;

    @Column(length = JSON_COLUMN_LENGTH)
    private String resumeJson;

    @Column(length = JSON_COLUMN_LENGTH)
    private String integrityJson;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public ResumeReview() {
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getTargetRole() {
        return targetRole;
    }

    public void setTargetRole(String targetRole) {
        this.targetRole = targetRole;
    }

    public Integer getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }

    public String getLetterGrade() {
        return letterGrade;
    }

    public void setLetterGrade(String letterGrade) {
        this.letterGrade = letterGrade;
    }

    public String getReviewJson() {
        return reviewJson;
    }

    public void setReviewJson(String reviewJson) {
        this.reviewJson = reviewJson;
    }

    public String getResumeJson() {
        return resumeJson;
    }

    public void setResumeJson(String resumeJson) {
        this.resumeJson = resumeJson;
    }

    public String getIntegrityJson() {
        return integrityJson;
    }

    public void setIntegrityJson(String integrityJson) {
        this.integrityJson = integrityJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
