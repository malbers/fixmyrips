package com.malbers.fixmyrips.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads and rewrites the Vorbis comment block (the tags) of a FLAC file. Only the metadata header changes;
 * the audio frames are hashed before and after every write, and a mismatch restores the original header.
 *
 * <p>Write strategy: when the PADDING block can absorb the size change, the header is rewritten in place
 * (same length, one write). Otherwise the file is rebuilt as {@code name.tmp} and renamed over the original.
 */
public final class FlacTags {
    static final int STREAMINFO = 0, PADDING = 1, VORBIS_COMMENT = 4;
    private static final int MAX_HEADER = 32 * 1024 * 1024;
    /** Padding added when a file has to be rebuilt, so the next edit fits in place. */
    static final int NEW_PADDING = 4096;

    private FlacTags() {}

    /** A FLAC file's metadata: its blocks in order and where the audio frames start. */
    public static final class Header {
        final List<Integer> types = new ArrayList<>(); // {type}
        final List<byte[]> blocks = new ArrayList<>();
        public final long audioOffset;
        public final String vendor;
        /** The tags as "KEY=value" lines, in file order. */
        public final List<String> comments;

        Header(long audioOffset, String vendor, List<String> comments) {
            this.audioOffset = audioOffset;
            this.vendor = vendor;
            this.comments = comments;
        }

        int indexOf(int type) {
            for (int i = 0; i < types.size(); i++) if ((int) types.get(i) == type) return i;
            return -1;
        }
    }

    /** Thrown when a file is not a FLAC we can safely edit. Nothing has been written when it is thrown. */
    public static final class NotEditable extends IOException {
        NotEditable(String why) { super(why); }
    }

