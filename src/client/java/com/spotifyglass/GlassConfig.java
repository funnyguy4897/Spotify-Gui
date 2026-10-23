package com.spotifyglass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Everything you can customise. Saved to .minecraft/config/spotifyglass.json. */
public class GlassConfig {
    // Card
    public boolean cardVisible = true;
    public boolean autoShow = true;          // pop the card up when Spotify starts
    public String corner = "top_right";      // top_left, top_right, bottom_left, bottom_right
    public int offsetX = 8;
    public int offsetY = 8;
    public float scale = 1.0f;               // 0.5 - 3.0
    public int radius = 10;                  // corner radius in GUI pixels

    // Liquid glass
    public String tint = "#FFFFFF";
    public int tintAlpha = 34;               // 0 - 255
    public int rimStrength = 70;             // 0 - 100, bright specular edge
    public int chroma = 35;                  // 0 - 100, red/blue colour fringe on the edges
    public boolean albumBackdrop = true;     // blurred album art behind the glass
    public boolean liquidSheen = true;       // slow moving light band

    // Lyrics in the world
    public String lyricMode = "horizon";     // horizon, tree, off
    public boolean horizonFollowLook = true; // lyrics drift to wherever you're facing
    public float horizonBearing = 270f;      // compass degrees when not following (270 = west, the sunset)
    public float horizonElevation = 3f;      // degrees above the horizon
    public float horizonSize = 1.0f;
    public boolean horizonThroughTerrain = true; // draw over mountains like a skybox
    public float treeTextSize = 1.0f;
    public int treeSearchRadius = 16;
    public boolean showNextLine = true;
    public int lyricOffsetMs = 0;

    // Detection
    public String players = "spotify,spotifyd,ncspot";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("spotifyglass.json");
    }

    public static GlassConfig load() {
        Path p = path();
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p)) {
                GlassConfig c = GSON.fromJson(r, GlassConfig.class);
                if (c != null) {
                    c.save(); // writes any new fields added by updates
                    return c;
                }
            } catch (Exception e) {
                SpotifyGlassClient.LOGGER.warn("Could not read {}, using defaults", p, e);
            }
        }
        GlassConfig c = new GlassConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path())) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            SpotifyGlassClient.LOGGER.warn("Could not save config", e);
        }
    }

    public int tintRgb() {
        try {
            String s = tint.startsWith("#") ? tint.substring(1) : tint;
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (Exception e) {
            return 0xFFFFFF;
        }
    }
}
