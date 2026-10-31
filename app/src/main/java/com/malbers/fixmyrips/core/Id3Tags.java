package com.malbers.fixmyrips.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The MP3 counterpart of {@link FlacTags}: reads and rewrites the ID3v2.2/2.3/2.4 tag at the start of an MP3.
 * Only the track (TRCK, TRK in v2.2) and disc (TPOS, TPA in v2.2) frames are ever rewritten; every other frame, cover art included,
 * is copied byte for byte. The audio after the tag (and any ID3v1 tag at the very end) is hashed before and
 * after, exactly as for FLAC.
 *
 * <p>The tag is presented to the planner as Vorbis-style lines: TRCK "3/17" reads as TRACKNUMBER=3 and
 * TRACKTOTAL=17, TPOS "1/2" as DISCNUMBER=1 and DISCTOTAL=2. Tags that can't be rewritten safely
 * (unsynchronised, extended header, footer, compressed or encrypted number frames) are refused
 * before anything is written.
 */
public final class Id3Tags {
    static final int NEW_PADDING = 2048;

    private Id3Tags() {}

    static final class Frame {
        final String id;
        /** Header and body, exactly as in the file. */
        final byte[] raw;
        final byte[] body;
        final int flags2;

        Frame(String id, byte[] raw, byte[] body, int flags2) {
            this.id = id;
            this.raw = raw;
            this.body = body;
            this.flags2 = flags2;
        }
    }

    static final class Tag {
        /** 3 or 4; 3 when the file has no tag yet. */
        final int major;
        /** Bytes of the tag body (frames + padding); 0 when there is no tag. */
        final int size;
        final List<Frame> frames;
        final long audioOffset;
        final boolean present;

        Tag(int major, int size, List<Frame> frames, long audioOffset, boolean present) {
            this.major = major;
            this.size = size;
            this.frames = frames;
            this.audioOffset = audioOffset;
            this.present = present;
        }

        Frame get(String id) {
            for (Frame f : frames) if (f.id.equals(id)) return f;
            return null;
        }
    }

