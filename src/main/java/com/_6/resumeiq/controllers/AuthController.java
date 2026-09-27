package com._6.resumeiq.controllers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com._6.resumeiq.model.User;
import com._6.resumeiq.repositories.UserRepository;
import com._6.resumeiq.service.EmailVerificationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import tools.jackson.databind.json.JsonMapper;

// Register / verify email / login / logout.
// A logged-in user is simply an HttpSession that has a "userId" attribute (same idea as Extractly's "role" attribute).
@Controller
public class AuthController {

    // session attribute names, shared with ReviewController
    public static final String SESSION_USER_ID_ATTR = "userId";
    public static final String SESSION_NAME_ATTR = "name";
    public static final String SESSION_EMAIL_ATTR = "email";
    public static final String SESSION_ROLE_ATTR = "role";

    private static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;

    // BCrypt: slow, salted hashing made for passwords
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // use to build JSON responses safely (quotes in messages can't break the JSON)
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public AuthController(UserRepository userRepository, EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
    }

    // Handles POST /register
    // register.js sends { "name": ..., "email": ..., "password": ... }
    @PostMapping("/register")
    @ResponseBody
    public ResponseEntity<String> register(@RequestBody Map<String, String> body) {
        String name = body.get("name") == null ? "" : body.get("name").trim();
        String email = body.get("email") == null ? "" : body.get("email").trim().toLowerCase(Locale.ROOT);
        String password = body.get("password") == null ? "" : body.get("password");

        if (name.isEmpty() || name.length() > 80) {
            return error(HttpStatus.BAD_REQUEST, "Please enter your name (up to 80 characters).");
        }
        if (!email.matches(EMAIL_PATTERN) || email.length() > 254) {
            return error(HttpStatus.BAD_REQUEST, "Please enter a valid email address.");
        }
        // BCrypt can only hash up to 72 BYTES (accented letters/emoji take 2-4 bytes each), so check bytes, not characters
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return error(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters and at most 72 bytes long.");
        }

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent() && existing.get().isVerified()) {
            return error(HttpStatus.CONFLICT, "An account with this email already exists. Try logging in.");
        }

        // an unverified account with this email gets overwritten, so a lost/expired verification email isn't a dead end
        User user = existing.orElseGet(User::new);
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));

        boolean verificationRequired = emailVerificationService.isEnabled();
        if (verificationRequired) {
            user.setVerified(false);
            user.setVerificationToken(UUID.randomUUID().toString().replace("-", ""));
            user.setVerificationTokenExpiry(Instant.now().plus(24, ChronoUnit.HOURS));
        } else {
            user.setVerified(true);
            user.setVerificationToken(null);
            user.setVerificationTokenExpiry(null);
        }
        userRepository.save(user);

        if (verificationRequired) {
            try {
                emailVerificationService.sendVerificationEmail(user);
            } catch (Exception e) {
                return error(HttpStatus.INTERNAL_SERVER_ERROR, "Account created, but the verification email could not be sent: "
                        + e.getMessage());
            }
            return json(HttpStatus.OK, Map.of(
                    "message", "Account created. Check your inbox for a link to verify your email, then log in.",
                    "verificationRequired", true));
        }
        return json(HttpStatus.OK, Map.of(
                "message", "Account created. You can log in now.",
                "verificationRequired", false));
    }

    // Handles GET /verify?token=...  (the link in the verification email)
    @GetMapping("/verify")
    public String verify(@RequestParam("token") String token) {
        Optional<User> found = userRepository.findByVerificationToken(token);
        if (found.isEmpty()) {
            return "redirect:/verify-failed.html";
        }
        User user = found.get();
        if (user.getVerificationTokenExpiry() == null || user.getVerificationTokenExpiry().isBefore(Instant.now())) {
            return "redirect:/verify-failed.html";
        }
        user.setVerified(true);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiry(null);
        userRepository.save(user);
        return "redirect:/login.html?verified=true";
    }

    // Handles POST /login
    // login.js sends { "email": ..., "password": ... }. On success the session remembers who is logged in
    @PostMapping("/login")
    @ResponseBody
    public ResponseEntity<String> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String email = body.get("email") == null ? "" : body.get("email").trim().toLowerCase(Locale.ROOT);
        String password = body.get("password") == null ? "" : body.get("password");

        Optional<User> found = userRepository.findByEmail(email);
        // same message for "no such user" and "wrong password" so the form can't be used to discover accounts
        if (found.isEmpty() || !passwordEncoder.matches(password, found.get().getPasswordHash())) {
            return error(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
        }
        User user = found.get();
        if (!user.isVerified()) {
            return error(HttpStatus.FORBIDDEN, "Please verify your email first (check your inbox for the link).");
        }

        HttpSession session = request.getSession(true);
        // new session id after login (prevents "session fixation" attacks)
        request.changeSessionId();
        session.setAttribute(SESSION_USER_ID_ATTR, user.getId());
        session.setAttribute(SESSION_NAME_ATTR, user.getName());
        session.setAttribute(SESSION_EMAIL_ATTR, user.getEmail());
        session.setAttribute(SESSION_ROLE_ATTR, user.getRole());

        return json(HttpStatus.OK, Map.of("message", "Logged in.", "redirect", "/review"));
    }

    // Handles POST /logout: throws the whole session away (including any saved Gemini key)
    @PostMapping("/logout")
    @ResponseBody
    public ResponseEntity<String> logout(HttpSession session) {
        session.invalidate();
        return json(HttpStatus.OK, Map.of("message", "Logged out."));
    }

    // Handles GET /me: lets pages check who is logged in
    @GetMapping("/me")
    @ResponseBody
    public ResponseEntity<String> me(HttpSession session) {
        if (session.getAttribute(SESSION_USER_ID_ATTR) == null) {
            return error(HttpStatus.UNAUTHORIZED, "Not logged in.");
        }
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("name", session.getAttribute(SESSION_NAME_ATTR));
        me.put("email", session.getAttribute(SESSION_EMAIL_ATTR));
        return json(HttpStatus.OK, me);
    }

    private ResponseEntity<String> error(HttpStatus status, String message) {
        return json(status, Map.of("error", message));
    }

    private ResponseEntity<String> json(HttpStatus status, Map<String, ?> body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(body));
    }
}
