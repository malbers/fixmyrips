package com.malbers.fixmyrips.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Second discs the Eversolo CD Ripper put in a dated folder. When an album's folder name is already taken, the
 * ripper names the next disc "Album-YYYYMMDDHHMMSS" instead of "Disc 2" (Michael's Billy Joel, Mellon Collie and
 * Three Dog Night sets, 2025). A second rip of the same disc gets the same kind of name (his Away From the World
 * and I'm With You), so a dated folder proves nothing on its own: disc numbers come only from a MusicBrainz
 * release whose discs carry these folders' track titles, in order.
 */
public final class TimestampDiscs {
    static final Pattern DATED = Pattern.compile("^(.+)-(\\d{14})$");
    /** Share of a folder's tracks whose file name must contain the release's title at the same position. */
    static final double TITLE_SHARE = 0.8;
    /** Full releases fetched per set at most; each is one MusicBrainz request. */
    static final int MAX_LOOKUPS = 4;

    /** What the dated-folder check found for one album: its disc and the set's size, or a note on why not. */
    public static final class Hint {
        public final Integer disc, total;
        public final String note;

        Hint(Integer disc, Integer total, String note) {
            this.disc = disc;
            this.total = total;
            this.note = note;
        }
    }

    private final Http http;

    public TimestampDiscs(Http http) {
        this.http = http;
    }

    /** Each folder that has dated siblings, followed by them, oldest first. */
    static List<List<Album>> sets(List<Album> albums) {
        Map<String, Album> byPath = new HashMap<>();
        for (Album a : albums) byPath.put(a.dir.getPath(), a);
        Map<String, List<Album>> dated = new LinkedHashMap<>();
        for (Album a : albums) {
            Matcher m = DATED.matcher(a.dir.getName());
            if (m.matches()) dated.computeIfAbsent(new File(a.dir.getParentFile(), m.group(1)).getPath(), k -> new ArrayList<>()).add(a);
        }
        List<List<Album>> out = new ArrayList<>();
        for (Map.Entry<String, List<Album>> e : dated.entrySet()) {
            Album base = byPath.get(e.getKey());
            if (base == null) continue;
            List<Album> set = new ArrayList<>();
            set.add(base);
            List<Album> rest = new ArrayList<>(e.getValue());
            rest.sort(Comparator.comparing(a -> a.dir.getName()));
            set.addAll(rest);
            out.add(set);
        }
        return out;
    }

    /** Hints for every album in a dated set. Network trouble leaves that set without hints: nothing is guessed. */
    public Map<File, Hint> find(List<Album> albums) {
        Map<File, Hint> out = new HashMap<>();
        for (List<Album> set : sets(albums)) {
            try {
                out.putAll(check(set));
            } catch (IOException | JSONException e) {
                // No confirmed match, so no disc numbers.
            }
        }
        return out;
    }

    Map<File, Hint> check(List<Album> set) throws IOException, JSONException {
        Map<File, Hint> out = new HashMap<>();
        List<List<String>> names = new ArrayList<>();
        for (Album a : set) {
            File[] files = audio(a.dir);
            if (files.length == 0) return out;
            List<String> n = new ArrayList<>();
            for (File f : files) {
                TrackInfo t = TagReader.readTrack(f).withFile(f);
                if (t.disc != null) return out; // already numbered: nothing to work out
                n.add(Text.norm(t.name));
            }
            names.add(n);
        }

        Album base = set.get(0);
        Tags tags = TagReader.read(audio(base.dir)[0]);
        String album = tags.album != null ? tags.album : base.dir.getName();
        String artist = tags.bestArtist();
        if (artist == null && base.dir.getParentFile() != null) artist = base.dir.getParentFile().getName();
        if (artist == null) return out;

        String q = "release:\"" + RipCheck.clean(ArtLookup.searchTitle(album)) + "\" AND artist:\"" + RipCheck.clean(artist) + "\"";
        Http.Response res = http.get(ArtLookup.MB + "release/?query=" + RipCheck.enc(q) + "&fmt=json&limit=25");
        if (res.code != 200) return out;
        List<JSONObject> pool = RipCheck.candidates(
                new JSONObject(new String(res.body, StandardCharsets.UTF_8)).optJSONArray("releases"), artist, album);
        // Sets first: they are what can say "disc 2". Single discs after, to recognise a second copy.
        pool.sort(Comparator.comparingInt(r -> r.optJSONArray("media").length() >= set.size() ? 0 : 1));

        boolean duplicate = false;
        int fetched = 0;
        Set<String> seen = new HashSet<>();
        for (JSONObject r : pool) {
            if (fetched >= MAX_LOOKUPS) break;
            String id = r.optString("id");
            if (id.isEmpty() || !seen.add(id) || !countsFit(r.optJSONArray("media"), names)) continue;
            fetched++;
            Http.Response full = http.get(ArtLookup.MB + "release/" + RipCheck.enc(id) + "?inc=recordings&fmt=json");
            if (full.code != 200) continue;
            JSONArray media = new JSONObject(new String(full.body, StandardCharsets.UTF_8)).optJSONArray("media");
            if (media == null) continue;
            int[] pick = assign(media, names);
            if (pick == null) continue;
            if (distinct(pick)) {
                if (media.length() < 2) continue;
                for (int k = 0; k < set.size(); k++) {
                    int disc = media.optJSONObject(pick[k]).optInt("position", pick[k] + 1);
                    out.put(set.get(k).dir, new Hint(disc, media.length(), null));
                }
                return out;
            }
            duplicate = true;
        }

        for (int k = 0; k < set.size(); k++) {
            String note;
            if (duplicate) {
                note = k == 0 ? "\"" + otherNames(set, k) + "\" looks like a second copy of this album (MusicBrainz shows the same tracks): nothing changed"
                        : "looks like a second copy of \"" + base.dir.getName() + "\" (MusicBrainz shows the same tracks): nothing changed. Delete one copy if you don't need both";
            } else if (k == 0) {
                continue;
            } else {
                note = "the CD ripper gave this folder a date instead of a disc number, and MusicBrainz couldn't confirm which disc it is: disc number left alone";
            }
            out.put(set.get(k).dir, new Hint(null, null, note));
        }
        return out;
    }

    /** True when the release has a disc of the right length for every folder (from the search, no extra request). */
    static boolean countsFit(JSONArray media, List<List<String>> names) {
        if (media == null) return false;
        for (List<String> n : names) {
            boolean any = false;
            for (int i = 0; i < media.length(); i++) {
                JSONObject m = media.optJSONObject(i);
                if (m != null && m.optInt("track-count") == n.size()) any = true;
            }
            if (!any) return false;
        }
        return true;
    }

    /** For each folder, the one disc whose titles it carries; null when any folder matches none or several. */
    static int[] assign(JSONArray media, List<List<String>> names) {
        int[] pick = new int[names.size()];
        for (int k = 0; k < names.size(); k++) {
            int found = -1;
            for (int i = 0; i < media.length(); i++) {
                if (!titlesMatch(media.optJSONObject(i), names.get(k))) continue;
                if (found >= 0) return null; // two discs with the same track list: can't tell which
                found = i;
            }
            if (found < 0) return null;
            pick[k] = found;
        }
        return pick;
    }

    /** Same number of tracks, and at least {@link #TITLE_SHARE} of the file names contain the title at that position. */
    static boolean titlesMatch(JSONObject medium, List<String> names) {
        JSONArray tracks = medium == null ? null : medium.optJSONArray("tracks");
        if (tracks == null || tracks.length() != names.size() || names.isEmpty()) return false;
        int hits = 0;
        for (int i = 0; i < names.size(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            String title = t == null ? "" : Text.norm(t.optString("title"));
            if (!title.isEmpty() && (" " + names.get(i) + " ").contains(" " + title + " ")) hits++;
        }
        return hits >= Math.ceil(TITLE_SHARE * names.size());
    }

    private static boolean distinct(int[] pick) {
        Set<Integer> s = new HashSet<>();
        for (int p : pick) if (!s.add(p)) return false;
        return true;
    }

    private static String otherNames(List<Album> set, int skip) {
        List<String> n = new ArrayList<>();
        for (int i = 0; i < set.size(); i++) if (i != skip) n.add(set.get(i).dir.getName());
        return String.join("\", \"", n);
    }

    /** The folder's audio files in name order. */
    private static File[] audio(File dir) {
        File[] files = dir.listFiles(f -> f.isFile() && AlbumScanner.isAudioName(f.getName()));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        return files;
    }
}
