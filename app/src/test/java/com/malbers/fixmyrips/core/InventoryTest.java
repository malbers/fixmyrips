package com.malbers.fixmyrips.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class InventoryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static LibraryIndex.Entry e(String artist, String album, String release, String group, Integer disc, String status, String... problems) {
        return new LibraryIndex.Entry("/m/" + artist + "/" + album, artist, album, release, group, disc, 12, status, new ArrayList<>(Arrays.asList(problems)));
    }

    private static LibraryIndex lib(LibraryIndex.Entry... es) {
        return new LibraryIndex(new ArrayList<>(Arrays.asList(es)), 1);
    }

    private static Inventory.Release r(String id, String group, String title, String artist, Integer... discs) {
        return new Inventory.Release(id, group, title, artist, Arrays.asList(discs));
    }

    @Test
    public void sameReleaseIdIsCertain() {
        Inventory.Answer a = Inventory.match(lib(e("Van Morrison", "A Night in San Francisco", "rel-1", "g-1", null, LibraryIndex.RIPPED)),
                Arrays.asList(r("rel-1", "g-1", "A Night in San Francisco", "Van Morrison", 12)));
        assertEquals(Inventory.RIPPED, a.verdict);
        assertEquals("Ripped · 12 tracks, matches this CD", a.message);
    }

    @Test
    public void anotherReleaseIdWithTheSameTrackCountIsTheSameCd() {
        // Michael's Doors CD: the ripper tagged a sibling edition of the same disc.
        Inventory.Answer a = Inventory.match(lib(e("The Doors", "The Doors: Original Soundtrack Recording", "ripper-pick", "g-doors", null, LibraryIndex.RIPPED)),
                Arrays.asList(r("case-barcode", "g-doors", "The Doors: Music From The Original Motion Picture", "The Doors", 12)));
        assertEquals(Inventory.RIPPED, a.verdict);
    }

    @Test
    public void rerunRipIsRippedWithTheRerunNotAMatchClaim() {
        Inventory.Answer a = Inventory.match(lib(e("Commodores", "Anthology", "rel", "g-c", null, LibraryIndex.RERIP, "17 of 21 tracks")),
                Arrays.asList(r("rel-2", "g-c", "Anthology", "Commodores", 21)));
        assertEquals(Inventory.RIPPED, a.verdict);
        assertEquals("Ripped · needs a re-rip: 17 of 21 tracks", a.message);
    }

    @Test
    public void sameGroupWithAnotherReleaseIdIsADifferentEdition() {
        Inventory.Answer a = Inventory.match(lib(e("The Beatles", "Help!", "uk-14", "g-help", null, LibraryIndex.RIPPED)),
                Arrays.asList(r("uk-14", "g-help", "Help!", "The Beatles", 14)));
        assertEquals(Inventory.OTHER_EDITION, a.verdict);
        assertEquals("Ripped, but a different edition: this CD has 14 tracks, your rip has 12", a.message);
    }

    @Test
    public void groupFromTheLookupOnlyIsUnconfirmedEdition() {
        Inventory.Answer a = Inventory.match(lib(e("Carole King", "Tapestry", null, "g-tap", null, LibraryIndex.RIPPED)),
                Arrays.asList(r("x", "g-tap", "Tapestry (bonus)", "Carole King", 14), r("y", "g-tap", "Tapestry", "Carole King", 12)));
        assertEquals("Ripped · 12 tracks, matches this CD", a.message);
    }

    @Test
    public void nameOnlyIsProbablyAndRerunIsMentioned() {
        Inventory.Answer a = Inventory.match(lib(e("Commodores", "Anthology: The Best of the Commodores", null, null, null,
                        LibraryIndex.RERIP, "17 of 21 tracks, missing 17, 19, 20, 21")),
                Arrays.asList(r("y", "g-y", "Anthology: The Best of The Commodores", "Commodores", 21)));
        assertEquals(Inventory.PROBABLY, a.verdict);
        assertTrue(a.message, a.message.startsWith("Probably ripped (matched by name) · needs a re-rip"));
        assertTrue(a.message, a.message.contains("needs a re-rip: 17 of 21 tracks"));
    }

    @Test
    public void multiDiscSetCountsDiscsFromFolderNames() {
        Inventory.Answer a = Inventory.match(lib(
                        e("The Beatles", "1967-1970 (Disc 1)", null, null, 1, LibraryIndex.RERIP, "11 of 14 tracks"),
                        e("The Beatles", "1967-1970 (Disc 2)", null, null, 2, LibraryIndex.RERIP, "3 of 14 tracks")),
                Arrays.asList(r("z", "g-z", "1967-1970", "The Beatles", 14, 14)));
        assertEquals(Inventory.PROBABLY, a.verdict);
        assertTrue(a.message, a.message.contains("2 of 2 discs"));
        assertEquals(2, a.matches.size());
    }

    @Test
    public void nothingMatchingIsNotRippedAndUnknownBarcodeSaysSo() {
        assertEquals(Inventory.NOT_RIPPED, Inventory.match(lib(e("A", "B", null, null, null, LibraryIndex.RIPPED)),
                Arrays.asList(r("q", "g-q", "Kind of Blue", "Miles Davis", 5))).verdict);
        assertEquals(Inventory.UNKNOWN, Inventory.match(lib(), Collections.emptyList()).verdict);
    }

    @Test
    public void typedSearchNeedsEveryWord() {
        LibraryIndex l = lib(e("The Beatles", "Abbey Road", null, null, null, LibraryIndex.RIPPED),
                e("The Beatles", "Help!", null, null, null, LibraryIndex.RIPPED));
        assertEquals(1, Inventory.find(l, "beatles abbey").matches.size());
        assertEquals(2, Inventory.find(l, "beatles").matches.size());
        assertEquals(0, Inventory.find(l, "abbey stones").matches.size());
    }

    @Test
    public void parsesABarcodeSearch() throws Exception {
        JSONObject o = new JSONObject("{\"releases\":[{\"id\":\"r1\",\"title\":\"Abbey Road\",\"release-group\":{\"id\":\"g1\"},"
                + "\"artist-credit\":[{\"name\":\"The Beatles\"}],\"media\":[{\"track-count\":17}]}]}");
        List<Inventory.Release> rs = Inventory.parse(o);
        assertEquals("g1", rs.get(0).group);
        assertEquals("The Beatles", rs.get(0).artist);
        assertEquals(1, rs.get(0).discs);
        assertTrue(Inventory.barcodeUrl("0 77774 64462 4").contains("barcode%3A077774644624"));
    }

    @Test
    public void indexMergeReplacesOnlyTheScannedFolderAndRoundTrips() throws Exception {
        LibraryIndex old = new LibraryIndex(new ArrayList<>(Arrays.asList(
                new LibraryIndex.Entry("/m/EverSoloMusic/The Beatles/Help", "The Beatles", "Help", null, null, null, 14, "ok", new ArrayList<>()),
                new LibraryIndex.Entry("/m/EverSoloMusic/Commodores/Anthology", "Commodores", "Anthology", null, null, null, 17, "rerip",
                        new ArrayList<>(Collections.singletonList("17 of 21"))))), 1);
        LibraryIndex merged = old.merge(new File("/m/EverSoloMusic/The Beatles"), Collections.singletonList(
                new LibraryIndex.Entry("/m/EverSoloMusic/The Beatles/Abbey Road", "The Beatles", "Abbey Road", "r", "g", null, 17, "ok", new ArrayList<>())), 2);
        assertEquals(2, merged.entries.size());
        File f = new File(tmp.getRoot(), "FixMyRips/library.json");
        merged.save(f);
        LibraryIndex back = LibraryIndex.load(f);
        assertEquals(2, back.entries.size());
        assertEquals(2, back.updated);
        assertEquals("17 of 21", back.entries.get(0).problems.get(0));
        assertEquals("g", back.entries.get(1).group);
    }
}
