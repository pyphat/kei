package me.piyaphat.kei.core.api;

import java.util.Locale;
import java.util.Set;

public final class ImagePaths {
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private ImagePaths() {}

    public static String normalize(String path) {
        return path.replace('\\', '/');
    }

    public static boolean isImagePath(String path) {
        String normalized = normalize(path);
        String base = normalized.substring(normalized.lastIndexOf('/') + 1);
        String lower = base.toLowerCase(Locale.ROOT);
        if (base.startsWith(".") || lower.equals("thumbs.db") || lower.equals("desktop.ini")) {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("__MACOSX") || segment.startsWith(".")) return false;
        }
        int dot = base.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(lower.substring(dot + 1));
    }

    public static String extension(String path) {
        String normalized = normalize(path);
        String base = normalized.substring(normalized.lastIndexOf('/') + 1);
        return base.substring(base.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
