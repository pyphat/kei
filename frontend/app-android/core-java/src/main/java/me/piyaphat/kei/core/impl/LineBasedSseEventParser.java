package me.piyaphat.kei.core.impl;

import me.piyaphat.kei.core.api.SseEvent;
import me.piyaphat.kei.core.api.SseEventParser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class LineBasedSseEventParser implements SseEventParser {
    private final List<String> data = new ArrayList<>();
    private String event = "";
    private String id;
    private boolean dataSet;

    @Override
    public void onLine(String line, Consumer<SseEvent> sink) {
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        if (line.isEmpty()) {
            if (dataSet) sink.accept(new SseEvent(
                    event.isEmpty() ? "message" : event, String.join("\n", data), id));
            data.clear();
            event = "";
            dataSet = false;
            return;
        }
        if (line.startsWith(":")) return;
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        switch (field) {
            case "data" -> {
                data.add(value);
                dataSet = true;
            }
            case "event" -> event = value;
            case "id" -> {
                if (value.indexOf('\0') < 0) id = value;
            }
        }
    }

    @Override
    public void reset() {
        data.clear();
        event = "";
        id = null;
        dataSet = false;
    }
}
