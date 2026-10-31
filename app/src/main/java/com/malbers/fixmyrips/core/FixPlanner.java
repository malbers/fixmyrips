package com.malbers.fixmyrips.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which tag changes an album needs. Plans only: nothing is written here. Every rule skips when it
 * can't be sure, and says why in {@link Plan#notes}; a wrong tag is worse than a missing one.
 *
 * <ul>
 *   <li>F1: track numbers that start at 0 (the Eversolo CD Ripper) move up by one, when every file's tag is
 *       exactly one below its file name's number.</li>
 *   <li>F2: TRACKTOTAL, only when the rip check compared the folder with the release and found it complete.</li>
 *   <li>F3: DISCNUMBER from a "Disc N" folder name, and DISCTOTAL when the sibling disc folders run 1..N.</li>
 *   <li>F4: DISCNUMBER and DISCTOTAL for a disc the ripper put in a dated folder, from {@link TimestampDiscs}.</li>
 * </ul>
 */
public final class FixPlanner {
    private static final Pattern DISC = Pattern.compile("(?i)[\\s(\\[_-]*\\b(?:disc|disk|cd)\\s*(\\d+)[)\\]]?");
    private static final Pattern LEADING_INT = Pattern.compile("^\\s*(\\d+)");

    private FixPlanner() {}

    public static final class Change {
        public final File file;
        public final String key;
        /** Null when the tag is absent. */
        public final String from;
        public final String to;

        Change(File file, String key, String from, String to) {
            this.file = file;
            this.key = key;
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return file.getName() + ": " + key + " " + (from == null ? "(none)" : from) + " → " + to;
        }
    }

    public static final class Plan {
        public final Album album;
        public final List<Change> changes;
        /** Things left alone, with the reason, e.g. "track total left out: can't confirm the album is complete". */
        public final List<String> notes;
        /** Problems only a re-rip fixes (from the rip check). */
        public final List<String> reRip;

        Plan(Album album, List<Change> changes, List<String> notes, List<String> reRip) {
            this.album = album;
            this.changes = changes;
            this.notes = notes;
            this.reRip = reRip;
        }

        public boolean fixable() {
            return !changes.isEmpty();
        }

        /** Distinct files the plan touches. */
        public List<File> files() {
            List<File> out = new ArrayList<>();
            for (Change c : changes) if (!out.contains(c.file)) out.add(c.file);
            return out;
        }
    }

    /**
     * @param check the rip check's result for this album, or null when it wasn't run (then no track total is
     *              written, since only a comparison with the release proves the folder is complete)
     */
    public static Plan plan(Album album, RipCheck.Result check) {
        return plan(album, check, null);
    }

