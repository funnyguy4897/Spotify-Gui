package com.spotifyglass;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Watches Linux's MPRIS media interface (through playerctl) for Spotify.
 * Runs on its own daemon thread; the game reads the latest snapshot.
 */
public final class PlayerWatcher {
    public record State(String status, String title, String artist, String album, double length,
                        String artUrl, double position, String trackId, long sampledNanos) {
        public boolean playing() {
            return "Playing".equals(status);
        }

        /** Playback position right now, interpolated between polls so lyrics move smoothly. */
        public double positionNow() {
            return playing() ? position + (System.nanoTime() - sampledNanos) / 1e9 : position;
        }

        public String trackKey() {
            return trackId != null && !trackId.isBlank() ? trackId : artist + "|" + title;
        }
    }

    private static final String SEP = "\u001f";
    private static final String FORMAT = String.join(SEP,
            "{{status}}", "{{xesam:title}}", "{{xesam:artist}}", "{{xesam:album}}",
            "{{mpris:length}}", "{{mpris:artUrl}}", "{{position}}", "{{mpris:trackid}}");

    private static volatile State state;
    private static volatile String error;

    private PlayerWatcher() {}

    /** Latest Spotify state, or null when Spotify isn't running or is stopped. */
    public static State state() {
        return state;
    }

    public static String error() {
        return error;
    }

    public static void start() {
        Thread t = new Thread(PlayerWatcher::loop, "spotifyglass-mpris");
        t.setDaemon(true);
        t.start();
    }

    private static String players() {
        String p = SpotifyGlassClient.CONFIG.players;
        return p == null || p.isBlank() ? "spotify" : p.replace(" ", "");
    }

    private static void loop() {
        while (true) {
            try {
                state = poll(state);
                error = null;
            } catch (IOException e) {
                state = null;
                error = "playerctl is not installed (sudo apt install playerctl)";
            } catch (Exception e) {
                state = null;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private static State poll(State prev) throws Exception {
        Process p = new ProcessBuilder("playerctl", "-p", players(), "metadata", "--format", FORMAT)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return null;
        }
        if (p.exitValue() != 0) return null;
        while (out.endsWith("\n") || out.endsWith("\r")) out = out.substring(0, out.length() - 1);
        String[] parts = Arrays.copyOf(out.split(SEP, -1), 8);
        for (int i = 0; i < parts.length; i++) if (parts[i] == null) parts[i] = "";

        String status = parts[0].trim();
        if (!status.equals("Playing") && !status.equals("Paused")) return null;

        long now = System.nanoTime();
        double length = num(parts[4]) / 1e6;
        double reported = num(parts[6]) / 1e6;
        String trackId = parts[7];

        // Keep our own smooth clock unless the reported position really jumped (seek, new track).
        double position = reported;
        if (prev != null && prev.playing() && status.equals("Playing") && prev.trackKey().equals(
                trackId.isBlank() ? parts[2] + "|" + parts[1] : trackId)) {
            double predicted = prev.positionNow();
            boolean stale = reported == 0 && predicted > 1.5; // some clients never report position
            if (stale || Math.abs(predicted - reported) < 0.35) position = predicted;
        }
        return new State(status, parts[1], parts[2], parts[3], length, parts[5], position, trackId, now);
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Runs a playerctl command such as "play-pause", "next" or "previous". */
    public static void command(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("playerctl");
        cmd.add("-p");
        cmd.add(players());
        cmd.addAll(Arrays.asList(args));
        try {
            new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException ignored) {
        }
    }
}
