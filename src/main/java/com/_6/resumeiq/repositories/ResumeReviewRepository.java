package com._6.resumeiq.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com._6.resumeiq.model.ResumeReview;

public interface ResumeReviewRepository extends JpaRepository<ResumeReview, Long> {

    // newest first, for the history page
    List<ResumeReview> findByUserIdOrderByCreatedAtDesc(Long userId);

    // always look reviews up together with the owner's id so one user can never open another user's review
    Optional<ResumeReview> findByIdAndUserId(Long id, Long userId);
}
