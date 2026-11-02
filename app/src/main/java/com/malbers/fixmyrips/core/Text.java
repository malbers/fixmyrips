package com.malbers.fixmyrips.core;

import java.text.Normalizer;
import java.util.Locale;

public final class Text {
    private Text() {}

    /** Comparison key for titles and artist names: case, accents, "&", a leading "The" and bracketed suffixes ignored. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)|\\[[^]]*]", " ")
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (n.startsWith("the ")) n = n.substring(4);
        // Library-sort form, "Album II, The".
        if (n.endsWith(" the")) n = n.substring(0, n.length() - 4);
        return n;
    }

    /** For search boxes: lower case, accents and punctuation gone ("I’m" and "I'm" both become "i m"), words single-spaced. */
    public static String searchKey(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** True when every word of the query appears in the text, in any order ("chili with" finds "Red Hot Chili Peppers · I'm With You"). */
    public static boolean matchesAll(String text, String query) {
        String t = " " + text + " ";
        for (String w : query.split(" ")) if (!w.isEmpty() && !t.contains(w)) return false;
        return true;
    }
}
