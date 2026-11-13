package me.piyaphat.kei.core.api;

public record Progress(long seriesId, long chapterId, int page, double percent,
                       String deviceId, double ts) {}
