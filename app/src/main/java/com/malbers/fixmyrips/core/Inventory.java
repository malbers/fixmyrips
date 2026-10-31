package com.malbers.fixmyrips.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rip Inventory: "is this CD in my library?" A barcode becomes the MusicBrainz releases printed with it; those
 * are compared with the library list, strongest evidence first:
 * <ol>
 *   <li>the same release ID as in the tags → the same edition, certain;</li>
 *   <li>the same release group → the same album, a different or unconfirmed edition;</li>
 *   <li>the same artist and album name → probably, said as a name match;</li>
 *   <li>nothing → not ripped.</li>
 * </ol>
 */
public final class Inventory {
    public static final String RIPPED = "ripped", OTHER_EDITION = "other-edition", PROBABLY = "probably",
            NOT_RIPPED = "not-ripped", UNKNOWN = "unknown-barcode";

    private Inventory() {}

    /** One MusicBrainz release that carries the scanned barcode. */
    public static final class Release {
        public final String id, group, title, artist;
        public final int discs;
        public final List<Integer> tracksPerDisc;

        public Release(String id, String group, String title, String artist, List<Integer> tracksPerDisc) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.artist = artist;
            this.tracksPerDisc = tracksPerDisc;
            this.discs = Math.max(1, tracksPerDisc.size());
        }
    }

    public static final class Answer {
        public final String verdict;
        public final String title, artist;
        public final List<LibraryIndex.Entry> matches;
        /** One line for the screen, e.g. "2 of 3 discs ripped · tracks 17, 19-21 need re-ripping". */
        public final String message;

        Answer(String verdict, String title, String artist, List<LibraryIndex.Entry> matches, String message) {
            this.verdict = verdict;
            this.title = title;
            this.artist = artist;
            this.matches = matches;
            this.message = message;
        }

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject().put("verdict", verdict).put("message", message);
                if (title != null) o.put("title", title);
                if (artist != null) o.put("artist", artist);
                JSONArray m = new JSONArray();
                for (LibraryIndex.Entry e : matches) {
                    JSONObject j = new JSONObject().put("path", e.path).put("status", e.status).put("problems", new JSONArray(e.problems));
                    if (e.artist != null) j.put("artist", e.artist);
                    if (e.album != null) j.put("album", e.album);
                    if (e.disc != null) j.put("disc", e.disc);
                    m.put(j);
                }
                return o.put("matches", m);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** The MusicBrainz search URL for a barcode, digits only. */
    public static String barcodeUrl(String barcode) {
        String digits = barcode.replaceAll("\\D", "");
        try {
            return ArtLookup.MB + "release/?query=" + URLEncoder.encode("barcode:" + digits, "UTF-8") + "&fmt=json&limit=25";
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /** Looks the barcode up and answers. Throws on network trouble so the page can say "try again". */
    public static Answer check(Http http, LibraryIndex lib, String barcode) throws IOException {
        Http.Response res = http.get(barcodeUrl(barcode));
        if (res.code != 200) throw new IOException("MusicBrainz answered " + res.code);
        try {
            return match(lib, parse(new JSONObject(new String(res.body, StandardCharsets.UTF_8))));
        } catch (JSONException e) {
            throw new IOException("unreadable answer from MusicBrainz", e);
        }
    }

    static List<Release> parse(JSONObject search) {
        List<Release> out = new ArrayList<>();
        JSONArray rs = search.optJSONArray("releases");
        if (rs == null) return out;
        for (int i = 0; i < rs.length(); i++) {
            JSONObject r = rs.optJSONObject(i);
            if (r == null || r.optString("id").isEmpty()) continue;
            List<Integer> discs = new ArrayList<>();
            JSONArray media = r.optJSONArray("media");
            if (media != null) for (int k = 0; k < media.length(); k++) {
                JSONObject m = media.optJSONObject(k);
                discs.add(m == null ? 0 : m.optInt("track-count"));
            }
            out.add(new Release(r.optString("id"), RipCheck.groupId(r), r.optString("title"), credit(r), discs));
        }
        return out;
    }

    public static Answer match(LibraryIndex lib, List<Release> releases) {
        if (releases.isEmpty()) {
            return new Answer(UNKNOWN, null, null, new ArrayList<>(),
                    "This barcode isn't on MusicBrainz. Search by artist or album instead.");
        }
        Release top = releases.get(0);
        Set<String> ids = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        for (Release r : releases) {
            ids.add(r.id);
            if (r.group != null) groups.add(r.group);
        }

        List<LibraryIndex.Entry> exact = new ArrayList<>(), sameGroup = new ArrayList<>(), byName = new ArrayList<>();
        for (LibraryIndex.Entry e : lib.entries) {
            if (e.releaseId != null && ids.contains(e.releaseId)) exact.add(e);
            else if (e.group != null && groups.contains(e.group)) sameGroup.add(e);
            else if (nameMatches(e, releases)) byName.add(e);
        }

        if (!exact.isEmpty()) return answer(RIPPED, top, exact, "Ripped, same edition");
        if (!sameGroup.isEmpty()) {
            boolean knownOther = false;
            for (LibraryIndex.Entry e : sameGroup) if (e.releaseId != null) knownOther = true;
            return answer(OTHER_EDITION, top, sameGroup, knownOther ? "Ripped, but a different edition" : "Ripped (edition not confirmed)");
        }
        if (!byName.isEmpty()) return answer(PROBABLY, top, byName, "Probably ripped (matched by name)");
        return new Answer(NOT_RIPPED, top.title, top.artist, new ArrayList<>(), "Not ripped yet");
    }

    /** Typed search: every word of the query appears in the album's artist and title. */
    public static Answer find(LibraryIndex lib, String query) {
        String[] words = Text.norm(query).split(" ");
        List<LibraryIndex.Entry> hits = new ArrayList<>();
        for (LibraryIndex.Entry e : lib.entries) {
            String hay = " " + Text.norm(e.artist) + " " + Text.norm(e.album) + " ";
            boolean all = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(" " + w)) { all = false; break; }
            if (all && words.length > 0 && !words[0].isEmpty()) hits.add(e);
        }
        if (hits.isEmpty()) return new Answer(NOT_RIPPED, query, null, hits, "Nothing in your library matches \"" + query + "\"");
        String msg = hits.size() == 1 ? "1 album matches" : hits.size() + " albums match";
        return new Answer(PROBABLY, query, null, hits, msg + summary(hits));
    }

    private static Answer answer(String verdict, Release top, List<LibraryIndex.Entry> found, String head) {
        String discs = "";
        if (top.discs > 1) {
            Set<Integer> have = new LinkedHashSet<>();
            for (LibraryIndex.Entry e : found) if (e.disc != null) have.add(e.disc);
            discs = " · " + (have.isEmpty() ? "disc numbers unknown" : have.size() + " of " + top.discs + " discs");
        }
        return new Answer(verdict, top.title, top.artist, found, head + discs + summary(found));
    }

    /** " · tracks 17, 19, 20 need re-ripping" when any match needs a re-rip. */
    private static String summary(List<LibraryIndex.Entry> found) {
        for (LibraryIndex.Entry e : found) {
            if (LibraryIndex.RERIP.equals(e.status) && !e.problems.isEmpty()) return " · needs a re-rip: " + e.problems.get(0);
        }
        return "";
    }

    private static boolean nameMatches(LibraryIndex.Entry e, List<Release> releases) {
        String album = Text.norm(ArtLookup.searchTitle(stripDisc(e.album)));
        String artist = Text.norm(e.artist);
        if (album.isEmpty() || artist.isEmpty()) return false;
        for (Release r : releases) {
            String ra = Text.norm(r.artist);
            if (Text.norm(ArtLookup.searchTitle(r.title)).equals(album)
                    && !ra.isEmpty() && (ra.equals(artist) || ra.contains(artist) || artist.contains(ra))) return true;
        }
        return false;
    }

    /** "1967-1970 (Disc 1)" → "1967-1970". */
    private static String stripDisc(String album) {
        return album == null ? null : album.replaceAll("(?i)[\\s(\\[_-]*\\b(?:disc|disk|cd)\\s*\\d+[)\\]]?", "").trim();
    }

    private static String credit(JSONObject release) {
        JSONArray ac = release.optJSONArray("artist-credit");
        if (ac == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ac.length(); i++) {
            JSONObject c = ac.optJSONObject(i);
            if (c != null) b.append(c.optString("name")).append(c.optString("joinphrase"));
        }
        return b.toString();
    }
}
