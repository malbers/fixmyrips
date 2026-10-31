package com.malbers.fixmyrips.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Writes small but real-shaped FLAC files for tests: STREAMINFO (3 minutes), tags, padding, fake audio. */
final class TestFlac {
    private TestFlac() {}

    static File write(File f, int padding, String... comments) throws IOException {
        List<Integer> types = new ArrayList<>();
        List<byte[]> blocks = new ArrayList<>();
        byte[] si = new byte[34];
        long samples = 44100L * 180;
        si[10] = 0x0A; si[11] = (byte) 0xC4; si[12] = 0x40; // 44100 Hz
        si[13] = (byte) (samples >> 32 & 0x0f);
        si[14] = (byte) (samples >> 24);
        si[15] = (byte) (samples >> 16);
        si[16] = (byte) (samples >> 8);
        si[17] = (byte) samples;
        types.add(FlacTags.STREAMINFO);
        blocks.add(si);
        types.add(FlacTags.VORBIS_COMMENT);
        blocks.add(FlacTags.buildComments("android-cdlib", Arrays.asList(comments)));
        types.add(FlacTags.PADDING);
        blocks.add(new byte[padding]);
        byte[] audio = new byte[100_000];
        new Random(f.getName().hashCode()).nextBytes(audio);
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(FlacTags.serialize(types, blocks));
            out.write(audio);
        }
        return f;
    }

    /** An album folder of {@code n} tracks named "01 Title.flac"..., tagged from {@code firstTag}. */
    static Album album(File dir, int n, int firstTag, String... extra) throws IOException {
        File first = null;
        for (int i = 1; i <= n; i++) {
            List<String> c = new ArrayList<>(Arrays.asList("ALBUM=Anthology", "ARTIST=Commodores", "TITLE=Song " + i,
                    "TRACKNUMBER=" + (i - 1 + firstTag)));
            c.addAll(Arrays.asList(extra));
            File f = write(new File(dir, String.format("%02d.Song %d.flac", i, i)), 1024, c.toArray(new String[0]));
            if (first == null) first = f;
        }
        return new Album(dir, n, first, null, false);
    }
}
