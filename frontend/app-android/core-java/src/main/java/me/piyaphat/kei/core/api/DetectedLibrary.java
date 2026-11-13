package me.piyaphat.kei.core.api;

import java.util.List;

public record DetectedLibrary(String seriesTitle, List<DetectedChapter> chapters) {
    public DetectedLibrary {
        chapters = List.copyOf(chapters);
    }
}
