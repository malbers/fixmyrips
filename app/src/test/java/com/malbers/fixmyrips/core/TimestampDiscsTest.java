package com.malbers.fixmyrips.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TimestampDiscsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String[] DISC1 = {"Tonight, Tonight", "Jellybelly", "Zero"};
    private static final String[] DISC2 = {"Where Boys Fear to Tread", "Bodies", "Thirty-Three"};

    /** A folder named like the Eversolo CD Ripper names it: "01.Title-Artist.flac". */
    private Album folder(File parent, String name, String[] titles, String... extra) throws IOException {
        File dir = new File(parent, name);
        File first = null;
        for (int i = 0; i < titles.length; i++) {
            List<String> c = new ArrayList<>(Arrays.asList("ALBUM=Mellon Collie and the Infinite Sadness",
                    "ARTIST=The Smashing Pumpkins", "TITLE=" + titles[i], "TRACKNUMBER=" + (i + 1)));
            c.addAll(Arrays.asList(extra));
            File f = TestFlac.write(new File(dir, String.format("%02d.%s-The Smashing Pumpkins.flac", i + 1, titles[i])), 1024,
                    c.toArray(new String[0]));
            if (first == null) first = f;
        }
        return new Album(dir, titles.length, first, null, false);
    }

    private static String search(int... discCounts) {
        StringBuilder media = new StringBuilder();
        for (int n : discCounts) media.append(media.length() == 0 ? "" : ",").append("{\"format\":\"CD\",\"track-count\":").append(n).append("}");
        return "{\"releases\":[{\"id\":\"rel1\",\"score\":100,\"title\":\"Mellon Collie and the Infinite Sadness\","
                + "\"artist-credit\":[{\"name\":\"The Smashing Pumpkins\"}],\"media\":[" + media + "]}]}";
    }

    private static String release(String[]... discs) {
        StringBuilder media = new StringBuilder();
        for (int d = 0; d < discs.length; d++) {
            StringBuilder tracks = new StringBuilder();
            for (String t : discs[d]) tracks.append(tracks.length() == 0 ? "" : ",").append("{\"title\":\"").append(t).append("\"}");
            media.append(d == 0 ? "" : ",").append("{\"position\":").append(d + 1).append(",\"track-count\":")
                    .append(discs[d].length).append(",\"tracks\":[").append(tracks).append("]}");
        }
        return "{\"media\":[" + media + "]}";
    }

    private List<Album> pair(String[] base, String[] dated, String... extra) throws IOException {
        File parent = tmp.newFolder("Smashing Pumpkins");
        return Arrays.asList(
                folder(parent, "Mellon Collie and the Infinite Sadness", base, extra),
                folder(parent, "Mellon Collie and the Infinite Sadness-20250816203430", dated, extra));
    }

    @Test
    public void groupsADatedFolderWithTheFolderItWasNamedAfter() throws IOException {
        File parent = tmp.newFolder("Artist");
        Album base = folder(parent, "Album", DISC1);
        Album dated = folder(parent, "Album-20250824181140", DISC2);
        Album other = folder(parent, "Other", DISC1);
        Album orphan = folder(parent, "Gone-20250824181140", DISC2);
        List<List<Album>> sets = TimestampDiscs.sets(Arrays.asList(base, dated, other, orphan));
        assertEquals(1, sets.size());
        assertEquals(Arrays.asList(base, dated), sets.get(0));
    }

    @Test
    public void numbersARealSecondDiscFromTheTitles() throws IOException {
        // Mellon Collie: both discs have the same number of tracks, so only the titles tell them apart.
        List<Album> albums = pair(DISC1, DISC2);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on("release/?query", search(3, 3))
                .on("release/rel1?inc=recordings", release(DISC1, DISC2));
        Map<File, TimestampDiscs.Hint> hints = new TimestampDiscs(http).find(albums);
        assertEquals(Integer.valueOf(1), hints.get(albums.get(0).dir).disc);
        assertEquals(Integer.valueOf(2), hints.get(albums.get(1).dir).disc);
        assertEquals(Integer.valueOf(2), hints.get(albums.get(1).dir).total);
        assertNull(hints.get(albums.get(1).dir).note);
    }

    @Test
    public void callsASecondRipOfTheSameDiscACopyAndNumbersNothing() throws IOException {
        List<Album> albums = pair(DISC1, DISC1);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on("release/?query", search(3))
                .on("release/rel1?inc=recordings", release(DISC1));
        Map<File, TimestampDiscs.Hint> hints = new TimestampDiscs(http).find(albums);
        assertNull(hints.get(albums.get(1).dir).disc);
        assertTrue(hints.get(albums.get(1).dir).note.contains("second copy"));
        assertTrue(hints.get(albums.get(0).dir).note.contains("second copy"));
    }

    @Test
    public void leavesTheDiscAloneWhenTheTitlesDontMatch() throws IOException {
        List<Album> albums = pair(DISC1, new String[] {"Something", "Else", "Entirely"});
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp()
                .on("release/?query", search(3, 3))
                .on("release/rel1?inc=recordings", release(DISC1, DISC2));
        Map<File, TimestampDiscs.Hint> hints = new TimestampDiscs(http).find(albums);
        assertNull(hints.get(albums.get(0).dir));
        assertNull(hints.get(albums.get(1).dir).disc);
        assertTrue(hints.get(albums.get(1).dir).note.contains("couldn't confirm"));
    }

    @Test
    public void asksNothingWhenTheFilesAlreadySayWhichDisc() throws IOException {
        List<Album> albums = pair(DISC1, DISC2, "DISCNUMBER=1");
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp();
        assertTrue(new TimestampDiscs(http).find(albums).isEmpty());
        assertTrue(http.asked.isEmpty());
    }

    @Test
    public void guessesNothingWhenMusicBrainzCantBeReached() throws IOException {
        List<Album> albums = pair(DISC1, DISC2);
        ArtLookupTest.FakeHttp http = new ArtLookupTest.FakeHttp().on("release/", new IOException("offline"));
        assertTrue(new TimestampDiscs(http).find(albums).isEmpty());
    }

    @Test
    public void thePlanWritesTheConfirmedDiscWithoutTouchingTheFolderName() throws IOException {
        List<Album> albums = pair(DISC1, DISC2);
        FixPlanner.Plan p = FixPlanner.plan(albums.get(1), null, new TimestampDiscs.Hint(2, 2, null));
        List<String> discs = new ArrayList<>();
        List<String> totals = new ArrayList<>();
        for (FixPlanner.Change c : p.changes) {
            if (c.key.equals("DISCNUMBER")) discs.add(c.to);
            if (c.key.equals("DISCTOTAL")) totals.add(c.to);
        }
        assertEquals(Arrays.asList("2", "2", "2"), discs);
        assertEquals(Arrays.asList("2", "2", "2"), totals);
        assertTrue(albums.get(1).dir.getName().endsWith("-20250816203430"));
    }

    @Test
    public void thePlanShowsTheNoteWhenNothingWasConfirmed() throws IOException {
        List<Album> albums = pair(DISC1, DISC1);
        FixPlanner.Plan p = FixPlanner.plan(albums.get(1), null, new TimestampDiscs.Hint(null, null, "looks like a second copy"));
        assertTrue(p.notes.contains("looks like a second copy"));
        for (FixPlanner.Change c : p.changes) assertTrue(!c.key.startsWith("DISC"));
    }
}
