package me.piyaphat.kei.core.api;

import java.util.List;

public record DetectedChapter(String dir, double number, String title, List<String> files) {
    public DetectedChapter {
        files = List.copyOf(files);
    }
}
