package dev.efeg.javakv.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FileUtil {

    private FileUtil() {
    }

    /** {@link FileChannel#write(ByteBuffer)} may write short; loop until the buffer is drained. */
    public static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /** Renames {@code tmp} onto {@code target} atomically, replacing it if present. */
    public static void moveAtomically(Path tmp, Path target) throws IOException {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
