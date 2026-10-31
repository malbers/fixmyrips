package com.malbers.fixmyrips.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FixPlannerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static RipCheck.Result ok(Album a, String basis) {
        return new RipCheck.Result(a, "Commodores", new ArrayList<>(), null, basis);
    }

    private static List<String> keys(FixPlanner.Plan p, String key) {
        List<String> out = new ArrayList<>();
        for (FixPlanner.Change c : p.changes) if (c.key.equals(key)) out.add(c.from + ">" + c.to);
        return out;
    }

    @Test
    public void zeroBasedTracksMoveUpByOne() throws IOException {
        Album a = TestFlac.album(tmp.newFolder("Anthology"), 3, 0);
        FixPlanner.Plan p = FixPlanner.plan(a, null);
        assertEquals(Arrays.asList("0>1", "1>2", "2>3"), keys(p, "TRACKNUMBER"));
        assertTrue(p.fixable());
    }

    @Test
    public void slashFormKeepsItsTotal() {
        assertEquals("1/14", FixPlanner.bump("0/14", 1));
        assertEquals("1", FixPlanner.bump("0", 1));
        assertEquals("12", FixPlanner.bump(null, 12));
    }

    @Test
    public void correctTracksAreLeftAlone() throws IOException {
        Album a = TestFlac.album(tmp.newFolder("Fine"), 3, 1);
        assertTrue(keys(FixPlanner.plan(a, null), "TRACKNUMBER").isEmpty());
    }

    @Test
    public void zeroThatDoesNotLineUpWithFileNamesIsOnlyNoted() throws IOException {
        File dir = tmp.newFolder("Odd");
        TestFlac.write(new File(dir, "01.A.flac"), 64, "TRACKNUMBER=0");
        TestFlac.write(new File(dir, "02.B.flac"), 64, "TRACKNUMBER=5");
        FixPlanner.Plan p = FixPlanner.plan(new Album(dir, 2, null, null, false), null);
        assertTrue(keys(p, "TRACKNUMBER").isEmpty());
        assertTrue(p.notes.toString(), p.notes.stream().anyMatch(n -> n.contains("start at 0")));
    }

    @Test
    public void trackTotalNeedsTheRipCheckToHaveComparedWithTheRelease() throws IOException {
        Album a = TestFlac.album(tmp.newFolder("A"), 4, 1);
        assertTrue(keys(FixPlanner.plan(a, null), "TRACKTOTAL").isEmpty());
        assertTrue(keys(FixPlanner.plan(a, ok(a, RipCheck.BY_TAGS)), "TRACKTOTAL").isEmpty());
        assertEquals(Collections.nCopies(4, "null>4"), keys(FixPlanner.plan(a, ok(a, RipCheck.BY_ID)), "TRACKTOTAL"));
        assertEquals(Collections.nCopies(4, "null>4"), keys(FixPlanner.plan(a, ok(a, RipCheck.BY_SEARCH)), "TRACKTOTAL"));
    }

    @Test
    public void noTrackTotalWhenTracksAreMissing() throws IOException {
        Album a = TestFlac.album(tmp.newFolder("Commodores"), 17, 0);
        RipCheck.Result bad = new RipCheck.Result(a, "Commodores", new ArrayList<>(Arrays.asList("17 of 21 tracks, missing 18, 19, 20, 21")),
                null, RipCheck.BY_ID);
        FixPlanner.Plan p = FixPlanner.plan(a, bad);
        assertTrue(keys(p, "TRACKTOTAL").isEmpty());
        assertEquals(17, keys(p, "TRACKNUMBER").size()); // the 0-based fix still applies
        assertEquals(Arrays.asList("17 of 21 tracks, missing 18, 19, 20, 21"), p.reRip);
        assertTrue(p.notes.stream().anyMatch(n -> n.startsWith("track total left out")));
    }

    @Test
    public void tagMismatchIsNotARerip() throws IOException {
        Album a = TestFlac.album(tmp.newFolder("M"), 2, 1);
        RipCheck.Result r = new RipCheck.Result(a, "x", new ArrayList<>(Arrays.asList("track tags don't match the file names on 1 of 2 files")),
                null, RipCheck.BY_TAGS);
        assertTrue(FixPlanner.plan(a, r).reRip.isEmpty());
    }

    @Test
    public void discNumbersAndTotalFromSiblingFolders() throws IOException {
        File parent = tmp.newFolder("The Beatles");
        Album d1 = TestFlac.album(new File(parent, "1967-1970 (Disc 1)"), 2, 1);
        TestFlac.album(new File(parent, "1967-1970 (Disc 2)"), 2, 1);
        TestFlac.album(new File(parent, "Abbey Road"), 2, 1);
        FixPlanner.Plan p = FixPlanner.plan(d1, null);
        assertEquals(Arrays.asList("null>1", "null>1"), keys(p, "DISCNUMBER"));
        assertEquals(Arrays.asList("null>2", "null>2"), keys(p, "DISCTOTAL"));
    }

    @Test
    public void loneDiscFolderGetsNumberButNoTotal() throws IOException {
        File parent = tmp.newFolder("Artist");
        Album d1 = TestFlac.album(new File(parent, "Live CD1"), 2, 1);
        FixPlanner.Plan p = FixPlanner.plan(d1, null);
        assertEquals(2, keys(p, "DISCNUMBER").size());
        assertTrue(keys(p, "DISCTOTAL").isEmpty());
        assertTrue(p.notes.stream().anyMatch(n -> n.startsWith("disc total left out")));
    }

    @Test
    public void discTagThatDisagreesIsLeftAlone() throws IOException {
        File parent = tmp.newFolder("X");
        Album d2 = TestFlac.album(new File(parent, "Set Disc 2"), 1, 1, "DISCNUMBER=1");
        FixPlanner.Plan p = FixPlanner.plan(d2, null);
        assertTrue(keys(p, "DISCNUMBER").isEmpty());
        assertTrue(p.notes.stream().anyMatch(n -> n.contains("disagrees")));
    }

    @Test
    public void foldersWithoutDiscMarkerGetNoDiscTags() throws IOException {
        Album a = TestFlac.album(tmp.newFolder("1967-1970"), 2, 1);
        assertTrue(keys(FixPlanner.plan(a, null), "DISCNUMBER").isEmpty());
        assertNull(FixPlanner.discFromName("1967-1970"));
        assertEquals(Integer.valueOf(2), FixPlanner.discFromName("Live at the BBC [CD2]"));
    }

    @Test
    public void fixAppliesThePlanAndUndoRestoresEveryByte() throws IOException {
        File dir = tmp.newFolder("Anthology");
        Album a = TestFlac.album(dir, 3, 0);
        File[] files = dir.listFiles();
        Arrays.sort(files);
        List<byte[]> before = new ArrayList<>();
        for (File f : files) before.add(Files.readAllBytes(f.toPath()));
        FixPlanner.Plan p = FixPlanner.plan(a, ok(a, RipCheck.BY_ID));

        File rec = Fixer.fix(p, tmp.newFolder("undo"), 1000L);

        for (int i = 0; i < files.length; i++) {
            List<String> c = FlacTags.read(files[i]).comments;
            assertEquals(String.valueOf(i + 1), FlacTags.get(c, "TRACKNUMBER"));
            assertEquals("3", FlacTags.get(c, "TRACKTOTAL"));
        }
        assertTrue(FixPlanner.plan(a, ok(a, RipCheck.BY_ID)).changes.isEmpty()); // nothing left to fix
        assertEquals(a.dir.getPath(), Fixer.describe(rec)[0]);

        Fixer.undo(rec);

        for (int i = 0; i < files.length; i++) assertArrayEquals(before.get(i), Files.readAllBytes(files[i].toPath()));
        assertFalse(rec.exists());
    }

    @Test
    public void undoRecordKeepsCoversByFingerprintAndStillRestoresEveryByte() throws IOException {
        File dir = tmp.newFolder("WithCover");
        File f = new File(dir, "01.Track.flac");
        byte[] si = new byte[34];
        si[10] = 0x0A; si[11] = (byte) 0xC4; si[12] = 0x40;
        byte[] cover = new byte[60_000];
        new java.util.Random(7).nextBytes(cover);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(FlacTags.serialize(Arrays.asList(FlacTags.STREAMINFO, FlacTags.VORBIS_COMMENT, 6, FlacTags.PADDING),
                    Arrays.asList(si, FlacTags.buildComments("x", Arrays.asList("TRACKNUMBER=0")), cover, new byte[512])));
            out.write(new byte[50_000]);
        }
        byte[] before = Files.readAllBytes(f.toPath());
        Album a = new Album(dir, 1, f, null, false);

        File rec = Fixer.fix(FixPlanner.plan(a, null), tmp.newFolder("undo"), 5L);

        assertTrue("record should not carry the cover: " + rec.length(), rec.length() < 5_000);
        Fixer.undo(rec);
        assertArrayEquals(before, Files.readAllBytes(f.toPath()));
    }

    @Test
    public void undoKeepsTheRecordWhenAFileWasReplaced() throws IOException {
        File dir = tmp.newFolder("A");
        Album a = TestFlac.album(dir, 2, 0);
        File rec = Fixer.fix(FixPlanner.plan(a, null), tmp.newFolder("undo"), 1L);
        File re = new File(dir, "02.Song 2.flac");
        TestFlac.write(re, 64, "TRACKNUMBER=2", "TITLE=re-ripped");
        byte[] d = Files.readAllBytes(re.toPath());
        d[d.length - 1] ^= 1; // a re-rip: different audio
        Files.write(re.toPath(), d);
        try {
            Fixer.undo(rec);
            org.junit.Assert.fail("undo should refuse a replaced file");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("02.Song 2"));
        }
        assertTrue(rec.exists());
        assertEquals("0", FlacTags.get(FlacTags.read(new File(dir, "01.Song 1.flac")).comments, "TRACKNUMBER"));
        assertEquals("re-ripped", FlacTags.get(FlacTags.read(re).comments, "TITLE"));
    }
}
