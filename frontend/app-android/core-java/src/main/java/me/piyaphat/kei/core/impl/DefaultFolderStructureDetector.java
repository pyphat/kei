package me.piyaphat.kei.core.impl;

import me.piyaphat.kei.core.api.DetectedChapter;
import me.piyaphat.kei.core.api.DetectedLibrary;
import me.piyaphat.kei.core.api.FolderStructureDetector;
import me.piyaphat.kei.core.api.ImagePaths;
import me.piyaphat.kei.core.api.NaturalOrderComparator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DefaultFolderStructureDetector implements FolderStructureDetector {
    private static final Pattern LAST_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)(?!.*\\d)");

    @Override
    public DetectedLibrary detect(List<String> relativePaths) {
        List<String> paths = relativePaths.stream().map(ImagePaths::normalize).toList();
        List<String> images = paths.stream().filter(ImagePaths::isImagePath).toList();
        if (images.isEmpty()) return null;
        String root = commonRoot(paths);
        Map<String, List<String>> byDir = new LinkedHashMap<>();
        for (String image : images) {
            String rel = image.substring(root.length());
            int slash = rel.indexOf('/');
            String dir = slash < 0 ? "" : rel.substring(0, slash);
            byDir.computeIfAbsent(dir, ignored -> new ArrayList<>()).add(rel);
        }
        List<String> dirs = new ArrayList<>(byDir.keySet());
        dirs.sort((a, b) -> a.isEmpty() ? (b.isEmpty() ? 0 : -1)
                : b.isEmpty() ? 1 : NaturalOrderComparator.INSTANCE.compare(a, b));
        List<DetectedChapter> chapters = new ArrayList<>();
        for (int i = 0; i < dirs.size(); i++) {
            String dir = dirs.get(i);
            double number = dir.isEmpty() ? (dirs.size() == 1 ? 1 : 0)
                    : chapterNumberFromName(dir).orElse(i + 1);
            List<String> files = byDir.get(dir);
            files.sort(NaturalOrderComparator.INSTANCE);
            chapters.add(new DetectedChapter(dir, number, dir,
                    files.stream().map(path -> root + path).toList()));
        }
        return new DetectedLibrary(root.isEmpty() ? null
                : root.substring(0, root.length() - 1), chapters);
    }

    private static String commonRoot(List<String> paths) {
        if (paths.isEmpty()) return "";
        String first = paths.get(0);
        int slash = first.indexOf('/');
        if (slash < 0) return "";
        String top = first.substring(0, slash + 1);
        return paths.stream().allMatch(path -> path.startsWith(top)) ? top : "";
    }

    private static java.util.OptionalDouble chapterNumberFromName(String name) {
        Matcher matcher = LAST_NUMBER.matcher(name);
        if (!matcher.find()) return java.util.OptionalDouble.empty();
        return java.util.OptionalDouble.of(Double.parseDouble(matcher.group(1)));
    }
}
