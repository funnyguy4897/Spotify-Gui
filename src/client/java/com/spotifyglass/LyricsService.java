package com.spotifyglass;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches synced lyrics from LRCLIB (free, no account) and answers "which line is it now?". */
public final class LyricsService {
    public record Line(double time, String text) {}

    public static final class Lyrics {
        final List<Line> lines;
        final double[] times;
        final boolean synced;
        final String note;

        Lyrics(List<Line> lines, boolean synced, String note) {
            this.lines = lines;
            this.synced = synced;
            this.note = note;
            this.times = lines.stream().mapToDouble(Line::time).toArray();
        }

        int indexAt(double t) {
            int i = Arrays.binarySearch(times, t);
            return i >= 0 ? i : -i - 2;
        }
    }

    /** What to show this frame. current is null during an intro or when there are no lyrics. */
    public record Frame(String current, String previous, String next, double progress,
                        double sinceStart, boolean synced, String note) {}

    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)]");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile String currentKey = "";
    private static volatile Lyrics current;
    private static volatile String note = "";

    private LyricsService() {}

    public static String note() {
        return note;
    }

    public static void request(String key, String title, String artist, String album, double length, boolean force) {
        currentKey = key;
        current = null;
        note = "searching for lyrics…";
        Thread t = new Thread(() -> {
            Lyrics l;
            try {
                l = fetch(title, artist, album, length, force);
            } catch (Exception e) {
                SpotifyGlassClient.LOGGER.warn("Lyrics lookup failed: {}", e.toString());
                l = new Lyrics(List.of(), false, "lyrics unavailable (offline?)");
            }
            if (key.equals(currentKey)) {
                current = l;
                note = l.note;
            }
        }, "spotifyglass-lyrics");
        t.setDaemon(true);
        t.start();
    }

    public static Frame frame(PlayerWatcher.State st, int offsetMs) {
        Lyrics l = current;
        double pos = st.positionNow() + offsetMs / 1000.0;
        if (l == null || l.lines.isEmpty()) return new Frame(null, null, null, 1, 99, false, note);
        int i = l.indexAt(pos);
        int n = l.lines.size();
        if (i < 0) return new Frame(null, null, l.lines.get(0).text(), 0, pos, l.synced, note);
        double t0 = l.times[i];
        double t1 = i + 1 < n ? l.times[i + 1] : t0 + 6;
        String text = l.lines.get(i).text();
        String cur = text.isBlank() ? "• • •" : text;
        String prev = i > 0 ? blankToNull(l.lines.get(i - 1).text()) : null;
        String next = i + 1 < n ? blankToNull(l.lines.get(i + 1).text()) : null;
        double prog = l.synced ? clamp((pos - t0) / (Math.max(0.4, t1 - t0) * 0.9)) : 1;
        return new Frame(cur, prev, next, prog, pos - t0, l.synced, note);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    // ── fetching ──

    private static Lyrics fetch(String title, String artist, String album, double length, boolean force) throws Exception {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("spotifyglass").resolve("lyrics");
        Files.createDirectories(dir);
        byte[] hash = MessageDigest.getInstance("SHA-1")
                .digest((artist + "\n" + title + "\n" + album).getBytes(StandardCharsets.UTF_8));
        Path cache = dir.resolve(HexFormat.of().formatHex(hash) + ".json");

        if (!force && Files.exists(cache)) {
            JsonElement cached = JsonParser.parseString(Files.readString(cache));
            long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis();
            boolean empty = !cached.isJsonObject() || cached.getAsJsonObject().size() == 0;
            if (!empty || ageMs < 3L * 24 * 3600 * 1000) return parse(cached, length);
        }

        JsonElement data = lrclib(title, artist, album, length);
        Files.writeString(cache, data == null ? "{}" : data.toString());
        return parse(data, length);
    }

    private static JsonElement lrclib(String title, String artist, String album, double length) throws Exception {
        StringBuilder q = new StringBuilder("https://lrclib.net/api/get?track_name=").append(enc(title))
                .append("&artist_name=").append(enc(artist));
        if (!album.isBlank()) q.append("&album_name=").append(enc(album));
        if (length > 0) q.append("&duration=").append(Math.round(length));
        JsonElement exact = getJson(q.toString());
        if (exact != null && exact.isJsonObject()) return exact;

        String firstArtist = artist.split(",")[0].trim();
        JsonElement search = getJson("https://lrclib.net/api/search?track_name=" + enc(cleanTitle(title))
                + "&artist_name=" + enc(firstArtist));
        if (search == null || !search.isJsonArray()) return null;
        JsonArray arr = search.getAsJsonArray();
        JsonObject best = null;
        double bestScore = Double.MAX_VALUE;
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            boolean synced = o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull();
            double dur = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0;
            double score = (synced ? 0 : 1000) + Math.abs(dur - length);
            if (score < bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best;
    }

    private static JsonElement getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "SpotifyGlass/1.0 (Minecraft Fabric mod)")
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return null;
        if (res.statusCode() != 200) throw new IOException("LRCLIB returned HTTP " + res.statusCode());
        return JsonParser.parseString(res.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String cleanTitle(String t) {
        return t.replaceAll("(?i)\\s+-\\s+.*\\b(remaster(ed)?|live|version|edit|mix|mono|stereo)\\b.*$", "")
                .replaceAll("(?i)\\s*[(\\[].*?(feat\\.|remaster|version|edit).*?[)\\]]", "")
                .trim();
    }

    private static Lyrics parse(JsonElement data, double length) {
        if (data == null || !data.isJsonObject() || data.getAsJsonObject().size() == 0)
            return new Lyrics(List.of(), false, "no lyrics found");
        JsonObject o = data.getAsJsonObject();
        if (o.has("instrumental") && !o.get("instrumental").isJsonNull() && o.get("instrumental").getAsBoolean())
            return new Lyrics(List.of(), false, "instrumental");

        if (o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull()) {
            List<Line> lines = new ArrayList<>();
            for (String raw : o.get("syncedLyrics").getAsString().split("\n")) {
                Matcher m = STAMP.matcher(raw);
                List<Double> stamps = new ArrayList<>();
                while (m.find()) stamps.add(Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2)));
                if (stamps.isEmpty()) continue;
                String text = STAMP.matcher(raw).replaceAll("").trim();
                for (double s : stamps) lines.add(new Line(s, text));
            }
            lines.sort((a, b) -> Double.compare(a.time(), b.time()));
            if (!lines.isEmpty()) return new Lyrics(lines, true, "synced lyrics");
        }

        if (o.has("plainLyrics") && !o.get("plainLyrics").isJsonNull()) {
            List<String> rows = new ArrayList<>();
            for (String r : o.get("plainLyrics").getAsString().split("\n")) if (!r.isBlank()) rows.add(r.trim());
            if (!rows.isEmpty()) {
                double len = length > 0 ? length : 180;
                double span = Math.max(30, len * 0.9);
                List<Line> lines = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) lines.add(new Line(len * 0.05 + span * i / rows.size(), rows.get(i)));
                return new Lyrics(lines, false, "unsynced lyrics");
            }
        }
        return new Lyrics(List.of(), false, "no lyrics found");
    }
}
