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
import java.util.Base64;
import java.util.List;

/**
 * Applies a {@link FixPlanner.Plan} and undoes it. Before any file is touched, an undo record holding every
 * file's original header and audio hash is saved in the app's own storage; it is the only way back, so it is
 * written first (temp file + rename) and deleted only after a successful undo.
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
                        .put("header", Base64.getEncoder().encodeToString(s.originalHeader))
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
                    FlacTags.undo(f, Base64.getDecoder().decode(e.getString("header")), e.getString("audio"));
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

    private static void writeAtomically(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
