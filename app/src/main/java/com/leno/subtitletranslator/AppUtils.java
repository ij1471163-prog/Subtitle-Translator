package com.leno.subtitletranslator;

public class AppUtils {
    private static final int BASE = 0x2A;
    private static final long SEED = 0x1F3C7B9AL;

    static int getPart1() { return (int)(SEED >> 24) & 0xFF; }
    static int getPart2() { return BASE ^ 0x15; }
    static int getPart3() { return "leno".hashCode() & 0x3F; }
}