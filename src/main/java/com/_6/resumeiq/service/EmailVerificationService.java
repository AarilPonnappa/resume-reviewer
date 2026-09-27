package com._6.resumeiq.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com._6.resumeiq.model.User;

// Sends the "confirm your email" link after registration.
// Switched OFF by default (EMAIL_VERIFICATION_ENABLED=false) so the app runs locally without an SMTP server;
// when off, new accounts are verified immediately.
@Service
public class EmailVerificationService {

    // ObjectProvider because the JavaMailSender bean only exists when spring.mail.host is configured
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    // Boolean (not boolean) so an empty value in .env simply means "off"
    @Value("${app.email-verification.enabled:false}")
    private Boolean enabled;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.mail.from:no-reply@resumeiq.local}")
    private String fromAddress;

    @Value("${spring.mail.host:}")
    private String mailHost;

    public EmailVerificationService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public void sendVerificationEmail(User user) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null || mailHost == null || mailHost.isBlank()) {
            throw new IllegalStateException("Email verification is enabled but no SMTP server is configured "
                    + "(set MAIL_HOST, MAIL_PORT, MAIL_USERNAME and MAIL_PASSWORD).");
        }

        String link = baseUrl.replaceAll("/+$", "") + "/verify?token=" + user.getVerificationToken();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("Confirm your ResumeIQ account");
        message.setText("Hi " + user.getName() + ",\n\n"
                + "Click the link below to confirm your email address and activate your ResumeIQ account:\n\n"
                + link + "\n\n"
                + "The link expires in 24 hours. If you didn't create an account, you can ignore this email.\n");
        mailSender.send(message);
    }
}
