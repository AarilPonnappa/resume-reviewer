package com._6.resumeiq.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// A GeminiService that never touches the network: it hands back pre-written answers in order
// and remembers every prompt it was sent, so tests can check what the services asked for.
class FakeGeminiService extends GeminiService {

    private final Deque<String> answers = new ArrayDeque<>();
    final List<String> prompts = new ArrayList<>();

    FakeGeminiService(String... answers) {
        super("http://localhost:1", "test-model", 5L);
        for (String answer : answers) {
            this.answers.add(answer);
        }
    }

    @Override
    public String generateJson(String apiKey, String systemPrompt, String userPrompt, byte[] pdfBytes) {
        prompts.add(userPrompt);
        if (answers.isEmpty()) {
            throw new GeminiException("No more fake answers", 502);
        }
        return answers.poll();
    }
}
