package com.malbers.fixmyrips;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.malbers.fixmyrips.core.Album;
import com.malbers.fixmyrips.core.FixPlanner;
import com.malbers.fixmyrips.core.Fixer;
import com.malbers.fixmyrips.core.AlbumScanner;
import com.malbers.fixmyrips.core.RipCheck;
import com.malbers.fixmyrips.core.UpdateCheck;
import com.malbers.fixmyrips.core.UrlHttp;
import com.malbers.fixmyrips.ui.ItemAdapter;
import com.malbers.fixmyrips.ui.Ui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Screens, each built fresh so nothing carries over: access, browse, checking, results, one album,
 * confirm, fixing, report, undo history.
 */
public class MainActivity extends Activity {
    private enum Screen { ACCESS, BROWSE, CHECKING, RESULTS, ALBUM, CONFIRM, FIXING, REPORT, UNDO }

    private static final int REQ_LEGACY = 1;
    private static final String CHANNEL = "https://www.youtube.com/@malbers";

    private LinearLayout root;
    private Screen screen;
    private File browseDir; // null = list of storage volumes
    private File scanRoot;
    private SharedPreferences prefs;
    private boolean sentToSettings;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        prefs = getSharedPreferences("fmr", MODE_PRIVATE);
        root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 14));
        setContentView(root);
        start();
        checkForUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screen != Screen.ACCESS) return;
        if (hasAccess()) start();
        // Back from the All files screen: go straight on to the second prompt, once (a Deny must not loop).
        else if (sentToSettings && hasManager()) {
            sentToSettings = false;
            requestAccess();
        }
    }

    private void start() {
        if (!hasAccess()) { showAccess(); return; }
        String last = prefs.getString("last", null);
        File f = last == null ? null : new File(last);
        showBrowse(f != null && f.isDirectory() && f.canRead() ? f : null);
    }

    // ---------- frame ----------

    /** Clears everything and lays out header, body and bottom buttons. */
    private void frame(Screen s, String subtitle, View body, View... buttons) {
        frameWithLeft(s, subtitle, body, null, buttons);
    }

    /** As {@link #frame}, with an optional view at the bottom left, opposite the buttons. */
    private void frameWithLeft(Screen s, String subtitle, View body, View left, View... buttons) {
        screen = s;
        root.removeAllViews();
        LinearLayout header = Ui.row(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        int iz = Ui.dp(this, 36);
        header.addView(icon, new LinearLayout.LayoutParams(iz, iz));
        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 12), 0, 0, 0);
        titles.addView(Ui.oneLine(this, "FixMyRips", 20, Ui.TEXT, true));
        if (subtitle != null) titles.addView(Ui.oneLine(this, subtitle, 13, Ui.SUB, false));
        header.addView(titles, Ui.weight(1));
        TextView credit = Ui.oneLine(this, "by @malbers", 13, Ui.SUB, false);
        credit.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        credit.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL)));
            } catch (Exception ignored) {
                // No browser on this device; the credit stays as text.
            }
        });
        header.addView(credit, Ui.wrap(Ui.dp(this, 12)));
        root.addView(header);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        bp.topMargin = Ui.dp(this, 12);
        bp.bottomMargin = Ui.dp(this, 12);
        root.addView(body, bp);

        if (buttons.length > 0 || left != null) {
            LinearLayout bar = Ui.row(this);
            if (left != null) bar.addView(left);
            // Zero height: a bare View sized wrap_content grows to fill the screen and squeezes the list out.
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
            for (View b : buttons) bar.addView(b, Ui.wrap(Ui.dp(this, 10)));
            root.addView(bar);
        }
    }

    // ---------- update check ----------

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** At most once a day, ask GitHub for the latest release; the answer is kept for the pill below. */
    private void checkForUpdate() {
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("upd_checked", 0) < DAY_MS) return;
        prefs.edit().putLong("upd_checked", now).apply();
        new Thread(() -> {
            UpdateCheck.Release r = UpdateCheck.latest(new UrlHttp(BuildConfig.USER_AGENT));
            if (r == null) return;
            prefs.edit().putString("upd_version", r.version).putString("upd_url", r.url).apply();
            runOnUiThread(() -> {
                if (screen == Screen.BROWSE) showBrowse(browseDir);
            });
        }, "update-check").start();
    }

    /** "Update available" pill that opens the download in the browser, or null when up to date. */
    private View updatePill() {
        String v = prefs.getString("upd_version", null);
        String url = prefs.getString("upd_url", null);
        if (v == null || url == null || !UpdateCheck.isNewer(v, BuildConfig.VERSION_NAME)) return null;
        updateText = Ui.text(this, "", 15, Ui.GOOD, true);
        updateText.setBackground(Ui.rounded(this, Ui.CARD, 24));
        int h = Ui.dp(this, 18);
        updateText.setPadding(h, Ui.dp(this, 12), h, Ui.dp(this, 12));
        showUpdateState(v);
        updateText.setOnClickListener(x -> {
            if (downloadId < 0 || "failed".equals(downloadState)) downloadUpdate(v, url);
        });
        return updateText;
    }

    private TextView updateText;
    private long downloadId = -1;
    private String downloadState; // null, "running", "done", "failed"

    private void showUpdateState(String v) {
        if (updateText == null) return;
        if ("running".equals(downloadState)) {
            updateText.setTextColor(Ui.ACCENT);
            updateText.setText("Downloading " + v + "…");
        } else if ("done".equals(downloadState)) {
            updateText.setTextColor(Ui.GOOD);
            updateText.setText("Saved. Open Files › Storage › Download and tap it to install");
        } else if ("failed".equals(downloadState)) {
            updateText.setTextColor(Ui.BAD);
            updateText.setText("Download failed · tap to try again");
        } else {
            updateText.setTextColor(Ui.GOOD);
            updateText.setText("Update available: " + v + " · Download");
        }
    }

    /**
     * Saves the APK to the shared Download folder with Android's own download service, so no browser
     * is needed (most Eversolos have none). Falls back to a browser only if that service is missing.
     */
    private void downloadUpdate(String v, String url) {
        DownloadManager dm = getSystemService(DownloadManager.class);
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                    .setTitle("FixMyRips " + v)
                    .setMimeType("application/vnd.android.package-archive")
                    .addRequestHeader("User-Agent", BuildConfig.USER_AGENT)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "FixMyRips-" + v + ".apk");
            downloadId = dm.enqueue(req);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                downloadState = "failed";
                showUpdateState(v);
            }
            return;
        }
        downloadState = "running";
        showUpdateState(v);
        pollDownload(dm, v);
    }

    private void pollDownload(DownloadManager dm, String v) {
        root.postDelayed(() -> {
            int status = -1;
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if (c != null && c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            } catch (Exception ignored) {
                // treated as failed below
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) downloadState = "done";
            else if (status == DownloadManager.STATUS_FAILED || status == -1) downloadState = "failed";
            else {
                pollDownload(dm, v);
                return;
            }
            showUpdateState(v);
        }, 500);
    }

    private LinearLayout card() {
        LinearLayout c = Ui.column(this);
        c.setBackground(Ui.rounded(this, Ui.CARD, 16));
        int p = Ui.dp(this, 6);
        c.setPadding(p, p, p, p);
        return c;
    }

    private ListView list(ItemAdapter adapter) {
        ListView l = new ListView(this);
        l.setAdapter(adapter);
        l.setDivider(null);
        l.setSelector(Ui.rounded(this, Ui.CARD_HI, 10));
        l.setCacheColorHint(0);
        return l;
    }

    private TextView chip(String s, int color) {
        TextView t = Ui.text(this, s, 15, color, true);
        t.setBackground(Ui.rounded(this, Ui.CARD, 18));
        int h = Ui.dp(this, 14);
        t.setPadding(h, Ui.dp(this, 7), h, Ui.dp(this, 7));
        return t;
    }

    // ---------- access ----------

    private boolean hasManager() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private boolean hasLegacy() {
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    // Both are needed on Android 11 with targetSdk 29: All files access alone leaves attached drives looking empty.
    private boolean hasAccess() {
        return hasManager() && hasLegacy();
    }

    private void showAccess() {
        LinearLayout body = card();
        int p = Ui.dp(this, 18);
        body.setPadding(p, p, p, p);
        body.addView(Ui.text(this, "One permission first", 18, Ui.TEXT, true));
        TextView t = Ui.text(this, "FixMyRips needs access to your music folders to check them and, only when you tap Fix, correct their tags. "
                + "It never changes the audio, and never moves, renames or deletes a file. Every fix can be undone.\n\n"
                + "Two quick steps: turn on \"Allow access to manage all files\" and come back, then tap Allow on the pop-up.", 15, Ui.SUB, false);
        t.setPadding(0, Ui.dp(this, 8), 0, 0);
        body.addView(t);
        frame(Screen.ACCESS, null, body, Ui.button(this, "Grant access", true, v -> requestAccess()));
    }

    private void requestAccess() {
        if (hasManager()) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY);
            return;
        }
        sentToSettings = true;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_LEGACY && hasAccess()) start();
    }

    // ---------- browse ----------

    private void showBrowse(File dir) {
        browseDir = dir;
        ItemAdapter adapter = new ItemAdapter(this);
        if (dir == null) {
            for (File[] v : volumes()) {
                adapter.add(new ItemAdapter.Item(v[1].getName(), v[0].getPath(), "›", Ui.SUB, null, false, v[0]).withIcon(R.drawable.ic_drive));
            }
        } else {
            File[] kids = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
            if (kids != null) {
                Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File k : kids) adapter.add(new ItemAdapter.Item("/" + k.getName(), null, "›", Ui.SUB, null, false, k).withIcon(R.drawable.ic_folder));
            }
            if (adapter.getCount() == 0) adapter.add(new ItemAdapter.Item("No folders in here", "Scan it, or go up", null, Ui.SUB, null, false, null));
        }
        LinearLayout body = card();
        ListView l = list(adapter);
        l.setOnItemClickListener((p, v, pos, id) -> {
            Object tag = adapter.getItem(pos).tag;
            if (tag instanceof File) showBrowse((File) tag);
        });
        body.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        String sub = dir == null ? "Choose where your music is" : dir.getPath();
        View update = updatePill();
        if (dir == null) {
            frameWithLeft(Screen.BROWSE, sub, body, update);
        } else {
            frameWithLeft(Screen.BROWSE, sub, body, update,
                    Ui.button(this, "Up", v -> goUp()),
                    Ui.button(this, "Scan this folder", true, v -> startScan(dir)));
        }
    }

    /** {dir, label-as-File-name} pairs for mounted storage. */
    private List<File[]> volumes() {
        List<File[]> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 30) {
            for (StorageVolume v : getSystemService(StorageManager.class).getStorageVolumes()) {
                if (!Environment.MEDIA_MOUNTED.equals(v.getState()) || v.getDirectory() == null) continue;
                out.add(new File[]{v.getDirectory(), new File(v.getDescription(this))});
            }
        } else {
            out.add(new File[]{Environment.getExternalStorageDirectory(), new File("Internal storage")});
            File[] others = new File("/storage").listFiles();
            if (others != null) for (File f : others) {
                if (f.getName().equals("emulated") || f.getName().equals("self") || !f.canRead()) continue;
                out.add(new File[]{f, new File(f.getName())});
            }
        }
        return out;
    }

    private boolean isVolumeRoot(File f) {
        for (File[] v : volumes()) if (v[0].equals(f)) return true;
        return false;
    }

    private void goUp() {
        if (browseDir == null) return;
        showBrowse(isVolumeRoot(browseDir) ? null : browseDir.getParentFile());
    }

    // ---------- check: find the albums, rip-check each, plan its fixes (nothing is written) ----------

    /** One album's check result and planned fixes. */
    private static final class Row {
        final Album album;
        final RipCheck.Result check;
        FixPlanner.Plan plan;

        Row(Album album, RipCheck.Result check, FixPlanner.Plan plan) {
            this.album = album;
            this.check = check;
            this.plan = plan;
        }

        String artist() {
            if (check != null && check.artist != null) return check.artist;
            File p = album.dir.getParentFile();
            return p == null ? null : p.getName();
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private volatile boolean cancelled;
    private int planned;

    private void startScan(File dir) {
        prefs.edit().putString("last", dir.getPath()).apply();
        scanRoot = dir;
        rows.clear();
        cancelled = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout body = Ui.column(this);
        TextView count = Ui.oneLine(this, "Looking for albums…", 13, Ui.SUB, true);
        body.addView(count);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.CARD_HI));
        body.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 10)));
        TextView nowBy = Ui.oneLine(this, "", 14, Ui.SUB, false);
        nowBy.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(nowBy);
        TextView now = Ui.oneLine(this, "", 18, Ui.TEXT, true);
        now.setPadding(0, 0, 0, Ui.dp(this, 10));
        body.addView(now);
        ItemAdapter found = new ItemAdapter(this);
        LinearLayout box = card();
        ListView l = list(found);
        l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        box.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        Button stop = Ui.button(this, "Stop", null);
        stop.setOnClickListener(v -> {
            cancelled = true;
            stop.setEnabled(false);
            stop.setText("Stopping after this album…");
        });
        frame(Screen.CHECKING, "Checking " + dir.getName() + " · nothing is changed", body, stop);

        new Thread(() -> {
            com.malbers.fixmyrips.core.ScanResult found0 = AlbumScanner.scan(dir);
            List<Album> all = new ArrayList<>(found0.missing);
            all.addAll(found0.withArt);
            all.sort(Comparator.comparing(a -> a.dir.getPath().toLowerCase(Locale.ROOT)));
            planned = all.size();
            runOnUiThread(() -> bar.setMax(Math.max(1, all.size())));
            RipCheck check = new RipCheck(new UrlHttp(BuildConfig.USER_AGENT));
            for (int i = 0; i < all.size() && !cancelled; i++) {
                Album a = all.get(i);
                int n = i;
                runOnUiThread(() -> {
                    count.setText((n + 1) + " of " + all.size());
                    nowBy.setText(parentName(a));
                    now.setText(a.dir.getName());
                });
                RipCheck.Result r = check.check(a);
                Row row = new Row(a, r, FixPlanner.plan(a, r));
                runOnUiThread(() -> {
                    rows.add(row);
                    bar.setProgress(rows.size());
                    if (row.plan.fixable() || !row.plan.reRip.isEmpty()) found.add(rowItem(row));
                });
            }
            runOnUiThread(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (screen == Screen.CHECKING) showResults();
            });
        }, "check").start();
    }

    private List<Row> fixable() {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) if (r.plan.fixable()) out.add(r);
        return out;
    }

    private ItemAdapter.Item rowItem(Row r) {
        String artist = r.artist();
        List<String> parts = new ArrayList<>();
        if (r.plan.fixable()) parts.add(describeChanges(r.plan));
        if (!r.plan.reRip.isEmpty()) parts.add("re-rip: " + String.join(" · ", r.plan.reRip));
        String status;
        int color;
        if (r.plan.fixable()) {
            status = "Fixable";
            color = Ui.ACCENT;
        } else if (!r.plan.reRip.isEmpty()) {
            status = "Re-rip";
            color = Ui.BAD;
        } else if (r.check != null && r.check.skipped != null) {
            status = "Not checked";
            color = Ui.SUB;
        } else {
            status = "OK";
            color = Ui.GOOD;
        }
        String sub = (artist != null ? artist + " · " : "") + (parts.isEmpty() ? "" : String.join(" · ", parts));
        return new ItemAdapter.Item(r.album.dir.getName(), sub, status, color, null, false, r);
    }

    /** "track numbers from 0 · track total · disc number" for the tags a plan touches. */
    private static String describeChanges(FixPlanner.Plan p) {
        List<String> what = new ArrayList<>();
        for (FixPlanner.Change c : p.changes) {
            String w;
            switch (c.key) {
                case "TRACKNUMBER": w = "track numbers from 0"; break;
                case "TRACKTOTAL": w = "track total"; break;
                case "DISCNUMBER": w = "disc number"; break;
                case "DISCTOTAL": w = "disc total"; break;
                default: w = c.key.toLowerCase(Locale.ROOT);
            }
            if (!what.contains(w)) what.add(w);
        }
        return String.join(", ", what);
    }

    private void showResults() {
        int fix = 0, rerip = 0, ok = 0, skipped = 0;
        List<ItemAdapter.Item> shown = new ArrayList<>();
        List<ItemAdapter.Item> rerips = new ArrayList<>();
        for (Row r : rows) {
            if (r.plan.fixable()) {
                fix++;
                shown.add(rowItem(r));
            } else if (!r.plan.reRip.isEmpty()) {
                rerip++;
                rerips.add(rowItem(r));
            } else if (r.check != null && r.check.skipped != null) {
                skipped++;
            } else {
                ok++;
            }
        }
        // Re-rip albums that are also fixable already show above; the rest follow.
        for (Row r : rows) if (r.plan.fixable() && !r.plan.reRip.isEmpty()) rerip++;
        shown.addAll(rerips);

        LinearLayout body = Ui.column(this);
        LinearLayout chips = Ui.row(this);
        chips.addView(chip(fix + " fixable", fix == 0 ? Ui.SUB : Ui.ACCENT));
        chips.addView(chip(rerip + " re-rip", rerip == 0 ? Ui.SUB : Ui.BAD), Ui.wrap(Ui.dp(this, 8)));
        chips.addView(chip(ok + " OK", Ui.GOOD), Ui.wrap(Ui.dp(this, 8)));
        chips.addView(chip(skipped + " not checked", Ui.SUB), Ui.wrap(Ui.dp(this, 8)));
        body.addView(chips);

        String note = (cancelled ? "Stopped after " + rows.size() + " of " + planned + " albums. " : "")
                + (shown.isEmpty() ? "Nothing to fix and nothing to re-rip in the albums checked."
                    : "Tap an album to see exactly what would change. Nothing changes until you tap Fix.");
        TextView n = Ui.text(this, note, 15, Ui.TEXT, false);
        n.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), 0, Ui.dp(this, 8));
        body.addView(n);

        ItemAdapter adapter = new ItemAdapter(this);
        adapter.addAll(shown);
        LinearLayout box = card();
        ListView l = list(adapter);
        l.setOnItemClickListener((p, v, pos, id) -> showAlbum((Row) adapter.getItem(pos).tag));
        box.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        Button save = Ui.button(this, "Save report", null);
        save.setOnClickListener(v -> {
            File f = saveReport();
            save.setText(f != null ? "Saved: Download/" + f.getName() : "Couldn't save the report");
            save.setEnabled(f == null);
        });
        List<View> buttons = new ArrayList<>();
        buttons.add(Ui.button(this, "Undo history", v -> showUndo()));
        buttons.add(Ui.button(this, "Choose another folder", v -> showBrowse(scanRoot)));
        List<Row> todo = fixable();
        if (!todo.isEmpty()) buttons.add(Ui.button(this, "Fix all (" + todo.size() + ")", true, v -> confirmFix(todo)));
        frameWithLeft(Screen.RESULTS, scanRoot.getPath(), body, save, buttons.toArray(new View[0]));
    }

    // ---------- one album: the before → after preview ----------

    private void showAlbum(Row r) {
        ItemAdapter adapter = new ItemAdapter(this);
        for (FixPlanner.Change c : r.plan.changes) {
            adapter.add(new ItemAdapter.Item(c.file.getName(), c.key + ":  " + (c.from == null ? "(none)" : c.from) + "  →  " + c.to,
                    "change", Ui.ACCENT, null, false, null));
        }
        for (String p : r.plan.reRip) adapter.add(new ItemAdapter.Item("Re-rip needed", p, "can't fix", Ui.BAD, null, false, null));
        for (String p : r.plan.notes) adapter.add(new ItemAdapter.Item("Left alone", p, null, Ui.SUB, null, false, null));

        LinearLayout body = Ui.column(this);
        String by = r.check != null && r.check.basis != null ? "Compared with the release by " + r.check.basis + ". " : "";
        TextView n = Ui.text(this, by + (r.plan.fixable()
                ? r.plan.changes.size() + " tag changes in " + r.plan.files().size() + " files. Only tags change; the music is checked before and after, and this can be undone."
                : "Nothing here can be fixed by changing tags."), 15, Ui.TEXT, false);
        n.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 8));
        body.addView(n);
        LinearLayout box = card();
        box.addView(list(adapter), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        String title = (r.artist() != null ? r.artist() + " · " : "") + r.album.dir.getName();
        if (r.plan.fixable()) {
            List<Row> one = new ArrayList<>();
            one.add(r);
            frame(Screen.ALBUM, title, body, Ui.button(this, "Back", v -> showResults()),
                    Ui.button(this, "Fix this album", true, v -> confirmFix(one)));
        } else {
            frame(Screen.ALBUM, title, body, Ui.button(this, "Back", true, v -> showResults()));
        }
    }

    // ---------- confirm, fix, report ----------

    private void confirmFix(List<Row> todo) {
        int changes = 0, files = 0;
        for (Row r : todo) {
            changes += r.plan.changes.size();
            files += r.plan.files().size();
        }
        LinearLayout body = card();
        int p = Ui.dp(this, 18);
        body.setPadding(p, p, p, p);
        body.addView(Ui.text(this, todo.size() == 1 ? "Fix " + label(todo.get(0)) + "?" : "Fix " + todo.size() + " albums?", 20, Ui.TEXT, true));
        TextView t = Ui.text(this, (todo.size() == 1 ? "" : names(todo) + "\n\n")
                + changes + " tag changes in " + files + " files.\n\n"
                + "Only the tags change. Each file's music is fingerprinted before and after; if anything else changed, "
                + "the file is put back straight away.\n\n"
                + "Every fix is saved in Undo history, so you can put the old tags back later. "
                + "(Undo records are kept in Download/FixMyRips/undo, so they survive reinstalling the app.)", 15, Ui.SUB, false);
        t.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(t);
        frame(Screen.CONFIRM, scanRoot.getPath(), body,
                Ui.button(this, "Cancel", v -> showResults()),
                Ui.button(this, "Fix", true, v -> startFix(todo)));
    }

    private void startFix(List<Row> todo) {
        cancelled = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout body = Ui.column(this);
        TextView count = Ui.oneLine(this, "", 13, Ui.SUB, true);
        body.addView(count);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(todo.size());
        bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.CARD_HI));
        body.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 10)));
        ItemAdapter log = new ItemAdapter(this);
        LinearLayout box = card();
        ListView l = list(log);
        l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        box.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lp.topMargin = Ui.dp(this, 12);
        body.addView(box, lp);
        Button stop = Ui.button(this, "Stop", null);
        stop.setOnClickListener(v -> {
            cancelled = true;
            stop.setEnabled(false);
            stop.setText("Stopping after this album…");
        });
        frame(Screen.FIXING, "Fixing tags", body, stop);

        File undoDir = undoDir();
        new Thread(() -> {
            List<Row> fixedRows = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (int i = 0; i < todo.size() && !cancelled; i++) {
                Row r = todo.get(i);
                int n = i;
                runOnUiThread(() -> count.setText((n + 1) + " of " + todo.size() + " · " + r.album.dir.getName()));
                ItemAdapter.Item item;
                try {
                    Fixer.fix(r.plan, undoDir, System.currentTimeMillis());
                    fixedRows.add(r);
                    item = new ItemAdapter.Item(r.album.dir.getName(), describeChanges(r.plan), "Fixed", Ui.GOOD, null, false, null);
                } catch (Exception e) {
                    failed.add(r.album.dir.getName() + ": " + e.getMessage());
                    item = new ItemAdapter.Item(r.album.dir.getName(), e.getMessage(), "Not fixed", Ui.BAD, null, false, null);
                }
                r.plan = FixPlanner.plan(r.album, r.check);
                ItemAdapter.Item shown = item;
                runOnUiThread(() -> {
                    log.add(shown);
                    bar.setProgress(n + 1);
                });
            }
            runOnUiThread(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                showFixReport(fixedRows, failed, todo.size());
            });
        }, "fix").start();
    }

    private void showFixReport(List<Row> done, List<String> failed, int planned) {
        int fixed = done.size();
        LinearLayout body = card();
        int p = Ui.dp(this, 18);
        body.setPadding(p, p, p, p);
        String head = fixed == 1 ? "Fixed " + label(done.get(0)) : "Fixed " + fixed + " albums";
        body.addView(Ui.text(this, head, 20, fixed == 0 ? Ui.SUB : Ui.GOOD, true));
        StringBuilder b = new StringBuilder();
        if (fixed > 1) b.append(names(done)).append("\n\n");
        if (cancelled) b.append("Stopped after ").append(fixed + failed.size()).append(" of ").append(planned).append(".\n\n");
        if (!failed.isEmpty()) {
            b.append("Not fixed, nothing changed in these:\n");
            for (String f : failed) b.append("• ").append(f).append('\n');
            b.append('\n');
        }
        b.append("Now update your library on the Eversolo so it reads the new tags (it may start on its own).\n\n")
                .append("Changed your mind? Undo history puts the old tags back.");
        TextView t = Ui.text(this, b.toString(), 15, Ui.TEXT, false);
        t.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(t);
        frame(Screen.REPORT, scanRoot.getPath(), body,
                Ui.button(this, "Undo history", v -> showUndo()),
                Ui.button(this, "Back to results", true, v -> showResults()));
    }

    // ---------- undo history ----------

    private void showUndo() {
        File[] recs = undoDir().listFiles(f -> f.getName().endsWith(".json"));
        ItemAdapter adapter = new ItemAdapter(this);
        if (recs != null) {
            Arrays.sort(recs, (a, b) -> b.getName().compareTo(a.getName()));
            SimpleDateFormat when = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
            for (File rec : recs) {
                String[] d = Fixer.describe(rec);
                if (d == null) continue;
                File album = new File(d[0]);
                String artist = album.getParentFile() != null ? album.getParentFile().getName() + " · " : "";
                adapter.add(new ItemAdapter.Item(artist + album.getName(), when.format(new Date(Long.parseLong(d[1]))) + " · " + d[2] + " tag changes",
                        "Undo", Ui.ACCENT, null, false, rec));
            }
        }
        if (adapter.getCount() == 0) adapter.add(new ItemAdapter.Item("Nothing to undo", "Fixes you make show up here.", null, Ui.SUB, null, false, null));
        LinearLayout body = card();
        ListView l = list(adapter);
        l.setOnItemClickListener((parent, v, pos, id) -> {
            Object tag = adapter.getItem(pos).tag;
            if (tag instanceof File) confirmUndo((File) tag, adapter.getItem(pos).title);
        });
        body.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        frame(Screen.UNDO, "Undo history · newest first", body,
                Ui.button(this, "Back", true, v -> { if (scanRoot != null && !rows.isEmpty()) showResults(); else showBrowse(browseDir); }));
    }

    private void confirmUndo(File rec, String album) {
        String[] d = Fixer.describe(rec);
        String albumPath = d == null ? null : d[0];
        LinearLayout body = card();
        int p = Ui.dp(this, 18);
        body.setPadding(p, p, p, p);
        body.addView(Ui.text(this, "Put the old tags back on " + album + "?", 20, Ui.TEXT, true));
        TextView t = Ui.text(this, "Each file gets back exactly the tags it had before the fix. A file whose music has changed since "
                + "(for example after a re-rip) is left alone.", 15, Ui.SUB, false);
        t.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(t);
        Button go = Ui.button(this, "Undo", true, null);
        go.setOnClickListener(v -> {
            go.setEnabled(false);
            go.setText("Undoing…");
            new Thread(() -> {
                String msg;
                try {
                    Fixer.undo(rec);
                    msg = null;
                } catch (Exception e) {
                    msg = e.getMessage();
                }
                String m = msg;
                runOnUiThread(() -> {
                    if (m == null) {
                        for (Row r : rows) if (r.album.dir.getPath().equals(albumPath)) r.plan = FixPlanner.plan(r.album, r.check);
                        showUndone(album, d == null ? null : d[2]);
                    } else {
                        t.setText("Couldn't undo everything: " + m + "\n\nThe undo record is kept.");
                        t.setTextColor(Ui.BAD);
                        go.setText("Undo");
                        go.setEnabled(true);
                    }
                });
            }, "undo").start();
        });
        frame(Screen.CONFIRM, "Undo", body, Ui.button(this, "Cancel", v -> showUndo()), go);
    }

    /** After a successful undo: say plainly what happened, rather than dropping back to an empty list. */
    private void showUndone(String album, String changes) {
        LinearLayout body = card();
        int p = Ui.dp(this, 18);
        body.setPadding(p, p, p, p);
        body.addView(Ui.text(this, "Undone: " + album, 20, Ui.GOOD, true));
        TextView t = Ui.text(this, "All " + (changes != null ? changes + " " : "") + "tag changes were reversed. "
                + "Every file has exactly the tags it had before the fix, and the music was checked unchanged.\n\n"
                + "Now update your library on the Eversolo so it shows the old tags again (it may start on its own).", 15, Ui.TEXT, false);
        t.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(t);
        List<View> buttons = new ArrayList<>();
        buttons.add(Ui.button(this, "Undo history", v -> showUndo()));
        if (scanRoot != null && !rows.isEmpty()) buttons.add(Ui.button(this, "Back to results", true, v -> showResults()));
        frame(Screen.REPORT, "Undo", body, buttons.toArray(new View[0]));
    }

    /**
     * Undo records live in the shared Download folder, not app storage: an uninstall or a clean reinstall
     * wipes app storage, and with it the only way back (lost that way on the 2026-10-30 device test).
     */
    private static File undoDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FixMyRips/undo");
    }

    /** "Commodores · Anthology- The Best of the Commodores": artist (tags, else parent folder) and album folder. */
    private static String label(Row r) {
        String artist = r.artist();
        return (artist != null ? artist + " · " : "") + r.album.dir.getName();
    }

    /** One album per line, at most eight, then "and N more". */
    private static String names(List<Row> rows) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(8, rows.size()); i++) b.append(i > 0 ? "\n" : "").append("• ").append(label(rows.get(i)));
        if (rows.size() > 8) b.append("\n…and ").append(rows.size() - 8).append(" more");
        return b.toString();
    }

    private static String parentName(Album a) {
        File p = a.dir.getParentFile();
        return p == null ? null : p.getName();
    }

    // ---------- report ----------

    /** Plain-text report to the shared Download folder (never into the music folders). Null when it can't be written. */
    private File saveReport() {
        StringBuilder b = new StringBuilder();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        b.append("FixMyRips report, ").append(BuildConfig.VERSION_NAME).append('\n');
        b.append(now).append(" · ").append(scanRoot.getPath()).append('\n');
        if (cancelled) b.append("Stopped after ").append(rows.size()).append(" of ").append(planned).append(" albums.\n");
        b.append('\n');
        b.append("RE-RIP (only a new rip fixes these)\n");
        boolean any = false;
        for (Row r : rows) {
            if (r.plan.reRip.isEmpty()) continue;
            any = true;
            b.append("\n").append(r.artist() != null ? r.artist() + " / " : "").append(r.album.dir.getName()).append('\n');
            b.append("  ").append(r.album.dir.getPath()).append('\n');
            for (String p : r.plan.reRip) b.append("  - ").append(p).append('\n');
        }
        if (!any) b.append("  none\n");
        b.append("\nTAG FIXES NOT YET APPLIED\n");
        any = false;
        for (Row r : rows) {
            if (!r.plan.fixable()) continue;
            any = true;
            b.append("\n").append(r.artist() != null ? r.artist() + " / " : "").append(r.album.dir.getName()).append('\n');
            for (FixPlanner.Change c : r.plan.changes) b.append("  - ").append(c).append('\n');
        }
        if (!any) b.append("  none\n");
        b.append("\nLEFT ALONE\n");
        any = false;
        for (Row r : rows) {
            for (String note : r.plan.notes) {
                any = true;
                b.append("  ").append(r.album.dir.getName()).append(": ").append(note).append('\n');
            }
        }
        if (!any) b.append("  none\n");
        b.append("\nNOT CHECKED (nothing reliable to compare with)\n");
        any = false;
        for (Row r : rows) {
            if (r.check == null || r.check.skipped == null) continue;
            any = true;
            b.append("  ").append(r.artist() != null ? r.artist() + " / " : "").append(r.album.dir.getName())
                    .append(": ").append(r.check.skipped).append('\n');
        }
        if (!any) b.append("  none\n");
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File f = new File(dir, "FixMyRips-report-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".txt");
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(b.toString());
            }
            return f;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    // ---------- back ----------

    @Override
    public void onBackPressed() {
        switch (screen) {
            case BROWSE:
                if (browseDir != null) { goUp(); return; }
                break;
            case RESULTS:
            case UNDO:
                showBrowse(scanRoot != null ? scanRoot : browseDir);
                return;
            case ALBUM:
            case CONFIRM:
            case REPORT:
                showResults();
                return;
            case CHECKING:
            case FIXING:
                return; // Stop is explicit; back must not abandon a run half-way.
            default:
                break;
        }
        super.onBackPressed();
    }
}
