package com.malbers.fixmyrips.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class Id3TagsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** An MP3: ID3v2.{major} with TIT2, the given TRCK/TPOS (null = absent), an optional cover, padding; audio; an ID3v1 tail. */
    static File mp3(File f, int major, String trck, String tpos, int coverBytes, int padding) throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        byte[] t = Id3Tags.frame(major, "TIT2", "Song");
        frames.write(t, 0, t.length);
        if (trck != null) { byte[] x = Id3Tags.frame(major, "TRCK", trck); frames.write(x, 0, x.length); }
        if (tpos != null) { byte[] x = Id3Tags.frame(major, "TPOS", tpos); frames.write(x, 0, x.length); }
        if (coverBytes > 0) {
            byte[] body = new byte[coverBytes];
            new Random(3).nextBytes(body);
            body[0] = 0;
            byte[] x = rawFrame(major, major == 2 ? "PIC" : "APIC", body);
            frames.write(x, 0, x.length);
        }
        int size = frames.size() + padding;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {'I', 'D', '3', (byte) major, 0, 0, (byte) (size >> 21 & 0x7f), (byte) (size >> 14 & 0x7f), (byte) (size >> 7 & 0x7f), (byte) (size & 0x7f)});
        out.write(frames.toByteArray());
        out.write(new byte[padding]);
        byte[] audio = new byte[80_000];
        new Random(f.getName().hashCode()).nextBytes(audio);
        audio[0] = (byte) 0xFF; audio[1] = (byte) 0xFB;
        out.write(audio);
        byte[] v1 = new byte[128];
        v1[0] = 'T'; v1[1] = 'A'; v1[2] = 'G'; v1[126] = 1; // ID3v1.1 track byte
        out.write(v1);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), out.toByteArray());
        return f;
    }

    private static byte[] rawFrame(int major, String id, byte[] body) {
        int len = body.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (major == 2) {
            b.write(id.getBytes(StandardCharsets.ISO_8859_1), 0, 3);
            b.write(len >> 16 & 0xff); b.write(len >> 8 & 0xff); b.write(len & 0xff);
            b.write(body, 0, body.length);
            return b.toByteArray();
        }
        b.write(id.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        if (major == 4) { b.write(len >> 21 & 0x7f); b.write(len >> 14 & 0x7f); b.write(len >> 7 & 0x7f); b.write(len & 0x7f); }
        else { b.write(len >> 24 & 0xff); b.write(len >> 16 & 0xff); b.write(len >> 8 & 0xff); b.write(len & 0xff); }
        b.write(0); b.write(0);
        b.write(body, 0, body.length);
        return b.toByteArray();
    }

    private static String audio(File f) throws IOException {
        return FlacTags.audioSha256(f, Id3Tags.parse(f).audioOffset);
    }

    @Test
    public void readsTrackAndDiscAsSeparateLines() throws IOException {
        File f = mp3(new File(tmp.getRoot(), "01.mp3"), 3, "3/17", "1/2", 0, 256);
        assertEquals(Arrays.asList("TRACKNUMBER=3", "TRACKTOTAL=17", "DISCNUMBER=1", "DISCTOTAL=2"), Id3Tags.comments(f));
    }

    @Test
    public void smallChangeFitsThePaddingAndKeepsTheCoverAndAudio() throws IOException {
        for (int major : new int[] {3, 4}) {
            File f = mp3(new File(tmp.getRoot(), "v" + major + ".mp3"), major, "0", null, 60_000, 512);
            long len = f.length();
            String before = audio(f);
            Id3Tags.Frame cover = Id3Tags.parse(f).get("APIC");

            FlacTags.Written w = Id3Tags.write(f, Arrays.asList("TRACKNUMBER=1", "TRACKTOTAL=12", "DISCNUMBER=1", "DISCTOTAL=2"));

            assertTrue(w.inPlace);
            assertEquals(len, f.length());
            assertEquals(before, audio(f));
            assertArrayEquals(cover.raw, Id3Tags.parse(f).get("APIC").raw);
            assertEquals(Arrays.asList("TRACKNUMBER=1", "TRACKTOTAL=12", "DISCNUMBER=1", "DISCTOTAL=2"), Id3Tags.comments(f));
            TrackInfo ti = TagReader.readTrack(f);
            assertEquals(Integer.valueOf(1), ti.track);
            assertEquals(Integer.valueOf(12), ti.trackTotal);
        }
    }

    @Test
    public void oldItunesV22TagsAreReadWrittenAndUndone() throws IOException {
        File f = mp3(new File(tmp.getRoot(), "old.mp3"), 2, "0/12", null, 30_000, 256);
        byte[] original = Files.readAllBytes(f.toPath());
        assertEquals(Arrays.asList("TRACKNUMBER=0", "TRACKTOTAL=12"), Id3Tags.comments(f));
        assertEquals(Integer.valueOf(0), TagReader.readTrack(f).track);

        FlacTags.Written w = Id3Tags.write(f, Arrays.asList("TRACKNUMBER=1", "TRACKTOTAL=12", "DISCNUMBER=1"));

        assertTrue(w.inPlace);
        assertEquals(Arrays.asList("TRACKNUMBER=1", "TRACKTOTAL=12", "DISCNUMBER=1"), Id3Tags.comments(f));
        assertEquals(Integer.valueOf(1), TagReader.readTrack(f).track);
        assertEquals(2, Files.readAllBytes(f.toPath())[3]); // still v2.2
        Id3Tags.undo(f, w.originalHeader, w.audioSha256);
        assertArrayEquals(original, Files.readAllBytes(f.toPath()));
    }

    @Test
    public void noRoomRebuildsAndNoTagAtAllGetsOne() throws IOException {
        File tight = mp3(new File(tmp.getRoot(), "tight.mp3"), 3, "0", null, 0, 0);
        String before = audio(tight);
        assertFalse(Id3Tags.write(tight, Arrays.asList("TRACKNUMBER=1", "TRACKTOTAL=12")).inPlace);
        assertEquals(before, audio(tight));
        assertEquals("1/12", Id3Tags.text(Id3Tags.parse(tight).get("TRCK")));

        File bare = new File(tmp.getRoot(), "bare.mp3");
        byte[] audio = new byte[50_000];
        new Random(9).nextBytes(audio);
        Files.write(bare.toPath(), audio);
        byte[] original = Files.readAllBytes(bare.toPath());
        FlacTags.Written w = Id3Tags.write(bare, Arrays.asList("TRACKNUMBER=4"));
        assertTrue(Id3Tags.parse(bare).present);
        assertEquals("4", Id3Tags.text(Id3Tags.parse(bare).get("TRCK")));
        Id3Tags.undo(bare, w.originalHeader, w.audioSha256);
        assertArrayEquals(original, Files.readAllBytes(bare.toPath()));
    }

    @Test
    public void refusesTagsItCannotRewriteSafely() throws IOException {
        File f = mp3(new File(tmp.getRoot(), "u.mp3"), 3, "1", null, 0, 64);
        byte[] d = Files.readAllBytes(f.toPath());
        d[5] = (byte) 0x80; // unsynchronised
        Files.write(f.toPath(), d);
        try {
            Id3Tags.write(f, Arrays.asList("TRACKNUMBER=2"));
            fail();
        } catch (FlacTags.NotEditable expected) {
            assertArrayEquals(d, Files.readAllBytes(f.toPath()));
        }
        File old = mp3(new File(tmp.getRoot(), "v2.mp3"), 3, "1", null, 0, 64);
        byte[] o = Files.readAllBytes(old.toPath());
        o[3] = 5; // an ID3 version that doesn't exist
        Files.write(old.toPath(), o);
        try {
            Id3Tags.comments(old);
            fail();
        } catch (FlacTags.NotEditable expected) {
            // left alone
        }
    }

    @Test
    public void mp3AlbumFixesAndUndoesThroughThePlannerWithCompactRecords() throws IOException {
        File parent = tmp.newFolder("The Beatles");
        File dir = new File(parent, "1967-1970 (Disc 1)");
        mp3(new File(dir, "1-01 Strawberry Fields Forever.mp3"), 3, "0", null, 40_000, 1024);
        mp3(new File(dir, "1-02 Penny Lane.mp3"), 3, "1", null, 40_000, 1024);
        mp3(new File(new File(parent, "1967-1970 (Disc 2)"), "2-01 Hey Jude.mp3"), 3, "1", "2", 0, 1024);
        File[] files = dir.listFiles();
        Arrays.sort(files);
        List<byte[]> before = new ArrayList<>();
        for (File f : files) before.add(Files.readAllBytes(f.toPath()));
        Album a = new Album(dir, 2, files[0], null, false);

        FixPlanner.Plan p = FixPlanner.plan(a, null);
        assertTrue(p.notes.toString(), p.fixable());
        File rec = Fixer.fix(p, tmp.newFolder("undo"), 9L);

        assertEquals(Arrays.asList("TRACKNUMBER=1", "DISCNUMBER=1", "DISCTOTAL=2"), Id3Tags.comments(files[0]));
        assertEquals(Arrays.asList("TRACKNUMBER=2", "DISCNUMBER=1", "DISCTOTAL=2"), Id3Tags.comments(files[1]));
        assertTrue("record should not carry the covers: " + rec.length(), rec.length() < 6_000);

        Fixer.undo(rec);
        for (int i = 0; i < files.length; i++) assertArrayEquals(before.get(i), Files.readAllBytes(files[i].toPath()));
    }

    @Test
    public void otherFormatsAreStillLeftAlone() throws IOException {
        File dir = tmp.newFolder("Odd");
        Files.write(new File(dir, "01.m4a").toPath(), new byte[100]);
        FixPlanner.Plan p = FixPlanner.plan(new Album(dir, 1, null, null, false), null);
        assertFalse(p.fixable());
        assertTrue(p.notes.get(0).contains("only FLAC and MP3"));
    }
}
