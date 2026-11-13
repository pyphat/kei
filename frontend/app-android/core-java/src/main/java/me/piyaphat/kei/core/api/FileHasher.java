package me.piyaphat.kei.core.api;

import java.io.IOException;
import java.io.InputStream;

public interface FileHasher {
    String sha256Hex(InputStream in) throws IOException;
}