    static Tag parse(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] h = new byte[10];
            int got = readFully(in, h);
            if (got < 10 || h[0] != 'I' || h[1] != 'D' || h[2] != '3') {
                return new Tag(3, 0, new ArrayList<>(), 0, false);
            }
            int major = h[3] & 0xff;
            int flags = h[5] & 0xff;
            if (major < 2 || major > 4) throw new FlacTags.NotEditable("unknown ID3v2." + major + " tag");
            if ((flags & 0x80) != 0) throw new FlacTags.NotEditable("unsynchronised ID3 tag");
            if ((flags & 0x40) != 0) throw new FlacTags.NotEditable(major == 2 ? "compressed ID3 tag" : "ID3 tag with an extended header");
            if ((flags & 0x10) != 0) throw new FlacTags.NotEditable("ID3 tag with a footer");
            int size = syncsafe(h, 6);
            byte[] body = new byte[size];
            if (readFully(in, body) < size) throw new FlacTags.NotEditable("file is truncated");
            List<Frame> frames = new ArrayList<>();
            int p = 0;
            int head = headerSize(major);
            while (p + head <= size && body[p] != 0) {
                String id = new String(body, p, head == 6 ? 3 : 4, StandardCharsets.ISO_8859_1);
                if (!id.matches("[A-Z0-9]{3,4}")) throw new FlacTags.NotEditable("damaged ID3 frame");
                int len = frameLength(major, body, p);
                if (len < 0 || p + head + len > size) throw new FlacTags.NotEditable("damaged ID3 frame");
                byte[] raw = java.util.Arrays.copyOfRange(body, p, p + head + len);
                byte[] b = java.util.Arrays.copyOfRange(body, p + head, p + head + len);
                frames.add(new Frame(canonical(major, id), raw, b, head == 6 ? 0 : body[p + 9] & 0xff));
                p += head + len;
            }
            for (int i = p; i < size; i++) if (body[i] != 0) throw new FlacTags.NotEditable("unexpected data after the ID3 frames");
            return new Tag(major, size, frames, 10L + size, true);
        }
    }

    /** The raw frames of a whole tag (header + body), as held in an undo record. */
    static List<byte[]> rawFrames(byte[] tag) {
        List<byte[]> out = new ArrayList<>();
        int major = tag[3] & 0xff;
        int head = headerSize(major);
        int p = 10;
        while (p + head <= tag.length && tag[p] != 0) {
            int len = frameLength(major, tag, p);
            if (len < 0 || p + head + len > tag.length) break;
            out.add(java.util.Arrays.copyOfRange(tag, p, p + head + len));
            p += head + len;
        }
        return out;
    }

    /** The track and disc numbers as Vorbis-style lines, in this order, only those present. */
    public static List<String> comments(File f) throws IOException {
        Tag t = parse(f);
        List<String> out = new ArrayList<>();
        split(out, text(t.get("TRCK")), "TRACKNUMBER", "TRACKTOTAL");
        split(out, text(t.get("TPOS")), "DISCNUMBER", "DISCTOTAL");
        return out;
    }

    public static FlacTags.Written snapshot(File f) throws IOException {
        Tag t = parse(f);
        return new FlacTags.Written(FlacTags.readRange(f, 0, t.audioOffset), FlacTags.audioSha256(f, t.audioOffset), false);
    }

    /** Writes TRCK and TPOS from {@code comments}; everything else in the tag stays as it was. */
    public static FlacTags.Written write(File f, List<String> comments) throws IOException {
        Tag t = parse(f);
        for (String id : new String[] {"TRCK", "TPOS"}) {
            Frame old = t.get(id);
            if (old != null && old.flags2 != 0) throw new FlacTags.NotEditable("compressed or encrypted " + id + " frame");
        }
        byte[] original = FlacTags.readRange(f, 0, t.audioOffset);
        String audioBefore = FlacTags.audioSha256(f, t.audioOffset);

        String trck = join(FlacTags.get(comments, "TRACKNUMBER"), FlacTags.get(comments, "TRACKTOTAL"));
        String tpos = join(FlacTags.get(comments, "DISCNUMBER"), FlacTags.get(comments, "DISCTOTAL"));
        List<byte[]> frames = new ArrayList<>();
        boolean sawTrck = false, sawTpos = false;
        for (Frame fr : t.frames) {
            if (fr.id.equals("TRCK")) {
                sawTrck = true;
                if (trck != null) frames.add(frame(t.major, "TRCK", trck));
            } else if (fr.id.equals("TPOS")) {
                sawTpos = true;
                if (tpos != null) frames.add(frame(t.major, "TPOS", tpos));
            } else {
                frames.add(fr.raw);
            }
        }
        if (!sawTrck && trck != null) frames.add(frame(t.major, "TRCK", trck));
        if (!sawTpos && tpos != null) frames.add(frame(t.major, "TPOS", tpos));
        int used = 0;
        for (byte[] fr : frames) used += fr.length;

        boolean inPlace = t.present && used <= t.size;
        int size = inPlace ? t.size : used + NEW_PADDING;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write('I'); b.write('D'); b.write('3');
        b.write(t.major); b.write(0); b.write(0);
        b.write(size >> 21 & 0x7f); b.write(size >> 14 & 0x7f); b.write(size >> 7 & 0x7f); b.write(size & 0x7f);
        for (byte[] fr : frames) b.write(fr, 0, fr.length);
        for (int i = used; i < size; i++) b.write(0);
        byte[] header = b.toByteArray();

        if (inPlace) {
            if (header.length != t.audioOffset) throw new IllegalStateException("tag size changed");
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.seek(0);
                raf.write(header);
                raf.getFD().sync();
            }
        } else {
            FlacTags.rebuild(f, header, t.audioOffset);
        }

        Tag after = parse(f);
        if (!FlacTags.audioSha256(f, after.audioOffset).equals(audioBefore)) {
            FlacTags.restore(f, original, after.audioOffset);
            throw new IOException("audio changed during the write; original tags restored");
        }
        return new FlacTags.Written(original, audioBefore, inPlace);
    }

    /** Puts the original tag back in front of the audio; refuses when the audio has changed since. */
    public static void undo(File f, byte[] originalHeader, String audioSha256) throws IOException {
        Tag t = parse(f);
        if (!FlacTags.audioSha256(f, t.audioOffset).equals(audioSha256)) throw new FlacTags.NotEditable("the file has changed since the fix");
        FlacTags.restore(f, originalHeader, t.audioOffset);
        if (!FlacTags.audioSha256(f, parse(f).audioOffset).equals(audioSha256)) throw new IOException("audio check failed after undo");
    }

    // ---- helpers ----

    /** A text frame holding plain ASCII digits (encoding 0). {@code id} is the v2.3 name; v2.2 gets its three-letter one. */
    static byte[] frame(int major, String id, String value) {
        byte[] text = value.getBytes(StandardCharsets.ISO_8859_1);
        int len = text.length + 1;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (major == 2) {
            String old = id.equals("TRCK") ? "TRK" : id.equals("TPOS") ? "TPA" : id.equals("TIT2") ? "TT2" : id.substring(0, 3);
            b.write(old.getBytes(StandardCharsets.ISO_8859_1), 0, 3);
            b.write(len >> 16 & 0xff); b.write(len >> 8 & 0xff); b.write(len & 0xff);
            b.write(0);
            b.write(text, 0, text.length);
            return b.toByteArray();
        }
        byte[] idb = id.getBytes(StandardCharsets.ISO_8859_1);
        b.write(idb, 0, 4);
        if (major == 4) {
            b.write(len >> 21 & 0x7f); b.write(len >> 14 & 0x7f); b.write(len >> 7 & 0x7f); b.write(len & 0x7f);
        } else {
            b.write(len >> 24 & 0xff); b.write(len >> 16 & 0xff); b.write(len >> 8 & 0xff); b.write(len & 0xff);
        }
        b.write(0); b.write(0);
        b.write(0);
        b.write(text, 0, text.length);
        return b.toByteArray();
    }

    /** The text of a text frame, or null. */
    static String text(Frame f) {
        if (f == null || f.body.length < 1) return null;
        int enc = f.body[0];
        java.nio.charset.Charset cs = enc == 1 ? StandardCharsets.UTF_16 : enc == 2 ? StandardCharsets.UTF_16BE
                : enc == 3 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        String s = new String(f.body, 1, f.body.length - 1, cs).replace("﻿", "");
        int end = s.indexOf('\u0000');
        return (end >= 0 ? s.substring(0, end) : s).trim();
    }

    private static void split(List<String> out, String v, String numKey, String totalKey) {
        if (v == null || v.isEmpty()) return;
        String[] parts = v.split("/", 2);
        if (!parts[0].trim().isEmpty()) out.add(numKey + "=" + parts[0].trim());
        if (parts.length > 1 && !parts[1].trim().isEmpty()) out.add(totalKey + "=" + parts[1].trim());
    }

    private static String join(String n, String total) {
        if (n == null) return null;
        String num = n.split("/")[0].trim();
        return total == null ? num : num + "/" + total.trim();
    }

    private static int headerSize(int major) {
        return major == 2 ? 6 : 10;
    }

    private static int frameLength(int major, byte[] d, int p) {
        if (major == 2) return (d[p + 3] & 0xff) << 16 | (d[p + 4] & 0xff) << 8 | (d[p + 5] & 0xff);
        return major == 4 ? syncsafe(d, p + 4) : be32(d, p + 4);
    }

    /** v2.2's TRK/TPA under their v2.3 names, so the rest of the class speaks one language. */
    private static String canonical(int major, String id) {
        if (major != 2) return id;
        return id.equals("TRK") ? "TRCK" : id.equals("TPA") ? "TPOS" : id;
    }

    static boolean isMp3(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".mp3");
    }

    private static int syncsafe(byte[] d, int p) {
        return (d[p] & 0x7f) << 21 | (d[p + 1] & 0x7f) << 14 | (d[p + 2] & 0x7f) << 7 | (d[p + 3] & 0x7f);
    }

    private static int be32(byte[] d, int p) {
        return (d[p] & 0xff) << 24 | (d[p + 1] & 0xff) << 16 | (d[p + 2] & 0xff) << 8 | (d[p + 3] & 0xff);
    }

    private static int readFully(InputStream in, byte[] d) throws IOException {
        int off = 0;
        while (off < d.length) {
            int r = in.read(d, off, d.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }
}