    /**
     * @param hint what {@link TimestampDiscs} found for a dated disc folder, or null: the disc number and total
     *             when MusicBrainz confirmed them, else a note that is shown as it is
     */
    public static Plan plan(Album album, RipCheck.Result check, TimestampDiscs.Hint hint) {
        List<Change> changes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> reRip = reRipProblems(check);

        File[] audio = album.dir.listFiles(f -> f.isFile() && AlbumScanner.isAudioName(f.getName()));
        if (audio == null || audio.length == 0) return new Plan(album, changes, notes, reRip);
        Arrays.sort(audio, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        for (File f : audio) {
            if (TagFiles.format(f) == null) {
                notes.add("not changed: only FLAC and MP3 files can be fixed (" + f.getName() + ")");
                return new Plan(album, changes, notes, reRip);
            }
        }

        List<List<String>> tags = new ArrayList<>();
        List<TrackInfo> info = new ArrayList<>();
        for (File f : audio) {
            try {
                tags.add(TagFiles.comments(f));
            } catch (IOException e) {
                notes.add("not changed: " + f.getName() + " can't be read (" + e.getMessage() + ")");
                return new Plan(album, new ArrayList<>(), notes, reRip);
            }
            info.add(TagReader.readTrack(f).withFile(f));
        }

        // F1: numbers from 0.
        Integer[] numbers = new Integer[audio.length];
        boolean shift = zeroBased(info);
        for (int i = 0; i < audio.length; i++) {
            numbers[i] = info.get(i).track;
            if (shift) {
                String old = FlacTags.get(tags.get(i), "TRACKNUMBER");
                String now = bump(old, info.get(i).fileNumber);
                changes.add(new Change(audio[i], "TRACKNUMBER", old, now));
                numbers[i] = info.get(i).fileNumber;
            }
        }
        if (!shift && info.stream().anyMatch(t -> t.track != null && t.track == 0)) {
            notes.add("track numbers start at 0 but don't line up with the file names: left alone");
        }
        String mismatch = shift ? null : RipCheck.tagNameMismatch(info);
        if (mismatch != null && !mismatch.isEmpty()) notes.add(mismatch + ": left alone");

        // F2: track total.
        boolean missingTotal = false;
        for (int i = 0; i < audio.length; i++) {
            if (FlacTags.get(tags.get(i), "TRACKTOTAL") == null && FlacTags.get(tags.get(i), "TOTALTRACKS") == null
                    && !String.valueOf(FlacTags.get(tags.get(i), "TRACKNUMBER")).contains("/")) {
                missingTotal = true;
            }
        }
        if (missingTotal) {
            String why = totalBlocker(check, numbers);
            if (why == null) {
                for (int i = 0; i < audio.length; i++) {
                    if (FlacTags.get(tags.get(i), "TRACKTOTAL") == null) {
                        changes.add(new Change(audio[i], "TRACKTOTAL", null, String.valueOf(audio.length)));
                    }
                }
            } else {
                notes.add("track total left out: " + why);
            }
        }

        // F3: disc number and total from the folder names, or for a dated folder, from MusicBrainz (F4).
        Integer disc = discFromName(album.dir.getName());
        Integer total = disc == null ? null : discTotal(album.dir);
        if (disc == null && hint != null && hint.disc != null) {
            disc = hint.disc;
            total = hint.total;
        }
        if (hint != null && hint.note != null) notes.add(hint.note);
        if (disc != null) {
            for (int i = 0; i < audio.length; i++) {
                String have = FlacTags.get(tags.get(i), "DISCNUMBER");
                if (have == null) {
                    changes.add(new Change(audio[i], "DISCNUMBER", null, String.valueOf(disc)));
                } else if (!have.split("/")[0].trim().equals(String.valueOf(disc))) {
                    notes.add("disc number tag (" + have + ") disagrees with the folder name: left alone");
                    break;
                }
            }
            if (total != null) {
                for (int i = 0; i < audio.length; i++) {
                    if (FlacTags.get(tags.get(i), "DISCTOTAL") == null && FlacTags.get(tags.get(i), "TOTALDISCS") == null) {
                        changes.add(new Change(audio[i], "DISCTOTAL", null, String.valueOf(total)));
                    }
                }
            } else {
                notes.add("disc total left out: the other disc folders aren't all there");
            }
        }
        return new Plan(album, changes, notes, reRip);
    }

    /** Applies a plan's changes for one file to that file's current tags. */
    public static List<String> apply(List<String> comments, List<Change> changes, File file) {
        List<String> out = comments;
        for (Change c : changes) if (c.file.equals(file)) out = FlacTags.set(out, c.key, c.to);
        return out;
    }

    /** Every numbered file is tagged exactly one below its file name, and the lowest tag is 0. */
    static boolean zeroBased(List<TrackInfo> info) {
        boolean zero = false;
        for (TrackInfo t : info) {
            if (t.track == null || t.fileNumber == null) return false;
            if (t.track != t.fileNumber - 1) return false;
            if (t.track == 0) zero = true;
        }
        return zero;
    }

    /** "0" → "1", "0/14" → "1/14": only the leading number changes. */
    static String bump(String old, int to) {
        if (old == null) return String.valueOf(to);
        Matcher m = LEADING_INT.matcher(old);
        if (!m.find()) return String.valueOf(to);
        return old.substring(0, m.start(1)) + to + old.substring(m.end(1));
    }

    /** Why no track total may be written, or null when it may. */
    static String totalBlocker(RipCheck.Result check, Integer[] numbers) {
        if (check == null) return "run the rip check first";
        if (check.skipped != null) return "the rip check couldn't compare this album (" + check.skipped + ")";
        if (!RipCheck.BY_ID.equals(check.basis) && !RipCheck.BY_SEARCH.equals(check.basis)) {
            return "can't confirm the album is complete";
        }
        if (!reRipProblems(check).isEmpty()) return "tracks are missing or damaged";
        TreeSet<Integer> seen = new TreeSet<>();
        for (Integer n : numbers) {
            if (n == null || !seen.add(n)) return "track numbers are missing or repeated";
        }
        if (seen.first() != 1 || seen.last() != numbers.length) return "track numbers don't run 1 to " + numbers.length;
        return null;
    }

    static List<String> reRipProblems(RipCheck.Result check) {
        List<String> out = new ArrayList<>();
        if (check == null || check.skipped != null) return out;
        for (String p : check.problems) {
            // Tag-vs-name mismatches are tag problems, not rip problems.
            if (p.startsWith("track tags")) continue;
            out.add(p);
        }
        return out;
    }

    static Integer discFromName(String folder) {
        Matcher m = DISC.matcher(folder);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** The folder name with its disc marker removed, to find the other discs of the same set. */
    static String setName(String folder) {
        return DISC.matcher(folder).replaceAll("").trim().toLowerCase(Locale.ROOT);
    }

    /** N (2 or more) when the folder's siblings of the same set are exactly discs 1..N, else null. A lone
     *  "Disc 1" folder says nothing about how many discs the set has. */
    static Integer discTotal(File dir) {
        File parent = dir.getParentFile();
        File[] sibs = parent == null ? null : parent.listFiles(File::isDirectory);
        if (sibs == null) return null;
        String set = setName(dir.getName());
        TreeSet<Integer> discs = new TreeSet<>();
        for (File s : sibs) {
            Integer d = discFromName(s.getName());
            if (d != null && setName(s.getName()).equals(set) && !discs.add(d)) return null;
        }
        if (discs.size() < 2 || discs.first() != 1 || discs.last() != discs.size()) return null;
        return discs.size();
    }
}
