package dev.efeg.javakv.util;

import java.util.zip.CRC32;

/** Shared checksum helper used by the WAL and (later) SSTable record formats. */
public final class Crc32Util {

    private Crc32Util() {
    }

    public static int compute(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }
}
