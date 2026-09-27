package com._6.resumeiq.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// The only class that talks to the Gemini REST API.
// Review and rewrite services build the prompts, this class sends them and hands back the model's text.
@Service
public class GeminiService {

    public static final String DEFAULT_MODEL = "gemini-3.5-flash";
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    // use to send HTTP requests to Gemini's REST API
    private final RestClient restClient;

    // use to convert our request Map into valid JSON text (and read Gemini's response back)
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    // e.g. gemini-3.5-flash (set GEMINI_MODEL to change it, e.g. to gemini-2.5-flash like Extractly)
    private final String model;

    public GeminiService(@Value("${gemini.base-url:}") String baseUrl,
                         @Value("${gemini.model:}") String model,
                         @Value("${gemini.timeout-seconds:180}") Long timeoutSeconds) {
        // A resume review can take a while (the model "thinks" before answering), so allow a long read timeout
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // Long (not long) so an empty GEMINI_TIMEOUT_SECONDS= in .env falls back to 180 instead of failing startup
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds == null || timeoutSeconds <= 0 ? 180 : timeoutSeconds));

        // an empty value in .env (e.g. "GEMINI_MODEL=") would otherwise override the defaults with ""
        String url = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.restClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory)
                .build();
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
    }

    public String getModel() {
        return model;
    }

    // Sends one prompt to Gemini and returns the model's answer text (expected to be JSON).
    // pdfBytes is optional: when present the original PDF is attached so Gemini can also see the layout.
    public String generateJson(String apiKey, String systemPrompt, String userPrompt, byte[] pdfBytes) {

        // Build the request as a plain Java object graph that mirrors the JSON shape Gemini expects:
        // { "systemInstruction": { "parts": [ { "text": "..." } ] },
        //   "contents": [ { "role": "user", "parts": [ { "inlineData": {...} }, { "text": "..." } ] } ],
        //   "generationConfig": { "responseMimeType": "application/json" } }
        // jsonMapper then turns it into valid JSON no matter what characters are inside the prompt
        List<Map<String, Object>> parts = new ArrayList<>();
        if (pdfBytes != null && pdfBytes.length > 0) {
            // JSON can't hold raw bytes, so the PDF is sent as base64 text
            parts.add(Map.of("inlineData", Map.of(
                    "mimeType", "application/pdf",
                    "data", Base64.getEncoder().encodeToString(pdfBytes))));
        }
        parts.add(Map.of("text", userPrompt));

        // responseMimeType "application/json" forces Gemini into strict JSON mode.
        // No temperature is set on purpose: Gemini 3 models are tuned for their default sampling settings.
        Map<String, Object> requestPayload = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of("role", "user", "parts", parts)),
                "generationConfig", Map.of("responseMimeType", "application/json"));

        String requestBody = jsonMapper.writeValueAsString(requestPayload);

        String responseBody;
        try {
            // POST {base-url}/models/{model}:generateContent
            // The key goes in the x-goog-api-key header instead of ?key= in the URL,
            // so it never shows up in server/proxy logs that record URLs
            responseBody = restClient.post()
                    .uri("/models/{model}:generateContent", model)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-goog-api-key", apiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // Gemini answered with an error status (bad key, quota, unknown model...)
            int status = e.getStatusCode().value();
            throw new GeminiException(friendlyError(status, e.getResponseBodyAsString()), statusForUser(status), e);
        } catch (ResourceAccessException e) {
            // network problem or timeout
            throw new GeminiException("Could not reach Gemini (network error or timeout). Please try again.", 504, e);
        }

        return readAnswerText(responseBody);
    }

    // Gemini's HTTP response is JSON that WRAPS the model's answer:
    // { "candidates": [ { "content": { "parts": [ { "text": "<the answer>" } ] }, "finishReason": "STOP" } ] }
    // This digs out the answer text (the same unwrapping Extractly's parseGeminiResponse does in JavaScript)
    @SuppressWarnings("unchecked")
    String readAnswerText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new GeminiException("Gemini returned an empty response. Please try again.", 502);
        }

        Map<String, Object> data;
        try {
            data = jsonMapper.readValue(responseBody, Map.class);
        } catch (JacksonException e) {
            throw new GeminiException("Gemini returned a response that was not valid JSON.", 502, e);
        }

        // the whole prompt was blocked before the model even answered
        Map<String, Object> promptFeedback = (Map<String, Object>) data.get("promptFeedback");
        if (promptFeedback != null && promptFeedback.get("blockReason") != null) {
            throw new GeminiException("Gemini refused to process this resume (" + promptFeedback.get("blockReason")
                    + "). Try a different file.", 422);
        }

        List<Object> candidates = (List<Object>) data.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new GeminiException("Gemini returned no result. Please try again.", 502);
        }

        Map<String, Object> candidate = (Map<String, Object>) candidates.get(0);
        String finishReason = (String) candidate.get("finishReason");
        Map<String, Object> content = (Map<String, Object>) candidate.get("content");
        List<Object> answerParts = content == null ? null : (List<Object>) content.get("parts");

        // join ALL parts: long answers can be split across several, and "thought" parts are skipped
        StringBuilder text = new StringBuilder();
        if (answerParts != null) {
            for (Object partObject : answerParts) {
                Map<String, Object> part = (Map<String, Object>) partObject;
                if (Boolean.TRUE.equals(part.get("thought"))) {
                    continue;
                }
                Object partText = part.get("text");
                if (partText != null) {
                    text.append(partText);
                }
            }
        }

        if ("MAX_TOKENS".equals(finishReason)) {
            throw new GeminiException("Gemini's answer was cut off (output token limit). Try a shorter resume.", 502);
        }
        if (text.length() == 0) {
            if ("SAFETY".equals(finishReason) || "RECITATION".equals(finishReason) || "PROHIBITED_CONTENT".equals(finishReason)) {
                throw new GeminiException("Gemini stopped before answering (" + finishReason + "). Try again.", 422);
            }
            throw new GeminiException("Gemini returned an empty answer. Please try again.", 502);
        }
        return text.toString();
    }

    // Gemini's error bodies look like { "error": { "code": 400, "message": "...", "status": "INVALID_ARGUMENT" } }
    @SuppressWarnings("unchecked")
    private String friendlyError(int status, String body) {
        String message = "";
        try {
            Map<String, Object> error = (Map<String, Object>) jsonMapper.readValue(body, Map.class).get("error");
            if (error != null && error.get("message") != null) {
                message = error.get("message").toString();
            }
        } catch (RuntimeException ignored) {
            // body was not JSON, fall through with an empty message
        }

        String lower = message.toLowerCase();
        if (lower.contains("api key not valid") || lower.contains("api_key_invalid") || status == 401) {
            return "Your Gemini API key is not valid. Check it (or save a new one) and try again.";
        }
        if (status == 403) {
            return "Gemini denied access with this API key (permission denied). " + message;
        }
        if (status == 404) {
            return "Gemini model '" + model + "' was not found for this API key. Set GEMINI_MODEL to a model you can use "
                    + "(for example gemini-3.5-flash or gemini-2.5-flash).";
        }
        if (status == 429) {
            return "Gemini rate limit or quota reached. Wait a minute and try again.";
        }
        if (status >= 500) {
            return "Gemini is temporarily unavailable (HTTP " + status + "). Please try again in a moment.";
        }
        return "Gemini request failed (HTTP " + status + ")" + (message.isBlank() ? "." : ": " + message);
    }

    // what WE answer the browser with: bad input/key -> 400, rate limit -> 429, everything else -> 502 (upstream failed)
    private int statusForUser(int geminiStatus) {
        if (geminiStatus == 400 || geminiStatus == 401 || geminiStatus == 403 || geminiStatus == 404) {
            return 400;
        }
        if (geminiStatus == 429) {
            return 429;
        }
        return 502;
    }
}
