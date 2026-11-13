package me.piyaphat.kei.core.impl;

import me.piyaphat.kei.core.api.ArchiveImage;
import me.piyaphat.kei.core.api.ImagePaths;
import me.piyaphat.kei.core.api.NaturalOrderComparator;
import me.piyaphat.kei.core.api.PageArchiveReader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ZipPageArchiveReader implements PageArchiveReader {
    @Override
    public List<ArchiveImage> extract(InputStream zip, File outDir, long maxEntryBytes)
            throws IOException {
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("could not create output directory");
        }
        List<ArchiveImage> images = new ArrayList<>();
        byte[] buffer = new byte[64 * 1024];
        int index = 0;
        try (ZipInputStream input = new ZipInputStream(zip)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = ImagePaths.normalize(entry.getName());
                String imagePath = name.replaceAll("(?:^|/)\\.\\.(?=/)", "");
                if (entry.isDirectory() || !ImagePaths.isImagePath(imagePath)) continue;
                File output = new File(outDir, String.format("%05d.%s",
                        index++, ImagePaths.extension(name)));
                long size = 0;
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        size += read;
                        if (size > maxEntryBytes) throw new IOException("archive entry too large");
                        stream.write(buffer, 0, read);
                    }
                } catch (IOException e) {
                    output.delete();
                    throw e;
                }
                images.add(new ArchiveImage(entry.getName(), output, size));
            }
        }
        images.sort(Comparator.comparing(ArchiveImage::name, NaturalOrderComparator.INSTANCE));
        return images;
    }
}
