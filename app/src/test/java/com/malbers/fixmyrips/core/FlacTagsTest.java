package com.malbers.fixmyrips.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FlacTagsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** A minimal FLAC: STREAMINFO, optional tags, optional picture, optional padding, then fake audio bytes. */
    private File flac(List<String> comments, int padding, boolean picture) throws IOException {
        List<Integer> types = new ArrayList<>();
        List<byte[]> blocks = new ArrayList<>();
        byte[] si = new byte[34];
        si[10] = 0x0A; si[11] = (byte) 0xC4; si[12] = 0x40; // 44100 Hz
        si[17] = 100;
        types.add(FlacTags.STREAMINFO);
        blocks.add(si);
        if (comments != null) {
            types.add(FlacTags.VORBIS_COMMENT);
            blocks.add(FlacTags.buildComments("android-cdlib", comments));
        }
        if (picture) {
            types.add(6);
            // PICTURE: front cover, "image/jpeg", no description, 16 bytes of size info, 4 image bytes.
            blocks.add(new byte[] {0, 0, 0, 3, 0, 0, 0, 10, 'i', 'm', 'a', 'g', 'e', '/', 'j', 'p', 'e', 'g', 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        }
        if (padding >= 0) {
            types.add(FlacTags.PADDING);
            blocks.add(new byte[padding]);
        }
        File f = tmp.newFile();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(FlacTags.serialize(types, blocks));
            out.write(audio());
        }
        return f;
    }

    private static byte[] audio() {
        byte[] a = new byte[200_000];
        new Random(42).nextBytes(a);
        a[0] = (byte) 0xFF; a[1] = (byte) 0xF8; // frame sync
        return a;
    }

    private static List<String> tags(String... kv) {
        return new ArrayList<>(Arrays.asList(kv));
    }

    private static String audioHash(File f) throws IOException {
        return FlacTags.audioSha256(f, FlacTags.read(f).audioOffset);
    }

    @Test
    public void readsCommentsInOrder() throws IOException {
        File f = flac(tags("ALBUM=Anthology", "TRACKNUMBER=0", "ARTIST=Commodores"), 1024, false);
        FlacTags.Header h = FlacTags.read(f);
        assertEquals(Arrays.asList("ALBUM=Anthology", "TRACKNUMBER=0", "ARTIST=Commodores"), h.comments);
        assertEquals("android-cdlib", h.vendor);
        assertEquals("0", FlacTags.get(h.comments, "tracknumber"));
    }

    @Test
    public void setReplacesFirstDropsDuplicatesAppendsMissing() {
        List<String> c = tags("TRACKNUMBER=0", "ALBUM=A", "tracknumber=9");
        assertEquals(Arrays.asList("TRACKNUMBER=1", "ALBUM=A"), FlacTags.set(c, "TrackNumber", "1"));
        assertEquals(Arrays.asList("TRACKNUMBER=0", "ALBUM=A", "tracknumber=9", "TRACKTOTAL=21"), FlacTags.set(c, "tracktotal", "21"));
        assertEquals(Arrays.asList("ALBUM=A"), FlacTags.set(c, "TRACKNUMBER", null));
    }

    @Test
    public void smallChangeIsWrittenInPlaceAndAudioIsUntouched() throws IOException {
        File f = flac(tags("ALBUM=Anthology", "TRACKNUMBER=0"), 1024, true);
        long len = f.length();
        String before = audioHash(f);
        List<String> c = FlacTags.set(FlacTags.read(f).comments, "TRACKNUMBER", "1");
        c = FlacTags.set(c, "TRACKTOTAL", "21");

        FlacTags.Written w = FlacTags.write(f, c);

        assertTrue(w.inPlace);
        assertEquals(len, f.length());
        assertEquals(before, audioHash(f));
        assertEquals(Arrays.asList("ALBUM=Anthology", "TRACKNUMBER=1", "TRACKTOTAL=21"), FlacTags.read(f).comments);
        assertEquals(Integer.valueOf(1), TagReader.readTrack(f).track);
    }

    @Test
    public void growthBeyondPaddingRebuildsTheFileAndKeepsAudioAndPicture() throws IOException {
        File f = flac(tags("ALBUM=A"), 8, true);
        String before = audioHash(f);
        char[] big = new char[500];
        Arrays.fill(big, 'x');

        FlacTags.Written w = FlacTags.write(f, FlacTags.set(FlacTags.read(f).comments, "COMMENT", new String(big)));

        assertFalse(w.inPlace);
        assertEquals(before, audioHash(f));
        FlacTags.Header h = FlacTags.read(f);
        assertTrue(h.indexOf(6) > 0);
        assertEquals(FlacTags.NEW_PADDING, h.blocks.get(h.indexOf(FlacTags.PADDING)).length);
        assertFalse(new File(f.getPath() + ".tmp").exists());
    }

    @Test
    public void fileWithoutPaddingOrTagsGetsBoth() throws IOException {
        File f = flac(null, -1, false);
        String before = audioHash(f);

        FlacTags.write(f, tags("TRACKNUMBER=1"));

        FlacTags.Header h = FlacTags.read(f);
        assertEquals(Arrays.asList("TRACKNUMBER=1"), h.comments);
        assertEquals(1, h.indexOf(FlacTags.VORBIS_COMMENT));
        assertTrue(h.indexOf(FlacTags.PADDING) > 0);
        assertEquals(before, audioHash(f));
    }

    @Test
    public void undoRestoresTheExactOriginalBytes() throws IOException {
        File f = flac(tags("ALBUM=Anthology", "TRACKNUMBER=0"), 1024, true);
        byte[] original = Files.readAllBytes(f.toPath());
        FlacTags.Written w = FlacTags.write(f, FlacTags.set(FlacTags.read(f).comments, "TRACKNUMBER", "1"));

        FlacTags.undo(f, w.originalHeader, w.audioSha256);

        assertArrayEquals(original, Files.readAllBytes(f.toPath()));
    }

    @Test
    public void undoAfterARebuildAlsoRestoresTheOriginalBytes() throws IOException {
        File f = flac(tags("ALBUM=A"), -1, false);
        byte[] original = Files.readAllBytes(f.toPath());
        FlacTags.Written w = FlacTags.write(f, tags("ALBUM=A", "TRACKTOTAL=12"));
        assertFalse(w.inPlace);

        FlacTags.undo(f, w.originalHeader, w.audioSha256);

        assertArrayEquals(original, Files.readAllBytes(f.toPath()));
    }

    @Test
    public void undoRefusesWhenTheAudioHasChanged() throws IOException {
        File f = flac(tags("ALBUM=A"), 1024, false);
        FlacTags.Written w = FlacTags.write(f, tags("ALBUM=B"));
        byte[] d = Files.readAllBytes(f.toPath());
        d[d.length - 1] ^= 1; // a re-rip / different file
        Files.write(f.toPath(), d);
        try {
            FlacTags.undo(f, w.originalHeader, w.audioSha256);
            fail();
        } catch (FlacTags.NotEditable expected) {
            assertEquals("ALBUM=B", FlacTags.read(f).comments.get(0));
        }
    }

    @Test
    public void refusesNonFlacAndTruncatedFiles() throws IOException {
        File mp3 = tmp.newFile();
        Files.write(mp3.toPath(), new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0});
        File cut = tmp.newFile();
        byte[] whole = Files.readAllBytes(flac(tags("ALBUM=A"), 1024, false).toPath());
        Files.write(cut.toPath(), Arrays.copyOf(whole, 60));
        for (File f : new File[] {mp3, cut}) {
            byte[] before = Files.readAllBytes(f.toPath());
            try {
                FlacTags.write(f, tags("ALBUM=X"));
                fail();
            } catch (FlacTags.NotEditable expected) {
                assertArrayEquals(before, Files.readAllBytes(f.toPath()));
            }
        }
    }

    @Test
    public void unicodeTagsRoundTrip() throws IOException {
        File f = flac(tags("ARTIST=Sigur Rós", "ALBUM=Ágætis byrjun"), 1024, false);
        FlacTags.write(f, FlacTags.set(FlacTags.read(f).comments, "TRACKTOTAL", "10"));
        assertEquals("Ágætis byrjun", FlacTags.get(FlacTags.read(f).comments, "ALBUM"));
    }

    @Test
    public void missingKeyReadsNull() {
        assertNull(FlacTags.get(tags("ALBUM=A"), "DISCNUMBER"));
    }

}
