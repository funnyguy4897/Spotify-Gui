package com.spotifyglass;

import java.io.File;
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
 *
 * When Minecraft runs inside a Flatpak launcher (Prism, the Flatpak Minecraft launcher, ...),
 * programs on the real system are hidden, so playerctl is reached with "flatpak-spawn --host".
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

    private static final String FLATPAK_ID = System.getenv("FLATPAK_ID");
    private static final boolean IN_FLATPAK = FLATPAK_ID != null || new File("/.flatpak-info").exists();

    private static volatile State state;
    private static volatile String error;
    private static volatile List<String> launcher; // the command prefix that worked

    private PlayerWatcher() {}

    /** Latest Spotify state, or null when Spotify isn't running or is stopped. */
    public static State state() {
        return state;
    }

    public static String error() {
        return error;
    }

    public static void start() {
        if (IN_FLATPAK) SpotifyGlassClient.LOGGER.info("Running inside a Flatpak ({}), using flatpak-spawn --host", FLATPAK_ID);
        Thread t = new Thread(PlayerWatcher::loop, "spotifyglass-mpris");
        t.setDaemon(true);
        t.start();
    }

    private static String players() {
        String p = SpotifyGlassClient.CONFIG.players;
        return p == null || p.isBlank() ? "spotify" : p.replace(" ", "");
    }

    /** Ways to start playerctl, in the order they're tried. */
    private static List<List<String>> candidates() {
        List<List<String>> c = new ArrayList<>();
        if (IN_FLATPAK) c.add(List.of("flatpak-spawn", "--host", "playerctl"));
        c.add(List.of("playerctl"));
        c.add(List.of("/usr/bin/playerctl"));
        c.add(List.of("/usr/local/bin/playerctl"));
        return c;
    }

    private static void loop() {
        while (true) {
            try {
                state = poll(state);
            } catch (Exception e) {
                state = null;
                if (error == null) error = "Spotify Glass error: " + e;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private record Result(int exit, String out, String err) {}

    /** Runs playerctl with the given arguments, finding a way to start it on first use. */
    private static Result run(List<String> args, long timeoutSec) throws Exception {
        List<List<String>> tries = launcher != null ? List.of(launcher) : candidates();
        IOException last = null;
        for (List<String> prefix : tries) {
            List<String> cmd = new ArrayList<>(prefix);
            cmd.addAll(args);
            Process p;
            try {
                p = new ProcessBuilder(cmd).start();
            } catch (IOException e) {
                last = e;
                continue;
            }
            String out, err;
            try (InputStream in = p.getInputStream(); InputStream es = p.getErrorStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "", "timeout");
            }
            launcher = prefix;
            return new Result(p.exitValue(), out, err);
        }
        throw last != null ? last : new IOException("playerctl not found");
    }

    private static State poll(State prev) throws Exception {
        Result r;
        try {
            r = run(List.of("-p", players(), "metadata", "--format", FORMAT), 3);
        } catch (IOException e) {
            error = IN_FLATPAK
                    ? "Can't reach playerctl from the Flatpak launcher. " + flatpakFix()
                    : "playerctl is not installed (sudo apt install playerctl)";
            return null;
        }

        if (r.exit() != 0) {
            String err = r.err().toLowerCase();
            boolean spawnDenied = launcher != null && launcher.get(0).equals("flatpak-spawn")
                    && (err.contains("org.freedesktop.flatpak") || err.contains("portal")
                    || err.contains("not allowed") || err.contains("permission")
                    || err.contains("not provided by any") || err.contains("serviceunknown"));
            boolean noPlayerctlOnHost = err.contains("no such file") || err.contains("failed to execute")
                    || err.contains("not found") && !err.contains("no players");
            if (spawnDenied) {
                error = "Your launcher is a Flatpak and needs one permission. " + flatpakFix();
            } else if (noPlayerctlOnHost) {
                error = "playerctl is not installed (sudo apt install playerctl)";
            } else {
                error = null; // normally "No players found": Spotify just isn't open
            }
            return null;
        }
        error = null;

        String out = r.out();
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

    private static String flatpakFix() {
        String id = FLATPAK_ID != null ? FLATPAK_ID : "<your launcher's app id>";
        return "In a terminal run: flatpak override --user --talk-name=org.freedesktop.Flatpak " + id
                + "  then fully close and reopen the launcher.";
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
        List<String> cmd = new ArrayList<>(launcher != null ? launcher : List.of("playerctl"));
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
