package me.piyaphat.kei.core.api;

import java.util.function.Consumer;

public interface SseEventParser {
    void onLine(String line, Consumer<SseEvent> sink);
    void reset();
}