    public static Header read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] magic = readN(in, 4);
            if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') throw new NotEditable("not a FLAC file");
            long pos = 4;
            List<Integer> types = new ArrayList<>();
            List<byte[]> blocks = new ArrayList<>();
            boolean last = false;
            while (!last) {
                byte[] h = readN(in, 4);
                last = (h[0] & 0x80) != 0;
                int type = h[0] & 0x7f;
                int len = (h[1] & 0xff) << 16 | (h[2] & 0xff) << 8 | (h[3] & 0xff);
                pos += 4 + len;
                if (pos > MAX_HEADER) throw new NotEditable("metadata too large");
                if (type == 127) throw new NotEditable("invalid metadata block");
                types.add(type);
                blocks.add(readN(in, len));
            }
            if (types.isEmpty() || (int) types.get(0) != STREAMINFO) throw new NotEditable("no STREAMINFO block");
            String vendor = "FixMyRips";
            List<String> comments = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                if ((int) types.get(i) == VORBIS_COMMENT) {
                    vendor = parseComments(blocks.get(i), comments);
                    break;
                }
            }
            Header hd = new Header(pos, vendor, Collections.unmodifiableList(comments));
            hd.types.addAll(types);
            hd.blocks.addAll(blocks);
            return hd;
        }
    }

    /** First value of {@code key} (case-insensitive), or null. */
    public static String get(List<String> comments, String key) {
        for (String c : comments) {
            int eq = c.indexOf('=');
            if (eq > 0 && c.substring(0, eq).equalsIgnoreCase(key)) return c.substring(eq + 1);
        }
        return null;
    }

    /**
     * Returns a copy of {@code comments} with {@code key} set to {@code value}: the first entry for the key is
     * replaced in place, any others are dropped; a missing key is appended. A null value removes the key.
     */
    public static List<String> set(List<String> comments, String key, String value) {
        List<String> out = new ArrayList<>();
        boolean done = false;
        for (String c : comments) {
            int eq = c.indexOf('=');
            if (eq > 0 && c.substring(0, eq).equalsIgnoreCase(key)) {
                if (!done && value != null) out.add(c.substring(0, eq) + "=" + value);
                done = true;
            } else {
                out.add(c);
            }
        }
        if (!done && value != null) out.add(key.toUpperCase(Locale.ROOT) + "=" + value);
        return out;
    }

    /** What a write did, for the undo journal. */
    public static final class Written {
        /** The file's metadata header (bytes 0 to the audio start) before the write. */
        public final byte[] originalHeader;
        public final String audioSha256;
        public final boolean inPlace;

        Written(byte[] originalHeader, String audioSha256, boolean inPlace) {
            this.originalHeader = originalHeader;
            this.audioSha256 = audioSha256;
            this.inPlace = inPlace;
        }
    }

    /** The file's current header and audio hash, taken before a write so the undo record exists first. */
    public static Written snapshot(File f) throws IOException {
        Header h = read(f);
        return new Written(readRange(f, 0, h.audioOffset), audioSha256(f, h.audioOffset), false);
    }

    /**
     * Replaces the file's tags with {@code comments}. The audio is verified unchanged afterwards; if it is not,
     * the original header is put back and an IOException is thrown.
     */
    public static Written write(File f, List<String> comments) throws IOException {
        Header h = read(f);
        byte[] original = readRange(f, 0, h.audioOffset);
        String audioBefore = audioSha256(f, h.audioOffset);

        byte[] newVorbis = buildComments(h.vendor, comments);
        List<Integer> types = new ArrayList<>(h.types);
        List<byte[]> blocks = new ArrayList<>(h.blocks);
        int vi = h.indexOf(VORBIS_COMMENT);
        if (vi >= 0) {
            blocks.set(vi, newVorbis);
        } else {
            types.add(1, VORBIS_COMMENT);
            blocks.add(1, newVorbis);
        }

        long oldMeta = h.audioOffset;
        long newMetaNoPad = 4;
        int pi = -1;
        for (int i = 0; i < types.size(); i++) {
            if ((int) types.get(i) == PADDING && pi < 0) { pi = i; continue; }
            newMetaNoPad += 4 + blocks.get(i).length;
        }
        boolean inPlace = false;
        if (pi >= 0) {
            long room = oldMeta - newMetaNoPad - 4; // what is left for the padding block's body
            if (room >= 0 && room <= 0xFFFFFF) {
                blocks.set(pi, new byte[(int) room]);
                inPlace = true;
            }
        } else if (newMetaNoPad == oldMeta) {
            inPlace = true;
        }

        if (inPlace) {
            byte[] meta = serialize(types, blocks);
            if (meta.length != oldMeta) throw new IllegalStateException("header size changed");
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.seek(0);
                raf.write(meta);
                raf.getFD().sync();
            }
        } else {
            if (pi >= 0) {
                blocks.set(pi, new byte[NEW_PADDING]);
            } else {
                types.add(PADDING);
                blocks.add(new byte[NEW_PADDING]);
            }
            rebuild(f, serialize(types, blocks), h.audioOffset);
        }

        Header after = read(f);
        String audioAfter = audioSha256(f, after.audioOffset);
        if (!audioAfter.equals(audioBefore)) {
            restore(f, original, after.audioOffset);
            throw new IOException("audio changed during the write; original tags restored");
        }
        return new Written(original, audioBefore, inPlace);
    }

    /**
     * Puts {@code originalHeader} back in front of the file's audio (undo). Refuses when the audio no longer
     * matches {@code audioSha256}, i.e. the file was replaced or re-ripped since the fix.
     */
    public static void undo(File f, byte[] originalHeader, String audioSha256) throws IOException {
        Header h = read(f);
        if (!audioSha256(f, h.audioOffset).equals(audioSha256)) throw new NotEditable("the file has changed since the fix");
        restore(f, originalHeader, h.audioOffset);
        Header after = read(f);
        if (!audioSha256(f, after.audioOffset).equals(audioSha256)) throw new IOException("audio check failed after undo");
    }

    private static void restore(File f, byte[] header, long currentAudioOffset) throws IOException {
        if (header.length == currentAudioOffset) {
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.seek(0);
                raf.write(header);
                raf.getFD().sync();
            }
        } else {
            rebuild(f, header, currentAudioOffset);
        }
    }

    /** Writes {@code header} + the audio from {@code audioOffset} to name.tmp, then renames it over the file. */
    private static void rebuild(File f, byte[] header, long audioOffset) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (InputStream in = new FileInputStream(f); FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(header);
            skipFully(in, audioOffset);
            copy(in, out);
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- encoding ----

    static byte[] buildComments(String vendor, List<String> comments) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] v = vendor.getBytes(StandardCharsets.UTF_8);
        le32(b, v.length);
        b.write(v, 0, v.length);
        le32(b, comments.size());
        for (String c : comments) {
            byte[] d = c.getBytes(StandardCharsets.UTF_8);
            le32(b, d.length);
            b.write(d, 0, d.length);
        }
        return b.toByteArray();
    }

    static byte[] serialize(List<Integer> types, List<byte[]> blocks) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write('f'); b.write('L'); b.write('a'); b.write('C');
        for (int i = 0; i < types.size(); i++) {
            byte[] d = blocks.get(i);
            if (d.length > 0xFFFFFF) throw new IllegalArgumentException("block too large");
            int head = (int) types.get(i) | (i == types.size() - 1 ? 0x80 : 0);
            b.write(head);
            b.write(d.length >> 16 & 0xff);
            b.write(d.length >> 8 & 0xff);
            b.write(d.length & 0xff);
            b.write(d, 0, d.length);
        }
        return b.toByteArray();
    }

    private static String parseComments(byte[] d, List<String> out) throws NotEditable {
        try {
            int p = 0;
            int vlen = le32(d, p);
            p += 4;
            String vendor = new String(d, p, vlen, StandardCharsets.UTF_8);
            p += vlen;
            int count = le32(d, p);
            p += 4;
            for (int i = 0; i < count; i++) {
                int len = le32(d, p);
                p += 4;
                if (len < 0 || p + len > d.length) throw new NotEditable("damaged tag block");
                out.add(new String(d, p, len, StandardCharsets.UTF_8));
                p += len;
            }
            return vendor;
        } catch (IndexOutOfBoundsException e) {
            throw new NotEditable("damaged tag block");
        }
    }

    // ---- io helpers ----

    public static String audioSha256(File f, long audioOffset) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = new FileInputStream(f)) {
            skipFully(in, audioOffset);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    static byte[] readRange(File f, long from, long to) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            skipFully(in, from);
            return readN(in, (int) (to - from));
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] d = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(d, off, n - off);
            if (r < 0) throw new NotEditable("file is truncated");
            off += r;
        }
        return d;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new NotEditable("file is truncated");
                s = 1;
            }
            n -= s;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static int le32(byte[] d, int p) {
        if (p + 4 > d.length) throw new IndexOutOfBoundsException();
        return (d[p] & 0xff) | (d[p + 1] & 0xff) << 8 | (d[p + 2] & 0xff) << 16 | (d[p + 3] & 0xff) << 24;
    }

    private static void le32(ByteArrayOutputStream b, int v) {
        b.write(v & 0xff);
        b.write(v >> 8 & 0xff);
        b.write(v >> 16 & 0xff);
        b.write(v >> 24 & 0xff);
    }
}
