package com.malbers.fixmyrips.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Artist names (TPE1/TPE2) in MP3s. Every fixture is built here byte by byte in the ID3v2.3/2.4 layout:
 * a 10-byte tag header, frames, zero padding, then random bytes standing in for MPEG audio and an ID3v1 tail.
 */
public class Id3ArtistTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final int LATIN1 = 0, UTF16 = 1, UTF16BE = 2, UTF8 = 3;

    /** A text frame body: encoding byte, optional byte order mark (UTF16 only), text, optional terminator. */
    static byte[] body(int enc, boolean littleEndian, boolean terminated, String value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(enc);
        byte[] text;
        switch (enc) {
            case UTF16:
                b.write(littleEndian ? 0xFF : 0xFE);
                b.write(littleEndian ? 0xFE : 0xFF);
                text = value.getBytes(littleEndian ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE);
                break;
            case UTF16BE: text = value.getBytes(StandardCharsets.UTF_16BE); break;
            case UTF8: text = value.getBytes(StandardCharsets.UTF_8); break;
            default: text = value.getBytes(StandardCharsets.ISO_8859_1);
        }
        b.write(text, 0, text.length);
        if (terminated) {
            b.write(0);
            if (enc == UTF16 || enc == UTF16BE) b.write(0);
        }
        return b.toByteArray();
    }

    static byte[] frame(int major, String id, int flags1, int flags2, byte[] body) {
        int len = body.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(id.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        if (major == 4) { b.write(len >> 21 & 0x7f); b.write(len >> 14 & 0x7f); b.write(len >> 7 & 0x7f); b.write(len & 0x7f); }
        else { b.write(len >> 24 & 0xff); b.write(len >> 16 & 0xff); b.write(len >> 8 & 0xff); b.write(len & 0xff); }
        b.write(flags1);
        b.write(flags2);
        b.write(body, 0, body.length);
        return b.toByteArray();
    }

    static byte[] text(int major, String id, int enc, boolean le, boolean term, String value) {
        return frame(major, id, 0, 0, body(enc, le, term, value));
    }

    /** Frames every fixture carries besides the artist ones; none of them may change. */
    static List<byte[]> others(int major) {
        List<byte[]> l = new ArrayList<>();
        l.add(text(major, "TIT2", UTF16, true, true, "Yellow"));
        l.add(text(major, "TALB", LATIN1, false, true, "Parachutes"));
        l.add(text(major, "TRCK", UTF16, true, true, "5/10")); // UTF-16 on purpose: it must not be normalised
        byte[] comm = body(LATIN1, false, false, "engDescription\0A comment");
        l.add(frame(major, "COMM", 0, 0, comm));
        byte[] cover = new byte[6000]; // over Fixer.BY_FINGERPRINT, so the undo record keeps it by hash
        new Random(7).nextBytes(cover);
        byte[] apic = new byte[cover.length + 14];
        apic[0] = 0;
        System.arraycopy("image/jpeg\0\3\0".getBytes(StandardCharsets.ISO_8859_1), 0, apic, 1, 13);
        System.arraycopy(cover, 0, apic, 14, cover.length);
        l.add(frame(major, "APIC", 0, 0, apic));
        return l;
    }

    static File mp3(File f, int major, List<byte[]> frames, int padding) throws IOException {
        ByteArrayOutputStream fr = new ByteArrayOutputStream();
        for (byte[] x : frames) fr.write(x);
        int size = fr.size() + padding;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {'I', 'D', '3', (byte) major, 0, 0, (byte) (size >> 21 & 0x7f), (byte) (size >> 14 & 0x7f), (byte) (size >> 7 & 0x7f), (byte) (size & 0x7f)});
        out.write(fr.toByteArray());
        out.write(new byte[padding]);
        byte[] audio = new byte[40_000];
        new Random(f.getName().hashCode()).nextBytes(audio);
        audio[0] = (byte) 0xFF; audio[1] = (byte) 0xFB;
        out.write(audio);
        byte[] v1 = new byte[128];
        v1[0] = 'T'; v1[1] = 'A'; v1[2] = 'G';
        out.write(v1);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), out.toByteArray());
        return f;
    }

    /** others() with the given artist frames after TALB, as taggers usually order them. */
    static List<byte[]> with(int major, byte[]... artistFrames) {
        List<byte[]> l = others(major);
        l.addAll(2, Arrays.asList(artistFrames));
        return l;
    }

    private static String audio(File f) throws IOException {
        return FlacTags.audioSha256(f, Id3Tags.parse(f).audioOffset);
    }

    /** The file's raw frames other than {@code skip}, in order. */
    private static List<byte[]> rawExcept(File f, String... skip) throws IOException {
        List<byte[]> out = new ArrayList<>();
        for (Id3Tags.Frame fr : Id3Tags.parse(f).frames) if (!Arrays.asList(skip).contains(fr.id)) out.add(fr.raw);
        return out;
    }

    private static void assertSameFrames(List<byte[]> want, List<byte[]> got) {
        assertEquals(want.size(), got.size());
        for (int i = 0; i < want.size(); i++) assertArrayEquals("frame " + i, want.get(i), got.get(i));
    }

    private static List<String> rename(File f, String from, String to) throws IOException {
        List<String> c = Id3Tags.comments(f);
        for (String key : Id3Tags.ARTIST_KEYS) {
            String v = FlacTags.get(c, key);
            if (v != null && v.equals(from)) c = FlacTags.set(c, key, to);
        }
        return c;
    }

    // ---- encodings and versions ----

    @Test
    public void tpe1IsRewrittenInItsOwnEncodingForEveryVersionAndEncoding() throws IOException {
        int[][] cases = { // major, encoding, little endian (UTF16 only), terminated
                {3, LATIN1, 0, 1}, {3, LATIN1, 0, 0}, {3, UTF16, 1, 1}, {3, UTF16, 0, 1}, {3, UTF16, 1, 0},
                {4, LATIN1, 0, 0}, {4, UTF16, 1, 1}, {4, UTF16, 0, 0}, {4, UTF16BE, 0, 1}, {4, UTF8, 0, 1}, {4, UTF8, 0, 0}};
        for (int[] c : cases) {
            int major = c[0], enc = c[1];
            boolean le = c[2] == 1, term = c[3] == 1;
            String label = Arrays.toString(c);
            File f = mp3(new File(tmp.getRoot(), "v" + label + ".mp3"), major,
                    with(major, text(major, "TPE1", enc, le, term, "Cold play")), 256);
            long length = f.length();
            String sound = audio(f);
            List<byte[]> others = rawExcept(f, "TPE1");
            assertEquals(label, "Cold play", FlacTags.get(Id3Tags.comments(f), "ARTIST"));

            FlacTags.Written w = Id3Tags.write(f, rename(f, "Cold play", "Coldplay"));

            assertTrue(label, w.inPlace);
            assertEquals(label, length, f.length());
            assertEquals(label, sound, audio(f));
            assertArrayEquals(label, text(major, "TPE1", enc, le, term, "Coldplay"), Id3Tags.parse(f).get("TPE1").raw);
            assertSameFrames(others, rawExcept(f, "TPE1"));
            assertEquals(label, "Coldplay", TagReader.read(f).artist);
        }
    }

    @Test
    public void nonAsciiNamesSurviveEachUnicodeEncoding() throws IOException {
        for (int enc : new int[] {UTF16, UTF16BE, UTF8}) {
            File f = mp3(new File(tmp.getRoot(), "u" + enc + ".mp3"), 4, with(4, text(4, "TPE1", enc, true, true, "Motorhead")), 128);
            Id3Tags.write(f, rename(f, "Motorhead", "Motörhead 東京"));
            assertArrayEquals(text(4, "TPE1", enc, true, true, "Motörhead 東京"), Id3Tags.parse(f).get("TPE1").raw);
            assertEquals("Motörhead 東京", TagReader.read(f).artist);
        }
    }

    @Test
    public void latin1FrameBecomesUtf16OnlyWhenTheNewNameNeedsIt() throws IOException {
        File f = mp3(new File(tmp.getRoot(), "l.mp3"), 3, with(3, text(3, "TPE1", LATIN1, false, true, "Bjork")), 128);
        Id3Tags.write(f, rename(f, "Bjork", "Björk")); // ö is in Latin-1: the encoding stays
        assertArrayEquals(text(3, "TPE1", LATIN1, false, true, "Björk"), Id3Tags.parse(f).get("TPE1").raw);
        Id3Tags.write(f, rename(f, "Björk", "Björk 東京")); // not in Latin-1: UTF-16 with a BOM, terminator kept
        assertArrayEquals(text(3, "TPE1", UTF16, true, true, "Björk 東京"), Id3Tags.parse(f).get("TPE1").raw);
        assertEquals("Björk 東京", TagReader.read(f).artist);
    }

    // ---- which frames ----

    @Test
    public void tpe1OnlyTpe2OnlyAndBoth() throws IOException {
        for (int major : new int[] {3, 4}) {
            File one = mp3(new File(tmp.getRoot(), major + "a.mp3"), major, with(major, text(major, "TPE1", LATIN1, false, true, "Beatles")), 64);
            Id3Tags.write(one, rename(one, "Beatles", "The Beatles"));
            assertEquals("The Beatles", FlacTags.get(Id3Tags.comments(one), "ARTIST"));
            assertNull(Id3Tags.parse(one).get("TPE2")); // nothing added

            File two = mp3(new File(tmp.getRoot(), major + "b.mp3"), major, with(major, text(major, "TPE2", UTF16, true, true, "Beatles")), 64);
            Id3Tags.write(two, rename(two, "Beatles", "The Beatles"));
            assertEquals("The Beatles", FlacTags.get(Id3Tags.comments(two), "ALBUMARTIST"));
            assertNull(Id3Tags.parse(two).get("TPE1"));
            assertEquals("The Beatles", TagReader.read(two).albumArtist);

            File both = mp3(new File(tmp.getRoot(), major + "c.mp3"), major, with(major,
                    text(major, "TPE1", UTF16, false, true, "Beatles"), text(major, "TPE2", LATIN1, false, false, "Beatles")), 64);
            List<byte[]> others = rawExcept(both, "TPE1", "TPE2");
            Id3Tags.write(both, rename(both, "Beatles", "The Beatles"));
            assertArrayEquals(text(major, "TPE1", UTF16, false, true, "The Beatles"), Id3Tags.parse(both).get("TPE1").raw);
            assertArrayEquals(text(major, "TPE2", LATIN1, false, false, "The Beatles"), Id3Tags.parse(both).get("TPE2").raw);
            assertSameFrames(others, rawExcept(both, "TPE1", "TPE2"));
        }
    }

    @Test
    public void onlyTheFrameWhoseValueChangesIsRewritten() throws IOException {
        File f = mp3(new File(tmp.getRoot(), "g.mp3"), 4, with(4,
                text(4, "TPE1", UTF8, false, true, "Beatles feat. Billy Preston"), text(4, "TPE2", UTF8, false, true, "Beatles")), 64);
        byte[] tpe1 = Id3Tags.parse(f).get("TPE1").raw;
        Id3Tags.write(f, rename(f, "Beatles", "The Beatles"));
        assertArrayEquals(tpe1, Id3Tags.parse(f).get("TPE1").raw);
        assertEquals("The Beatles", FlacTags.get(Id3Tags.comments(f), "ALBUMARTIST"));
    }

    @Test
    public void severalValuesInOneFrameAreNeverTakenForOneName() throws IOException {
        File dir = new File(tmp.getRoot(), "Multi");
        mp3(new File(dir, "01.mp3"), 4, with(4, text(4, "TPE1", UTF8, false, true, "Beatles\0Billy Preston")), 64);
        Album a = new Album(dir, 1, new File(dir, "01.mp3"), null, false);
        assertTrue(ArtistNames.changeable(a));
        assertTrue(ArtistNames.plan(a, "Beatles", "The Beatles").changes.isEmpty());
    }

    @Test
    public void unchangedTrackAndDiscFramesStayAsTheyWereOnATrackFix() throws IOException {
        // A track fix rewrites TRCK only; the UTF-16 TPOS and the artist frames keep their bytes.
        File f = mp3(new File(tmp.getRoot(), "t.mp3"), 3, Arrays.asList(
                text(3, "TRCK", LATIN1, false, false, "0"), text(3, "TPOS", UTF16, true, true, "1/2"),
                text(3, "TPE1", UTF16, true, true, "Coldplay")), 64);
        List<byte[]> keep = rawExcept(f, "TRCK");
        Id3Tags.write(f, FlacTags.set(Id3Tags.comments(f), "TRACKNUMBER", "1"));
        assertEquals("1", Id3Tags.text(Id3Tags.parse(f).get("TRCK")));
        assertSameFrames(keep, rawExcept(f, "TRCK"));
    }

    // ---- size and padding ----

    @Test
    public void longerAndShorterNamesWithAndWithoutPadding() throws IOException {
        for (int major : new int[] {3, 4}) {
            for (int padding : new int[] {0, 512}) {
                for (String to : new String[] {"The Beatles and Friends", "Btls"}) {
                    File f = mp3(new File(tmp.getRoot(), major + "-" + padding + "-" + to + ".mp3"), major,
                            with(major, text(major, "TPE1", UTF16, true, true, "Beatles")), padding);
                    long length = f.length();
                    long audioAt = Id3Tags.parse(f).audioOffset;
                    String sound = audio(f);
                    List<byte[]> others = rawExcept(f, "TPE1");
                    byte[] original = Files.readAllBytes(f.toPath());

                    FlacTags.Written w = Id3Tags.write(f, rename(f, "Beatles", to));

                    String label = major + "/" + padding + "/" + to;
                    boolean grows = to.length() > "Beatles".length();
                    boolean fits = !grows || padding > 0;
                    assertEquals(label, fits, w.inPlace);
                    if (fits) {
                        assertEquals(label, length, f.length());
                        assertEquals(label, audioAt, Id3Tags.parse(f).audioOffset);
                    } else {
                        // Rebuilt with fresh padding, so the next edit fits in place.
                        assertEquals(label, audioAt + 2 * (to.length() - "Beatles".length()) + Id3Tags.NEW_PADDING, Id3Tags.parse(f).audioOffset);
                    }
                    assertEquals(label, sound, audio(f));
                    assertEquals(label, to, TagReader.read(f).artist);
                    assertSameFrames(others, rawExcept(f, "TPE1"));

                    Id3Tags.undo(f, w.originalHeader, w.audioSha256);
                    assertArrayEquals(label, original, Files.readAllBytes(f.toPath()));
                }
            }
        }
    }

    // ---- undo, through the app's own path ----

    @Test
    public void albumFixesAndUndoesToTheOriginalBytesThroughFixer() throws IOException {
        File dir = new File(tmp.getRoot(), "Cold Play/Parachutes");
        File a = mp3(new File(dir, "01 Don't Panic.mp3"), 3, with(3,
                text(3, "TPE1", UTF16, true, true, "Cold Play"), text(3, "TPE2", UTF16, true, true, "Cold Play")), 1024);
        File b = mp3(new File(dir, "02 Shiver.mp3"), 4, with(4,
                text(4, "TPE1", UTF8, false, false, "Cold Play"), text(4, "TPE2", LATIN1, false, true, "Cold Play")), 0);
        byte[] beforeA = Files.readAllBytes(a.toPath());
        byte[] beforeB = Files.readAllBytes(b.toPath());
        Album album = new Album(dir, 2, a, null, false);

        FixPlanner.Plan p = ArtistNames.plan(album, "Cold Play", "Coldplay");
        assertEquals(4, p.changes.size());
        assertEquals("01 Don't Panic.mp3: ARTIST Cold Play → Coldplay", p.changes.get(0).toString()); // the preview line
        File rec = Fixer.fix(p, tmp.newFolder("undo"), 5L);

        for (File f : new File[] {a, b}) {
            assertEquals("Coldplay", TagReader.read(f).artist);
            assertEquals("Coldplay", TagReader.read(f).albumArtist);
        }
        assertTrue("the record keeps the cover by fingerprint: " + rec.length(), rec.length() < 4_000);

        Fixer.undo(rec);
        assertArrayEquals(beforeA, Files.readAllBytes(a.toPath()));
        assertArrayEquals(beforeB, Files.readAllBytes(b.toPath()));
        assertFalse(rec.exists());
    }

    // ---- refused before anything is written ----

    @Test
    public void framesThatCantBeRewrittenSafelyAreRefusedUntouched() throws IOException {
        int[][] flags = {{0, 0x80}, {0x20, 0}}; // v2.3: compressed; read-only
        for (int[] fl : flags) {
            File f = mp3(new File(tmp.getRoot(), "r" + fl[0] + fl[1] + ".mp3"), 3,
                    with(3, frame(3, "TPE1", fl[0], fl[1], body(LATIN1, false, true, "Beatles"))), 64);
            assertNotNull(Id3Tags.whyArtistUnchangeable(f));
            byte[] d = Files.readAllBytes(f.toPath());
            List<String> c = FlacTags.set(Id3Tags.comments(f), "ARTIST", "The Beatles");
            try {
                Id3Tags.write(f, c);
                fail();
            } catch (FlacTags.NotEditable expected) {
                assertArrayEquals(d, Files.readAllBytes(f.toPath()));
            }
        }
        File twice = mp3(new File(tmp.getRoot(), "twice.mp3"), 4, with(4,
                text(4, "TPE1", UTF8, false, true, "Beatles"), text(4, "TPE1", UTF8, false, true, "Beatles")), 64);
        assertEquals("more than one TPE1 frame", Id3Tags.whyArtistUnchangeable(twice));
    }

    // ---- M1: what the chooser shows ----

    private Album album(String path, int major, String artist) throws IOException {
        File dir = new File(tmp.getRoot(), path);
        File first = null;
        for (int i = 1; i <= 2; i++) {
            File f = new File(dir, "0" + i + ".mp3");
            if (major == 0) { // no ID3v2 tag at all: audio only
                dir.mkdirs();
                Files.write(f.toPath(), new byte[2000]);
            } else if (major == 2) {
                Id3TagsTest.mp3(f, 2, "1", null, 0, 64);
            } else {
                mp3(f, major, with(major, text(major, "TPE1", LATIN1, false, true, artist)), 64);
            }
            if (first == null) first = f;
        }
        return new Album(dir, 2, first, null, false);
    }

    @Test
    public void theAllMp3ColdplayLibraryCanNowBeChanged() throws IOException {
        // The 1.0.2 report: Coldplay / Cold Play / Cold play, 4 albums, all MP3, and "Change 0 albums".
        Map<Album, String> artist = new LinkedHashMap<>();
        artist.put(album("Coldplay/Parachutes", 3, "Coldplay"), "Coldplay");
        artist.put(album("Coldplay/X&Y", 4, "Coldplay"), "Coldplay");
        artist.put(album("Cold Play/Viva", 3, "Cold Play"), "Cold Play");
        artist.put(album("Cold play/Ghost Stories", 4, "Cold play"), "Cold play");
        List<ArtistNames.Group> groups = ArtistNames.find(artist);
        assertEquals(1, groups.size());
        ArtistNames.Group g = groups.get(0);
        assertEquals(3, g.spellings.size());
        assertTrue(g.canChange());
        assertEquals(ArtistNames.CHOOSE, g.status());
        assertEquals("Change 2 albums", g.buttonLabel(g.majority()));
        assertEquals("Change 3 albums", g.buttonLabel(g.spellings.get(1)));
    }

    @Test
    public void unchangeableMp3sSayCantChangeYetInsteadOfOfferingAButton() throws IOException {
        Album bare = album("Beatles/Help", 0, null);
        Album old = album("The Beatles/Revolver", 2, null);
        assertFalse(ArtistNames.changeable(bare));
        assertFalse(ArtistNames.changeable(old));
        assertTrue(ArtistNames.plan(bare, "Beatles", "The Beatles").notes.get(0).contains("no ID3v2 tag"));
        assertTrue(ArtistNames.plan(old, "The Beatles", "Beatles").notes.get(0).contains("ID3v2.2"));

        Map<Album, String> artist = new LinkedHashMap<>();
        artist.put(bare, "Beatles");
        artist.put(old, "The Beatles");
        ArtistNames.Group g = ArtistNames.find(artist).get(0);
        assertFalse(g.canChange());
        assertEquals("MP3, can't change yet", g.status());
        for (ArtistNames.Spelling keep : g.spellings) assertEquals("MP3, can't change yet", g.buttonLabel(keep));

        // One changeable album and one not: keeping the changeable spelling would change nothing.
        Map<Album, String> mixed = new LinkedHashMap<>();
        Album good = album("The Who/Tommy", 3, "The Who");
        mixed.put(good, "The Who");
        mixed.put(album("Who/Live", 0, null), "Who");
        ArtistNames.Group m = ArtistNames.find(mixed).get(0);
        assertEquals(ArtistNames.CHOOSE, m.status());
        ArtistNames.Spelling theWho = m.spellings.get(0).name.equals("The Who") ? m.spellings.get(0) : m.spellings.get(1);
        ArtistNames.Spelling who = theWho == m.spellings.get(0) ? m.spellings.get(1) : m.spellings.get(0);
        assertEquals("MP3, can't change yet", m.buttonLabel(theWho));
        assertEquals("Change 1 album", m.buttonLabel(who));
    }
}
