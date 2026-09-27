package com._6.resumeiq.controllers;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import com._6.resumeiq.dto.AnalysisResponse;
import com._6.resumeiq.dto.ResumeData;
import com._6.resumeiq.dto.ReviewSummary;
import com._6.resumeiq.service.GeminiException;
import com._6.resumeiq.service.ResumeAnalysisService;
import com._6.resumeiq.service.ResumePdfService;

import jakarta.servlet.http.HttpSession;
import tools.jackson.databind.json.JsonMapper;

@Controller
public class ReviewController {

    // a user can paste their own Gemini API key, which is kept ONLY in their
    // HttpSession (never saved to the database or logged) and disappears on logout.
    // If they don't, the server's key from GEMINI_API_KEY (.env / environment variable) is used.
    private static final String SESSION_GEMINI_KEY_ATTR = "geminiApiKey";

    @Value("${gemini.api.key:}")
    private String serverGeminiApiKey;

    private final ResumeAnalysisService analysisService;
    private final ResumePdfService pdfService;

    // use to convert Java objects into JSON text for the frontend
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public ReviewController(ResumeAnalysisService analysisService, ResumePdfService pdfService) {
        this.analysisService = analysisService;
        this.pdfService = pdfService;
    }


    // logged in -> the app, otherwise -> the landing page
    @GetMapping("/")
    public String index(HttpSession session) {
        return currentUserId(session) == null ? "redirect:/index.html" : "redirect:/review";
    }

    // the main page: upload + results (templates/review.html)
    @GetMapping("/review")
    public String reviewPage(HttpSession session, Model model) {
        if (currentUserId(session) == null) {
            return "redirect:/login.html";
        }
        model.addAttribute("name", session.getAttribute(AuthController.SESSION_NAME_ATTR));
        return "review";
    }

    // saved reviews (templates/history.html)
    @GetMapping("/history")
    public String historyPage(HttpSession session, Model model) {
        if (currentUserId(session) == null) {
            return "redirect:/login.html";
        }
        model.addAttribute("name", session.getAttribute(AuthController.SESSION_NAME_ATTR));
        return "history";
    }


    // Handles POST /gemini-key: stores the user's own key in their session
    @PostMapping("/gemini-key")
    @ResponseBody
    public ResponseEntity<String> saveGeminiKey(@RequestBody Map<String, String> body, HttpSession session) {
        if (currentUserId(session) == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in first.");
        }
        String geminiApiKey = body.get("geminiApiKey");
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "Gemini API key is required.");
        }
        session.setAttribute(SESSION_GEMINI_KEY_ATTR, geminiApiKey.trim());
        return json(HttpStatus.OK, Map.of("message", "Gemini API key saved for this session."));
    }

    // Handles POST /gemini-key/clear: removes only the key, the user stays logged in
    @PostMapping("/gemini-key/clear")
    @ResponseBody
    public ResponseEntity<String> clearGeminiKey(HttpSession session) {
        session.removeAttribute(SESSION_GEMINI_KEY_ATTR);
        return json(HttpStatus.OK, Map.of("message", "Gemini API key cleared."));
    }

    // Handles GET /gemini-key/status: says whether a key is available without ever exposing it
    @GetMapping("/gemini-key/status")
    @ResponseBody
    public ResponseEntity<String> geminiKeyStatus(HttpSession session) {
        boolean hasKey = session.getAttribute(SESSION_GEMINI_KEY_ATTR) != null;
        boolean serverKey = serverGeminiApiKey != null && !serverGeminiApiKey.isBlank();
        return json(HttpStatus.OK, Map.of("hasKey", hasKey, "serverKey", serverKey));
    }


    // Handles POST /analyze
    // review.js sends the resume file (multipart/form-data) plus the optional target role and job description.
    // Returns the grade, suggestions, rewrites, roadmap, the rewritten resume and the integrity report as one JSON object
    @PostMapping("/analyze")
    @ResponseBody
    public ResponseEntity<String> analyze(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "targetRole", required = false) String targetRole,
                                          @RequestParam(value = "jobDescription", required = false) String jobDescription,
                                          HttpSession session) {

        Long userId = currentUserId(session);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Your session has expired. Please log in again.");
        }

        String apiKey = resolveApiKey(session);
        if (apiKey == null) {
            return error(HttpStatus.BAD_REQUEST, "No Gemini API key available. Paste your key in the Gemini API key box and click Save.");
        }

        if (file == null || file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "Choose a resume file first.");
        }

        try {
            AnalysisResponse result = analysisService.analyze(userId, apiKey, file.getOriginalFilename(),
                    file.getBytes(), targetRole, jobDescription);
            return json(HttpStatus.OK, result);
        } catch (IllegalArgumentException e) {
            // problems with the uploaded file (wrong type, empty, password protected...)
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (GeminiException e) {
            return error(HttpStatus.valueOf(e.getStatus()), e.getMessage());
        } catch (IOException e) {
            return error(HttpStatus.BAD_REQUEST, "The uploaded file could not be read.");
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong while analysing the resume: " + e.getMessage());
        }
    }


    // Handles GET /reviews: this user's saved reviews, newest first
    @GetMapping("/reviews")
    @ResponseBody
    public ResponseEntity<String> listReviews(HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in.");
        }
        List<ReviewSummary> reviews = analysisService.history(userId);
        return json(HttpStatus.OK, Map.of("data", reviews));
    }

    @GetMapping("/reviews/{id}")
    @ResponseBody
    public ResponseEntity<String> getReview(@PathVariable("id") Long id, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in.");
        }
        Optional<AnalysisResponse> review = analysisService.load(userId, id);
        if (review.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "Review not found.");
        }
        return json(HttpStatus.OK, review.get());
    }

    // Handles GET /reviews/{id}/pdf: the rewritten resume as a PDF
    // ?inline=true shows it in the browser (preview) instead of downloading it
    @GetMapping("/reviews/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable("id") Long id,
                                              @RequestParam(value = "inline", defaultValue = "false") boolean inline,
                                              HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<ResumeData> resume = analysisService.loadResume(userId, id);
        if (resume.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        byte[] pdf;
        try {
            pdf = pdfService.render(resume.get());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        String fileName = pdfService.fileNameFor(resume.get());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition((inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(fileName)
                .build());
        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    // Handles DELETE /reviews/{id}
    @DeleteMapping("/reviews/{id}")
    @ResponseBody
    public ResponseEntity<String> deleteReview(@PathVariable("id") Long id, HttpSession session) {
        Long userId = currentUserId(session);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in.");
        }
        if (!analysisService.delete(userId, id)) {
            return error(HttpStatus.NOT_FOUND, "Review not found.");
        }
        return json(HttpStatus.OK, Map.of("message", "Review deleted."));
    }

    private Long currentUserId(HttpSession session) {
        return (Long) session.getAttribute(AuthController.SESSION_USER_ID_ATTR);
    }

    // the user's own session key wins; otherwise fall back to the server's key
    private String resolveApiKey(HttpSession session) {
        String sessionKey = (String) session.getAttribute(SESSION_GEMINI_KEY_ATTR);
        if (sessionKey != null && !sessionKey.isBlank()) {
            return sessionKey;
        }
        if (serverGeminiApiKey != null && !serverGeminiApiKey.isBlank()) {
            return serverGeminiApiKey.trim();
        }
        return null;
    }

    private ResponseEntity<String> error(HttpStatus status, String message) {
        return json(status, Map.of("error", message == null ? "Unexpected error." : message));
    }

    private ResponseEntity<String> json(HttpStatus status, Object body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(body));
    }
}
