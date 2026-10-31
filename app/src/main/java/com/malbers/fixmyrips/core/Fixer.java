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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Applies a {@link FixPlanner.Plan} and undoes it. Before any file is touched, an undo record holding every
 * file's original header and audio hash is saved; it is the only way back, so it is written first (temp file +
 * rename) and deleted only after a successful undo. Headers are stored block by block: small blocks (the tags)
 * in full, padding by length, and large blocks (embedded covers) by fingerprint, since a fix never touches them.
 */
public final class Fixer {
    private Fixer() {}

    /** Fixes the album and returns its undo record. On any failure, files already changed are put back. */
    public static File fix(FixPlanner.Plan plan, File undoDir, long now) throws IOException {
        List<File> files = plan.files();
        JSONObject rec = new JSONObject();
        JSONArray entries = new JSONArray();
        List<FlacTags.Written> before = new ArrayList<>();
        try {
            rec.put("album", plan.album.dir.getPath());
            rec.put("time", now);
            JSONArray changes = new JSONArray();
            for (FixPlanner.Change c : plan.changes) changes.put(c.toString());
            rec.put("changes", changes);
            for (File f : files) {
                FlacTags.Written s = FlacTags.snapshot(f);
                before.add(s);
                entries.put(new JSONObject()
                        .put("path", f.getPath())
                        .put("blocks", compact(s.originalHeader))
                        .put("audio", s.audioSha256));
            }
            rec.put("files", entries);
        } catch (JSONException e) {
            throw new IOException(e);
        }

        if (!undoDir.isDirectory() && !undoDir.mkdirs()) throw new IOException("can't create " + undoDir);
        File out = new File(undoDir, now + "-" + Integer.toHexString(plan.album.dir.getPath().hashCode()) + ".json");
        writeAtomically(out, rec.toString());

        int done = 0;
        try {
            for (File f : files) {
                List<String> tags = FixPlanner.apply(FlacTags.read(f).comments, plan.changes, f);
                FlacTags.write(f, tags);
                done++;
            }
        } catch (IOException e) {
            for (int i = 0; i < done; i++) {
                try {
                    FlacTags.undo(files.get(i), before.get(i).originalHeader, before.get(i).audioSha256);
                } catch (IOException ignored) {
                    // The record stays, so Undo can still be tried from the app.
                }
            }
            if (done > 0) throw new IOException(e.getMessage() + "; the " + done + " files already changed were put back", e);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
        return out;
    }

    /** Restores every file in the record. The record is deleted only when all of them came back. */
    public static void undo(File record) throws IOException {
        JSONObject rec;
        try {
            rec = new JSONObject(new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8));
            JSONArray files = rec.getJSONArray("files");
            List<String> failed = new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject e = files.getJSONObject(i);
                File f = new File(e.getString("path"));
                try {
                    byte[] header = e.has("blocks") ? expand(e.getJSONArray("blocks"), f) : Base64.getDecoder().decode(e.getString("header"));
                    FlacTags.undo(f, header, e.getString("audio"));
                } catch (IOException ex) {
                    failed.add(f.getName() + " (" + ex.getMessage() + ")");
                }
            }
            if (!failed.isEmpty()) throw new IOException("not undone: " + String.join(", ", failed));
        } catch (JSONException e) {
            throw new IOException("damaged undo record", e);
        }
        //noinspection ResultOfMethodCallIgnored
        record.delete();
    }

    /** Album folder and time of an undo record, for the list in the app. */
    public static String[] describe(File record) {
        try {
            JSONObject rec = new JSONObject(new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8));
            return new String[] {rec.getString("album"), String.valueOf(rec.getLong("time")), String.valueOf(rec.getJSONArray("changes").length())};
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Blocks this big or bigger are kept by fingerprint (embedded covers run 50-500 KB per track). */
    static final int BY_FINGERPRINT = 4096;

    static JSONArray compact(byte[] header) throws JSONException, IOException {
        JSONArray out = new JSONArray();
        int p = 4;
        boolean last = false;
        while (!last) {
            last = (header[p] & 0x80) != 0;
            int type = header[p] & 0x7f;
            int len = (header[p + 1] & 0xff) << 16 | (header[p + 2] & 0xff) << 8 | (header[p + 3] & 0xff);
            p += 4;
            JSONObject b = new JSONObject().put("type", type).put("len", len);
            byte[] data = Arrays.copyOfRange(header, p, p + len);
            if (type == FlacTags.PADDING && allZero(data)) {
                // length is enough
            } else if (len >= BY_FINGERPRINT) {
                b.put("sha", sha(data));
            } else {
                b.put("data", Base64.getEncoder().encodeToString(data));
            }
            out.put(b);
            p += len;
        }
        return out;
    }

    /** Rebuilds the original header, taking fingerprinted blocks from the file as it is now. */
    static byte[] expand(JSONArray blocks, File f) throws IOException, JSONException {
        FlacTags.Header now = FlacTags.read(f);
        List<Integer> types = new ArrayList<>();
        List<byte[]> data = new ArrayList<>();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.getJSONObject(i);
            int type = b.getInt("type");
            types.add(type);
            if (b.has("data")) {
                data.add(Base64.getDecoder().decode(b.getString("data")));
            } else if (b.has("sha")) {
                byte[] found = null;
                for (byte[] cur : now.blocks) if (cur.length == b.getInt("len") && sha(cur).equals(b.getString("sha"))) { found = cur; break; }
                if (found == null) throw new FlacTags.NotEditable("an embedded picture has changed since the fix");
                data.add(found);
            } else {
                data.add(new byte[b.getInt("len")]);
            }
        }
        return FlacTags.serialize(types, data);
    }

    private static boolean allZero(byte[] d) {
        for (byte x : d) if (x != 0) return false;
        return true;
    }

    private static String sha(byte[] d) throws IOException {
        try {
            StringBuilder s = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(d)) s.append(String.format(Locale.ROOT, "%02x", x));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void writeAtomically(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
