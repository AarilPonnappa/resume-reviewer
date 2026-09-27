package com._6.resumeiq.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// One row per registered account
// Table is called "users" because "user" is a reserved word in PostgreSQL and H2
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    // always stored lowercase so "Aaril@Mail.com" and "aaril@mail.com" are the same account
    @Column(nullable = false, unique = true, length = 254)
    private String email;

    // BCrypt hash, never the plain password
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean verified;

    // only used when email verification is switched on
    @Column(length = 64)
    private String verificationToken;

    private Instant verificationTokenExpiry;

    @Column(nullable = false, length = 20)
    private String role = "USER";

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public User() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public String getVerificationToken() {
        return verificationToken;
    }

    public void setVerificationToken(String verificationToken) {
        this.verificationToken = verificationToken;
    }

    public Instant getVerificationTokenExpiry() {
        return verificationTokenExpiry;
    }

    public void setVerificationTokenExpiry(Instant verificationTokenExpiry) {
        this.verificationTokenExpiry = verificationTokenExpiry;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
