package com.generalsx.zerohour;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Game data downloaded after activation instead of bundled in the APK: asset
 * archives, published with site/upload-data.py and served by the site only to requests carrying
 * this device's license (site/src/data.ts).
 *
 * <p>The site's manifest lists every file (path, size, SHA-256, key). Files go straight into
 * {@code <external>/GameData} -- one copy, nothing to unpack -- through a {@code .part} file that
 * a dropped connection resumes, and each is checked against its SHA-256 before it replaces
 * anything. What was installed is recorded in {@code GameData/.zh-data.json}, so a new data
 * version downloads only the files that changed and removes the ones it no longer lists.
 */
final class DataPack {
    private DataPack() {}

    static final String SITE = "https://zerohour.housamkak.com";
    private static final String STATE_FILE = ".zh-data.json";
    private static final String PROGRESS_FILE = ".zh-data-progress.json";
    private static final String PREFS = "gx_datapack";
    private static final String KEY_LATEST = "latest_version";

    static final class Entry {
        String path;
        long size;
        String sha256;
        String key;
    }

    /** An optional pack (a mod): the player turns it on in ModsActivity; off, its files go. */
    static final class Pack {
        String id;
        String titleEn;
        String titleAr;
        String descriptionEn;
        String descriptionAr;
        boolean gameplay;  // changes what the simulation reads: online players must match
        long size;
        List<Entry> files = new ArrayList<>();
    }

    static final class Manifest {
        String version;
        List<Entry> files = new ArrayList<>();  // the base: every player gets these
        List<Pack> packs = new ArrayList<>();

        long totalSize() {
            long sum = 0;
            for (Entry e : files) {
                sum += e.size;
            }
            return sum;
        }
    }

    private static final String KEY_ENABLED_PACKS = "enabled_packs";
    private static final String KEY_MANIFEST = "manifest_json";

