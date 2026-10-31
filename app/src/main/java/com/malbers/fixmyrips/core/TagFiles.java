package com.malbers.fixmyrips.core;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** One door for the two formats FixMyRips can fix: FLAC (Vorbis comments) and MP3 (ID3v2.3/2.4). */
public final class TagFiles {
    public static final String FLAC = "flac", ID3 = "id3";

    private TagFiles() {}

    /** "flac", "id3", or null for a format the app can't fix. */
    public static String format(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".flac")) return FLAC;
        if (n.endsWith(".mp3")) return ID3;
        return null;
    }

    public static List<String> comments(File f) throws IOException {
        return ID3.equals(format(f)) ? Id3Tags.comments(f) : FlacTags.read(f).comments;
    }

    public static FlacTags.Written snapshot(File f) throws IOException {
        return ID3.equals(format(f)) ? Id3Tags.snapshot(f) : FlacTags.snapshot(f);
    }

    public static FlacTags.Written write(File f, List<String> comments) throws IOException {
        return ID3.equals(format(f)) ? Id3Tags.write(f, comments) : FlacTags.write(f, comments);
    }

    public static void undo(File f, byte[] header, String audioSha256) throws IOException {
        if (ID3.equals(format(f))) Id3Tags.undo(f, header, audioSha256);
        else FlacTags.undo(f, header, audioSha256);
    }
}
