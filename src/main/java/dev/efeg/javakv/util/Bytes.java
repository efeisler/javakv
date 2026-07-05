package dev.efeg.javakv.util;

import java.util.Arrays;
import java.util.Comparator;

/** Shared unsigned byte-array ordering used by every component that stores or looks up keys. */
public final class Bytes {

    public static final Comparator<byte[]> COMPARATOR = Arrays::compareUnsigned;

    private Bytes() {
    }
}
