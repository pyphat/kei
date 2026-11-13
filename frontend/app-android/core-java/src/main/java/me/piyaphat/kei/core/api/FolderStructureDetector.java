package me.piyaphat.kei.core.api;

import java.util.List;

public interface FolderStructureDetector {
    DetectedLibrary detect(List<String> relativePaths);
}
