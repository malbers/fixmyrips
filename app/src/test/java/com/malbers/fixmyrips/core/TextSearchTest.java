package com.malbers.fixmyrips.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TextSearchTest {
    @Test
    public void curlyAndStraightApostrophesSearchAlike() {
        String row = Text.searchKey("Red Hot Chili Peppers · I’m With You-20250816220534");
        assertTrue(Text.matchesAll(row, Text.searchKey("I'm with you")));
        assertTrue(Text.matchesAll(row, Text.searchKey("chili with")));
        assertTrue(Text.matchesAll(row, Text.searchKey("  RED  hot ")));
    }

    @Test
    public void everyWordHasToMatch() {
        String row = Text.searchKey("The Beatles · Abbey Road");
        assertFalse(Text.matchesAll(row, Text.searchKey("beatles help")));
        assertTrue(Text.matchesAll(row, Text.searchKey("abb")));
    }

    @Test
    public void accentsAreIgnored() {
        assertEquals("beyonce lemonade", Text.searchKey("Beyoncé · Lemonade"));
        assertTrue(Text.matchesAll(Text.searchKey("Sigur Rós · ( )"), Text.searchKey("sigur ros")));
    }
}
