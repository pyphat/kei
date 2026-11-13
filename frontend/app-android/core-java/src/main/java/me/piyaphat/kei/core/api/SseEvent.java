package me.piyaphat.kei.core.api;

public record SseEvent(String event, String data, String id) {}
