package com._6.resumeiq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// Tests the unwrapping of Gemini's HTTP response (no network involved)
class GeminiServiceTest {

    private final GeminiService gemini = new GeminiService("http://localhost:1", "test-model", 5L);

    @Test
    void joinsAllTextPartsAndSkipsThoughts() {
        String body = """
                { "candidates": [ { "finishReason": "STOP", "content": { "role": "model", "parts": [
                    { "text": "thinking...", "thought": true },
                    { "text": "{\\"a\\": " },
                    { "text": "1}" }
                ] } } ] }
                """;
        assertEquals("{\"a\": 1}", gemini.readAnswerText(body));
    }

    @Test
    void blockedPromptGivesAClearError() {
        String body = "{ \"promptFeedback\": { \"blockReason\": \"SAFETY\" } }";
        GeminiException e = assertThrows(GeminiException.class, () -> gemini.readAnswerText(body));
        assertEquals(422, e.getStatus());
        assertTrue(e.getMessage().contains("SAFETY"));
    }

    @Test
    void truncatedAnswerIsReported() {
        String body = """
                { "candidates": [ { "finishReason": "MAX_TOKENS", "content": { "parts": [ { "text": "{\\"a\\"" } ] } } ] }
                """;
        GeminiException e = assertThrows(GeminiException.class, () -> gemini.readAnswerText(body));
        assertTrue(e.getMessage().contains("cut off"));
    }

    @Test
    void emptyResponsesAreErrors() {
        assertThrows(GeminiException.class, () -> gemini.readAnswerText(""));
        assertThrows(GeminiException.class, () -> gemini.readAnswerText("{ \"candidates\": [] }"));
        assertThrows(GeminiException.class, () -> gemini.readAnswerText("not json"));
    }

    @Test
    void blankModelFallsBackToDefault() {
        assertEquals(GeminiService.DEFAULT_MODEL, new GeminiService("", "  ", null).getModel());
    }
}