    /** The packs the player turned on (ModsActivity). */
    static java.util.Set<String> enabledPacks(Context ctx) {
        return new java.util.TreeSet<>(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENABLED_PACKS, new java.util.HashSet<>()));
    }

    static void setEnabledPacks(Context ctx, java.util.Set<String> ids) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_ENABLED_PACKS, new java.util.HashSet<>(ids)).apply();
    }

    /** The base files plus those of every pack the player turned on. */
    static List<Entry> wanted(Context ctx, Manifest m) {
        List<Entry> out = new ArrayList<>(m.files);
        java.util.Set<String> on = enabledPacks(ctx);
        for (Pack p : m.packs) {
            if (on.contains(p.id)) {
                out.addAll(p.files);
            }
        }
        return out;
    }

    /** The last manifest seen, for screens that list the packs offline; null before the first check. */
    static Manifest lastManifest(Context ctx) {
        String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MANIFEST, null);
        try {
            return json != null ? parseManifest(new JSONObject(json)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** True when an installed pack the player has on changes gameplay: online matches need the same. */
    static boolean gameplayPacksOn(Context ctx) {
        Manifest m = lastManifest(ctx);
        java.util.Set<String> installed = installedPacks(ctx);
        if (m == null) {
            return false;
        }
        for (Pack p : m.packs) {
            if (p.gameplay && installed.contains(p.id)) {
                return true;
            }
        }
        return false;
    }

    /** The packs the installed data includes (written by commit()). */
    static java.util.Set<String> installedPacks(Context ctx) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        JSONObject state = readState(ctx);
        JSONArray packs = state != null ? state.optJSONArray("packs") : null;
        for (int i = 0; packs != null && i < packs.length(); i++) {
            out.add(packs.optString(i));
        }
        return out;
    }

    /** True when the installed data is not what the manifest and the player's packs ask for. */
    static boolean outOfDate(Context ctx, Manifest m) {
        java.util.Set<String> want = new java.util.TreeSet<>();
        java.util.Set<String> on = enabledPacks(ctx);
        for (Pack p : m.packs) {
            if (on.contains(p.id)) {
                want.add(p.id);
            }
        }
        return !m.version.equals(installedVersion(ctx)) || !want.equals(installedPacks(ctx));
    }

    interface Progress {
        void onProgress(long done, long total, String file);
    }

    static File gameDataDir(Context ctx) {
        File root = ctx.getExternalFilesDir(null);
        return root != null ? new File(root, "GameData") : null;
    }

    /** The data version installed on this device, or null. */
    static String installedVersion(Context ctx) {
        JSONObject state = readState(ctx);
        return state != null ? state.optString("version", null) : null;
    }

    /** The data version the last check saw published, or null before the first check. */
    static String latestVersion(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LATEST, null);
    }

    /** True when the last check saw a data version other than the installed one. */
    static boolean updateAvailable(Context ctx) {
        String latest = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LATEST, null);
        String installed = installedVersion(ctx);
        if (latest == null || installed == null) {
            return false;
        }
        // A new data version, or packs turned on or off since the last install.
        Manifest m = lastManifest(ctx);
        return m != null ? outOfDate(ctx, m) : !latest.equals(installed);
    }

    /** Blocking. Fetches the manifest and remembers its version for updateAvailable(). */
    static Manifest fetchManifest(Context ctx) throws IOException {
        String license = LicenseGate.storedLicense(ctx);
        if (license == null) {
            throw new IOException("not activated");
        }
        HttpURLConnection conn = open(SITE + "/api/data/manifest", license);
        try {
            int status = conn.getResponseCode();
            if (status == 404) {
                return null;  // nothing published yet
            }
            if (status != 200) {
                throw new IOException("HTTP " + status);
            }
            String raw = readAll(conn.getInputStream());
            Manifest m = parseManifest(new JSONObject(raw));
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LATEST, m.version).putString(KEY_MANIFEST, raw).apply();
            return m;
        } catch (org.json.JSONException e) {
            throw new IOException("bad manifest", e);
        } finally {
            conn.disconnect();
        }
    }

    private static Manifest parseManifest(JSONObject json) throws org.json.JSONException, IOException {
        Manifest m = new Manifest();
        m.version = json.getString("version");
        parseFiles(json.getJSONArray("files"), m.files);
        JSONArray packs = json.optJSONArray("packs");
        for (int i = 0; packs != null && i < packs.length(); i++) {
            JSONObject p = packs.getJSONObject(i);
            Pack pack = new Pack();
            pack.id = p.getString("id");
            JSONObject title = p.optJSONObject("title");
            JSONObject description = p.optJSONObject("description");
            pack.titleEn = title != null ? title.optString("en", pack.id) : pack.id;
            pack.titleAr = title != null ? title.optString("ar", pack.titleEn) : pack.titleEn;
            pack.descriptionEn = description != null ? description.optString("en", "") : "";
            pack.descriptionAr = description != null ? description.optString("ar", pack.descriptionEn) : pack.descriptionEn;
            pack.gameplay = p.optBoolean("gameplay", false);
            pack.size = p.optLong("size", 0);
            parseFiles(p.getJSONArray("files"), pack.files);
            m.packs.add(pack);
        }
        return m;
    }

    private static void parseFiles(JSONArray files, List<Entry> out) throws org.json.JSONException, IOException {
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            Entry e = new Entry();
            e.path = f.getString("path");
            e.size = f.getLong("size");
            e.sha256 = f.getString("sha256").toLowerCase(Locale.ROOT);
            e.key = f.getString("key");
            if (e.path.contains("..") || e.path.startsWith("/")) {
                throw new IOException("bad path in manifest: " + e.path);
            }
            out.add(e);
        }
    }

    /**
     * The manifest's files this device does not already have, unchanged: neither in the installed
     * state nor finished earlier in an interrupted download of this same data version.
     */
    static List<Entry> missing(Context ctx, Manifest m) {
        Map<String, String> installed = installedFiles(ctx);
        Map<String, String> finished = finishedFiles(ctx, m.version);
        File dir = gameDataDir(ctx);
        List<Entry> out = new ArrayList<>();
        for (Entry e : wanted(ctx, m)) {
            File local = dir != null ? new File(dir, e.path) : null;
            boolean same = local != null && local.isFile() && local.length() == e.size
                && (e.sha256.equals(installed.get(e.path)) || e.sha256.equals(finished.get(e.path)));
            if (!same) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * True while a download has started and not completed. The game must not start then: the
     * folder can already hold INIZH.big and pass the game-folder check with half the files.
     */
    static boolean downloadIncomplete(Context ctx) {
        File dir = gameDataDir(ctx);
        return dir != null && new File(dir, PROGRESS_FILE).isFile();
    }

    static long bytesOf(List<Entry> entries) {
        long sum = 0;
        for (Entry e : entries) {
            sum += e.size;
        }
        return sum;
    }

    /**
     * Blocking. Downloads every file in {@code todo}, then records {@code m} as installed and
     * deletes files the previous version had that {@code m} does not list.
     */
    static void install(Context ctx, Manifest m, List<Entry> todo, Progress progress) throws IOException {
        String license = LicenseGate.storedLicense(ctx);
        File dir = gameDataDir(ctx);
        if (license == null || dir == null) {
            throw new IOException("not activated or no storage");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        // Every finished file is recorded at once (PROGRESS_FILE), so an interruption -- the
        // app closed, the phone asleep, the connection gone -- loses at most the unfinished
        // part of one file, and that part resumes over Range.
        Map<String, String> finished = finishedFiles(ctx, m.version);
        writeProgress(ctx, m.version, finished);
        long total = bytesOf(todo);
        long done = 0;
        for (Entry e : todo) {
            File target = new File(dir, e.path);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            File part = new File(target.getPath() + ".part");
            downloadWithRetries(license, e, part, done, total, progress);
            if (target.exists() && !target.delete()) {
                throw new IOException("cannot replace " + target);
            }
            if (!part.renameTo(target)) {
                throw new IOException("cannot move " + part);
            }
            finished.put(e.path, e.sha256);
            writeProgress(ctx, m.version, finished);
            done += e.size;
        }
        commit(ctx, m);
    }

    /**
     * Makes {@code m} the installed version: deletes what the previous version installed and
     * {@code m} no longer lists (only files this app put there; a mod or asset withdrawn from the
     * published data leaves the phone too), then records {@code m}. Also what a release that only
     * removes files needs, since it has nothing to download.
     */
    static void commit(Context ctx, Manifest m) throws IOException {
        File dir = gameDataDir(ctx);
        if (dir == null) {
            throw new IOException("no storage");
        }
        Map<String, String> before = installedFiles(ctx);
        for (Entry e : wanted(ctx, m)) {
            before.remove(e.path);
        }
        for (String obsolete : before.keySet()) {
            File f = new File(dir, obsolete);
            if (f.isFile() && !f.delete()) {
                android.util.Log.w("GXDataPack", "could not remove " + obsolete);
            }
        }
        writeState(ctx, m);
        new File(dir, PROGRESS_FILE).delete();
    }

    // A dropped connection is retried before giving up; each attempt resumes the .part file.
    private static final long[] RETRY_DELAYS_MS = { 2000, 5000, 15000, 30000 };

    private static void downloadWithRetries(String license, Entry e, File part, long doneBefore, long total,
                                            Progress progress) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                downloadFile(license, e, part, doneBefore, total, progress);
                return;
            } catch (IOException err) {
                if (attempt >= RETRY_DELAYS_MS.length) {
                    throw err;
                }
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw err;
                }
            }
        }
    }

    private static Map<String, String> finishedFiles(Context ctx, String version) {
        Map<String, String> out = new HashMap<>();
        File dir = gameDataDir(ctx);
        File file = dir != null ? new File(dir, PROGRESS_FILE) : null;
        if (file == null || !file.isFile()) {
            return out;
        }
        try (InputStream in = new FileInputStream(file)) {
            JSONObject json = new JSONObject(readAll(in));
            if (!version.equals(json.optString("version"))) {
                return out;  // progress of another data version: nothing in it counts
            }
            JSONObject files = json.optJSONObject("files");
            if (files != null) {
                java.util.Iterator<String> keys = files.keys();
                while (keys.hasNext()) {
                    String path = keys.next();
                    out.put(path, files.optString(path));
                }
            }
        } catch (Exception e) {
            return new HashMap<>();
        }
        return out;
    }

    private static void writeProgress(Context ctx, String version, Map<String, String> finished) throws IOException {
        try {
            JSONObject files = new JSONObject();
            for (Map.Entry<String, String> f : finished.entrySet()) {
                files.put(f.getKey(), f.getValue());
            }
            JSONObject json = new JSONObject().put("version", version).put("files", files);
            File dir = gameDataDir(ctx);
            File tmp = new File(dir, PROGRESS_FILE + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            // Rename, so an interruption never leaves a half-written record behind.
            if (!tmp.renameTo(new File(dir, PROGRESS_FILE))) {
                throw new IOException("cannot record download progress");
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    private static void downloadFile(String license, Entry e, File part, long doneBefore, long total,
                                     Progress progress) throws IOException {
        long have = part.isFile() ? part.length() : 0;
        if (have > e.size) {
            part.delete();
            have = 0;
        }
        if (have < e.size) {
            HttpURLConnection conn = open(SITE + "/" + encodePath(e.key), license);
            if (have > 0) {
                conn.setRequestProperty("Range", "bytes=" + have + "-");
            }
            try {
                int status = conn.getResponseCode();
                boolean resumed = status == 206;
                if (status != 200 && !resumed) {
                    throw new IOException("HTTP " + status + " for " + e.path);
                }
                if (!resumed) {
                    have = 0;  // the server sent the whole file: start the part over
                }
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(part, resumed)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    long lastReport = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        have += n;
                        if (have > e.size) {
                            throw new IOException("too much data for " + e.path);
                        }
                        if (have - lastReport > 1024 * 1024 || have == e.size) {
                            lastReport = have;
                            progress.onProgress(doneBefore + have, total, e.path);
                        }
                    }
                }
            } finally {
                conn.disconnect();
            }
        }
        if (part.length() != e.size || !e.sha256.equals(sha256(part))) {
            part.delete();
            throw new IOException("checksum mismatch for " + e.path);
        }
    }

    private static HttpURLConnection open(String url, String license) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("X-ZH-License", license);
        conn.setRequestProperty("User-Agent", "ZHCommander-Data");
        return conn;
    }

    private static String encodePath(String key) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String seg : key.split("/")) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    private static Map<String, String> installedFiles(Context ctx) {
        Map<String, String> out = new HashMap<>();
        JSONObject state = readState(ctx);
        JSONArray files = state != null ? state.optJSONArray("files") : null;
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f != null) {
                    out.put(f.optString("path"), f.optString("sha256"));
                }
            }
        }
        return out;
    }

    private static JSONObject readState(Context ctx) {
        File dir = gameDataDir(ctx);
        File file = dir != null ? new File(dir, STATE_FILE) : null;
        if (file == null || !file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            return new JSONObject(readAll(in));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeState(Context ctx, Manifest m) throws IOException {
        try {
            JSONObject state = new JSONObject();
            state.put("version", m.version);
            JSONArray files = new JSONArray();
            for (Entry e : wanted(ctx, m)) {
                files.put(new JSONObject().put("path", e.path).put("sha256", e.sha256).put("size", e.size));
            }
            state.put("files", files);
            JSONArray packs = new JSONArray();
            java.util.Set<String> on = enabledPacks(ctx);
            for (Pack p : m.packs) {
                if (on.contains(p.id)) {
                    packs.put(p.id);
                }
            }
            state.put("packs", packs);
            try (OutputStream out = new FileOutputStream(new File(gameDataDir(ctx), STATE_FILE))) {
                out.write(state.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toString("UTF-8");
    }
}
