package me.piyaphat.kei.core.api;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface PageArchiveReader {
    List<ArchiveImage> extract(InputStream zip, File outDir, long maxEntryBytes) throws IOException;
}
