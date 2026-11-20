package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.core.preview.PreviewArchive;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Entry point to {@link PreviewUploads} for the execution machine API. */
public final class PreviewUploadsAccess {

    private PreviewUploadsAccess() {}

    public static void extract(InputStream in, Path target, PreviewArchive.Limits limits) throws IOException {
        PreviewUploads.extract(in, target, limits);
    }

    public static boolean rejected(IOException e) {
        return e instanceof PreviewUploads.Rejected || e instanceof java.util.zip.ZipException;
    }

    public static void delete(Path dir) {
        PreviewUploads.delete(dir);
    }
}
