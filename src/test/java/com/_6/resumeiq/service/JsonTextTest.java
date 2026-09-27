package com._6.resumeiq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JsonTextTest {

    @Test
    void plainJsonIsReturnedAsIs() {
        assertEquals("{\"a\":1}", JsonText.extractObject("{\"a\":1}"));
    }

    @Test
    void codeFencesAndSurroundingTextAreRemoved() {
        String raw = "Here you go:\n```json\n{\"a\": {\"b\": 2}}\n```\nHope that helps!";
        assertEquals("{\"a\": {\"b\": 2}}", JsonText.extractObject(raw));
    }

    @Test
    void noObjectGivesNull() {
        assertNull(JsonText.extractObject("sorry, I can't help with that"));
        assertNull(JsonText.extractObject(null));
    }
}
