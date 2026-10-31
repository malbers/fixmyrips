package com.malbers.fixmyrips.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The library list Rip Inventory answers from: one entry per album folder, built from a check run and kept
 * in Download/FixMyRips/library.json. Scanning a folder replaces the entries under that folder and keeps the
 * rest, so scanning parts of a library one at a time adds up.
 */
public final class LibraryIndex {
    public static final String RIPPED = "ok", RERIP = "rerip", NOT_CHECKED = "notchecked";

    public static final class Entry {
        public final String path, artist, album;
        /** MusicBrainz release ID from the tags (one exact edition), or null. */
        public final String releaseId;
        /** MusicBrainz release group (the album across editions), or null. */
        public final String group;
        public final Integer disc;
        public final int tracks;
        public final String status;
        public final List<String> problems;

        public Entry(String path, String artist, String album, String releaseId, String group, Integer disc, int tracks,
                     String status, List<String> problems) {
            this.path = path;
            this.artist = artist;
            this.album = album;
            this.releaseId = releaseId;
            this.group = group;
            this.disc = disc;
            this.tracks = tracks;
            this.status = status;
            this.problems = problems;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject().put("path", path).put("tracks", tracks).put("status", status);
            if (artist != null) o.put("artist", artist);
            if (album != null) o.put("album", album);
            if (releaseId != null) o.put("release", releaseId);
            if (group != null) o.put("group", group);
            if (disc != null) o.put("disc", disc);
            o.put("problems", new JSONArray(problems));
            return o;
        }

        static Entry fromJson(JSONObject o) {
            List<String> p = new ArrayList<>();
            JSONArray a = o.optJSONArray("problems");
            if (a != null) for (int i = 0; i < a.length(); i++) p.add(a.optString(i));
            return new Entry(o.optString("path"), opt(o, "artist"), opt(o, "album"), opt(o, "release"), opt(o, "group"),
                    o.has("disc") ? o.optInt("disc") : null, o.optInt("tracks"), o.optString("status", NOT_CHECKED), p);
        }
    }

    public final List<Entry> entries;
    /** When the list was last saved (ms), or 0. */
    public final long updated;

    public LibraryIndex(List<Entry> entries, long updated) {
        this.entries = entries;
        this.updated = updated;
    }

    /** One album's entry from its check and plan. */
    public static Entry entry(Album a, RipCheck.Result check, FixPlanner.Plan plan) {
        Tags t = TagReader.read(a.firstAudio);
        TrackInfo ti = TagReader.readTrack(a.firstAudio);
        String artist = t.bestArtist();
        if (artist == null && check != null) artist = check.artist;
        String album = t.album != null ? t.album : a.dir.getName();
        Integer disc = ti.disc != null ? ti.disc : RipCheck.discFromName(a.dir.getName());
        String status = plan != null && !plan.reRip.isEmpty() ? RERIP
                : check != null && check.skipped == null ? RIPPED : NOT_CHECKED;
        List<String> problems = plan != null ? new ArrayList<>(plan.reRip) : new ArrayList<>();
        return new Entry(a.dir.getPath(), artist, album, t.mbAlbumId, check == null ? null : check.releaseGroup, disc,
                a.trackCount, status, problems);
    }

    /** Entries outside {@code root} from this list, plus {@code scanned}. */
    public LibraryIndex merge(File root, List<Entry> scanned, long now) {
        String base = root.getPath().replace('\\', '/');
        String prefix = base.endsWith("/") ? base : base + "/";
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            String p = e.path.replace('\\', '/');
            if (p.equals(base) || p.startsWith(prefix)) continue;
            out.add(e);
        }
        out.addAll(scanned);
        return new LibraryIndex(out, now);
    }

    public String toJson() {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : entries) a.put(e.toJson());
            return new JSONObject().put("updated", updated).put("albums", a).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static LibraryIndex fromJson(String s) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(s);
            JSONArray a = o.optJSONArray("albums");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(Entry.fromJson(a.getJSONObject(i)));
            return new LibraryIndex(out, o.optLong("updated"));
        } catch (JSONException e) {
            return new LibraryIndex(out, 0);
        }
    }

    /** The saved list, or an empty one when there is none or it can't be read. */
    public static LibraryIndex load(File f) {
        try {
            return f.isFile() ? fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : new LibraryIndex(new ArrayList<>(), 0);
        } catch (IOException e) {
            return new LibraryIndex(new ArrayList<>(), 0);
        }
    }

    public void save(File f) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(toJson().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String opt(JSONObject o, String k) {
        String v = o.optString(k, "");
        return v.isEmpty() ? null : v;
    }
}
