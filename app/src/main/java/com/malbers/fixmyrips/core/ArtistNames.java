package com.malbers.fixmyrips.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One artist spelled two ways across a library ("Beatles" on one album, "The Beatles" on the rest). The app
 * never picks a spelling itself: it finds the groups, the owner chooses, and only then is a plan made.
 */
public final class ArtistNames {
    private ArtistNames() {}

    /** The tags that carry an album's artist. Only a value exactly equal to the chosen-away spelling changes. */
    static final String[] KEYS = {"ARTIST", "ALBUMARTIST", "ALBUM ARTIST"};

    /** One spelling and the albums that use it. */
    public static final class Spelling {
        public final String name;
        public final List<Album> albums = new ArrayList<>();
        /** Albums FixMyRips can't change yet (see {@link #changeable}), e.g. an MP3 with no ID3v2 tag. */
        public int notChangeable;

        Spelling(String name) {
            this.name = name;
        }
    }

    /** Two or more spellings of what is clearly one artist; the most-used spelling first. */
    public static final class Group {
        public final List<Spelling> spellings;

        Group(List<Spelling> spellings) {
            this.spellings = spellings;
        }

        public Spelling majority() {
            return spellings.get(0);
        }

        public int albumCount() {
            int n = 0;
            for (Spelling s : spellings) n += s.albums.size();
            return n;
        }

        /** How many albums would change if the owner keeps {@code keep}: those with another spelling that can be changed. */
        public int changes(Spelling keep) {
            int n = 0;
            for (Spelling s : spellings) if (s != keep) n += s.albums.size() - s.notChangeable;
            return n;
        }

        /** False when no album in the group can be changed, whichever spelling is kept. */
        public boolean canChange() {
            for (Spelling s : spellings) if (s.albums.size() > s.notChangeable) return true;
            return false;
        }

        /** The group's status in the artist list: {@link #CHOOSE}, or {@link #CANT_CHANGE} when nothing in it can change. */
        public String status() {
            return canChange() ? CHOOSE : CANT_CHANGE;
        }

        /** The chooser's button: "Change 2 albums", or {@link #CANT_CHANGE} when keeping {@code keep} changes nothing. */
        public String buttonLabel(Spelling keep) {
            int n = changes(keep);
            return n == 0 ? CANT_CHANGE : n == 1 ? "Change 1 album" : "Change " + n + " albums";
        }
    }

    public static final String CHOOSE = "Choose";
    /** Shown instead of a button wherever nothing can be changed, so no dead button looks tappable. */
    public static final String CANT_CHANGE = "MP3, can't change yet";

    /**
     * The comparison key: case, accents, punctuation and spaces ignored, a leading "The" and a library-sort
     * ", The" dropped, "&" and "and" treated alike. "Beatles", "The Beatles" and "Beatles, The" share a key;
     * so do "AC/DC" and "ACDC". "The The" keeps its name ("the").
     */
    public static String key(String name) {
        String k = Text.searchKey(name);
        if (k.startsWith("the ") && k.length() > 4) k = k.substring(4);
        if (k.endsWith(" the") && k.length() > 4) k = k.substring(0, k.length() - 4);
        k = (" " + k + " ").replace(" and ", " ");
        return k.replace(" ", "");
    }

    /**
     * Groups albums whose artist is spelled differently but shares a {@link #key}.
     *
     * @param artistOf each album's artist as the player shows it (album artist, else track artist); albums
     *                 with no artist are left out
     */
    public static List<Group> find(Map<Album, String> artistOf) {
        Map<String, Map<String, Spelling>> byKey = new LinkedHashMap<>();
        for (Map.Entry<Album, String> e : artistOf.entrySet()) {
            String name = e.getValue() == null ? null : e.getValue().trim();
            if (name == null || name.isEmpty()) continue;
            String k = key(name);
            if (k.isEmpty()) continue;
            Spelling s = byKey.computeIfAbsent(k, x -> new LinkedHashMap<>()).computeIfAbsent(name, Spelling::new);
            s.albums.add(e.getKey());
        }
        List<Group> out = new ArrayList<>();
        for (Map<String, Spelling> spellings : byKey.values()) {
            if (spellings.size() < 2) continue;
            List<Spelling> list = new ArrayList<>(spellings.values());
            // Only albums in a group are checked: for an MP3 that means reading its tag.
            for (Spelling s : list) for (Album a : s.albums) if (!changeable(a)) s.notChangeable++;
            list.sort(Comparator.comparingInt((Spelling s) -> -s.albums.size()).thenComparing(s -> s.name));
            out.add(new Group(list));
        }
        out.sort(Comparator.comparing(g -> g.majority().name.toLowerCase(Locale.ROOT)));
        return out;
    }

    /**
     * True when FixMyRips can write the artist tags of every audio file in the album: FLAC, or MP3 with an
     * ID3v2.3/2.4 tag it can rewrite (see {@link Id3Tags#whyArtistUnchangeable}).
     */
    public static boolean changeable(Album a) {
        return whyUnchangeable(a) == null;
    }

    /** Why the album's artist tags can't be changed ("01.mp3: no ID3v2 tag"), or null when they can. */
    static String whyUnchangeable(Album a) {
        File[] files = audio(a.dir);
        if (files.length == 0) return "no audio files";
        for (File f : files) {
            String format = TagFiles.format(f);
            if (TagFiles.FLAC.equals(format)) continue;
            if (!TagFiles.ID3.equals(format)) return f.getName() + ": only FLAC and MP3 files can be changed";
            String why = Id3Tags.whyArtistUnchangeable(f);
            if (why != null) return f.getName() + ": " + why;
        }
        return null;
    }

    /**
     * The changes that rename {@code from} to {@code to} in one album: every artist tag whose value is exactly
     * {@code from}. Guest credits ("Beatles feat. X") and other artists on a compilation are left as they are.
     */
    public static FixPlanner.Plan plan(Album a, String from, String to) throws IOException {
        List<FixPlanner.Change> changes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String why = whyUnchangeable(a);
        if (why != null) {
            notes.add("artist name left alone: can't be changed yet (" + why + ")");
            return new FixPlanner.Plan(a, changes, notes, new ArrayList<>());
        }
        for (File f : audio(a.dir)) {
            List<String> c = TagFiles.comments(f);
            for (String key : KEYS) {
                String v = FlacTags.get(c, key);
                if (v != null && v.trim().equals(from.trim())) changes.add(new FixPlanner.Change(f, key, v, to));
            }
        }
        return new FixPlanner.Plan(a, changes, notes, new ArrayList<>());
    }

    private static File[] audio(File dir) {
        File[] files = dir.listFiles(f -> f.isFile() && AlbumScanner.isAudioName(f.getName()));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        return files;
    }
}
