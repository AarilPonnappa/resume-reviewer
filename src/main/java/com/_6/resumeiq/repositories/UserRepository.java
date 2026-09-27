package com._6.resumeiq.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com._6.resumeiq.model.User;

// Spring Data writes the SQL for these from the method names
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByVerificationToken(String verificationToken);
}
