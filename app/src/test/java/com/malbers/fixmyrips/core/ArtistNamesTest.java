package com.malbers.fixmyrips.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArtistNamesTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void keysMatchTheUsualSpellingDifferences() {
        assertEquals(ArtistNames.key("The Beatles"), ArtistNames.key("Beatles"));
        assertEquals(ArtistNames.key("The Beatles"), ArtistNames.key("Beatles, The"));
        assertEquals(ArtistNames.key("AC/DC"), ArtistNames.key("ACDC"));
        assertEquals(ArtistNames.key("Simon & Garfunkel"), ArtistNames.key("Simon and Garfunkel"));
        assertEquals(ArtistNames.key("Beyoncé"), ArtistNames.key("Beyonce"));
        assertEquals("the", ArtistNames.key("The The"));
        assertNotEquals(ArtistNames.key("The Doors"), ArtistNames.key("The Who"));
    }

    private Album album(String artistFolder, String name, String ext, String... comments) throws IOException {
        File dir = new File(tmp.getRoot(), artistFolder + "/" + name);
        File first = null;
        for (int i = 1; i <= 2; i++) {
            File f = new File(dir, String.format("%02d.Song.%s", i, ext));
            if (ext.equals("flac")) TestFlac.write(f, 1024, comments);
            else {
                assertTrue(dir.mkdirs() || dir.isDirectory());
                assertTrue(f.createNewFile());
            }
            if (first == null) first = f;
        }
        return new Album(dir, 2, first, null, false);
    }

    @Test
    public void findsOneArtistSpelledTwoWaysAndPutsTheMostUsedFirst() throws IOException {
        Map<Album, String> artist = new LinkedHashMap<>();
        artist.put(album("The Beatles", "Abbey Road", "flac"), "The Beatles");
        artist.put(album("The Beatles", "Revolver", "flac"), "The Beatles");
        Album help = album("Beatles", "Help", "flac");
        artist.put(help, "Beatles");
        artist.put(album("The Who", "Tommy", "flac"), "The Who");
        List<ArtistNames.Group> groups = ArtistNames.find(artist);
        assertEquals(1, groups.size());
        assertEquals("The Beatles", groups.get(0).majority().name);
        assertEquals(2, groups.get(0).majority().albums.size());
        assertEquals("Beatles", groups.get(0).spellings.get(1).name);
        assertEquals(help, groups.get(0).spellings.get(1).albums.get(0));
        assertEquals(3, groups.get(0).albumCount());
    }

    @Test
    public void planChangesOnlyTagsThatAreExactlyTheOldSpelling() throws IOException {
        Album help = album("Beatles", "Help", "flac", "ARTIST=Beatles", "ALBUMARTIST=Beatles", "TITLE=Help!");
        FixPlanner.Plan p = ArtistNames.plan(help, "Beatles", "The Beatles");
        assertEquals(4, p.changes.size()); // ARTIST + ALBUMARTIST on 2 files
        for (FixPlanner.Change c : p.changes) assertEquals("The Beatles", c.to);

        Album guest = album("Beatles", "Guests", "flac", "ARTIST=Beatles feat. Billy Preston");
        assertTrue(ArtistNames.plan(guest, "Beatles", "The Beatles").changes.isEmpty());
    }

    @Test
    public void fixAndUndoRoundTripTheArtistTags() throws IOException {
        Album help = album("Beatles", "Help", "flac", "ARTIST=Beatles", "TITLE=Help!");
        FixPlanner.Plan p = ArtistNames.plan(help, "Beatles", "The Beatles");
        File rec = Fixer.fix(p, tmp.newFolder("undo"), 1L);
        for (File f : p.files()) assertEquals("The Beatles", FlacTags.get(FlacTags.read(f).comments, "ARTIST"));
        Fixer.undo(rec);
        for (File f : p.files()) assertEquals("Beatles", FlacTags.get(FlacTags.read(f).comments, "ARTIST"));
    }

    @Test
    public void mp3AlbumsAreListedButNotChanged() throws IOException {
        Album mp3 = album("Beatles", "Help", "mp3");
        assertFalse(ArtistNames.changeable(mp3));
        FixPlanner.Plan p = ArtistNames.plan(mp3, "Beatles", "The Beatles");
        assertTrue(p.changes.isEmpty());
        assertTrue(p.notes.get(0), p.notes.get(0).contains("no ID3v2 tag"));

        Map<Album, String> artist = new LinkedHashMap<>();
        artist.put(album("The Beatles", "Abbey Road", "flac"), "The Beatles");
        artist.put(mp3, "Beatles");
        for (ArtistNames.Spelling s : ArtistNames.find(artist).get(0).spellings) {
            assertEquals(s.name.equals("Beatles") ? 1 : 0, s.notChangeable); // a 1-1 tie sorts by name
        }
    }
}
