package com._6.resumeiq.service;

// when a Gemini call fails. The message is written for the end user (it is shown on the page as-is) and status is the HTTP status our controller should answer with.
public class GeminiException extends RuntimeException {

    private final int status;

    public GeminiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public GeminiException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
