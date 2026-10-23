package com.spotifyglass;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the synced lyric line out in the world:
 *  - horizon: huge text sitting on the skyline that moves with you like the sky does
 *  - tree:    text floating above the nearest tree's canopy, the old line falling like leaves
 */
public final class WorldLyrics {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static float smoothYaw = Float.NaN;
    private static long lastNanos = 0;

    private WorldLyrics() {}

    public static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        GlassConfig c = SpotifyGlassClient.CONFIG;
        if (mc.level == null || mc.player == null) return;
        if (!"horizon".equals(c.lyricMode) && !"tree".equals(c.lyricMode)) return;
        PlayerWatcher.State st = PlayerWatcher.state();
        if (st == null) return;

        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;

        LyricsService.Frame f = LyricsService.frame(st, c.lyricOffsetMs);
        Vec3 cam = ctx.worldState().cameraRenderState.pos;
        PoseStack ps = ctx.matrices();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        int accent = ArtManager.accent();
        int sung = Glass.lerpColor(0xFFFFFFFF, accent | 0xFF000000, 0.25f);
        int unsung = 0xFFC8C8D8;

        Vec3 tree = "tree".equals(c.lyricMode) ? TreeFinder.anchor() : null;
        if (tree != null) drawTree(mc, ps, buf, cam, tree, f, st, c, sung, unsung);
        else drawHorizon(mc, ps, buf, cam, f, st, c, dt, sung, unsung);
        buf.endBatch();
    }

    private static void drawHorizon(Minecraft mc, PoseStack ps, MultiBufferSource buf, Vec3 cam,
                                    LyricsService.Frame f, PlayerWatcher.State st, GlassConfig c,
                                    float dt, int sung, int unsung) {
        double dx, dz;
        if (c.horizonFollowLook) {
            float target = (float) Math.toRadians(mc.player.getYRot());
            if (Float.isNaN(smoothYaw)) smoothYaw = target;
            float diff = (float) Math.atan2(Math.sin(target - smoothYaw), Math.cos(target - smoothYaw));
            smoothYaw += diff * (1 - (float) Math.exp(-dt * 1.4f));
            dx = -Math.sin(smoothYaw);
            dz = Math.cos(smoothYaw);
        } else {
            double b = Math.toRadians(c.horizonBearing);
            dx = Math.sin(b);
            dz = -Math.cos(b);
        }
        float dist = Math.max(24f, Math.min(96f, mc.options.getEffectiveRenderDistance() * 16f * 0.7f));
        float theta = (float) Math.atan2(-dx, -dz);
        float scale = dist * 0.0045f * c.horizonSize;
        Font.DisplayMode mode = c.horizonThroughTerrain ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;

        String text = f.current();
        double rise = 1;
        double prog = f.progress();
        float alpha = 1f;
        if (text == null) { // intro or no lyrics: the song title rests on the horizon
            text = "♪  " + st.title();
            prog = 1;
            alpha = 0.7f;
        } else {
            rise = ease(f.sinceStart() / 0.7);
        }
        double elev = Math.toRadians(c.horizonElevation - (1 - rise) * 4);
        double rx = dx * dist, ry = Math.tan(elev) * dist, rz = dz * dist;

        // The previous line drifts up into the sky and fades
        if (f.previous() != null && f.sinceStart() < 1.2) {
            double k = ease(f.sinceStart() / 1.2);
            drawLines(ps, buf, mc.font, f.previous(), rx, ry, rz, theta, scale, (float) (-12 - 16 * k), 1,
                    (float) ((1 - k) * 0.8), mode, true, sung, unsung);
        }
        drawLines(ps, buf, mc.font, text, rx, ry, rz, theta, scale, 0, prog, (float) (alpha * rise), mode, true,
                sung, unsung);
        if (c.showNextLine && f.next() != null && f.current() != null) {
            drawLines(ps, buf, mc.font, f.next(), rx, ry, rz, theta, scale * 0.6f, 15, 0, 0.45f, mode, false,
                    sung, unsung);
        }
    }

    private static void drawTree(Minecraft mc, PoseStack ps, MultiBufferSource buf, Vec3 cam, Vec3 anchor,
                                 LyricsService.Frame f, PlayerWatcher.State st, GlassConfig c,
                                 int sung, int unsung) {
        double t = System.currentTimeMillis() / 1000.0;
        double ax = anchor.x + Math.sin(t * 0.8) * 0.06; // gentle sway with the leaves
        double ay = anchor.y + Math.sin(t * 1.3) * 0.04;
        double rx = ax - cam.x, ry = ay - cam.y, rz = anchor.z - cam.z;
        double hd = Math.max(0.5, Math.hypot(rx, rz));
        float theta = (float) Math.atan2(-rx / hd, -rz / hd);
        float scale = (0.018f + (float) hd * 0.0016f) * c.treeTextSize;
        Font.DisplayMode mode = Font.DisplayMode.NORMAL;

        String text = f.current();
        double prog = f.progress();
        float alpha = 1f;
        if (text == null) {
            text = "♪  " + st.title();
            prog = 1;
            alpha = 0.75f;
        } else {
            alpha = (float) ease(f.sinceStart() / 0.5);
        }

        // The previous line falls out of the tree like leaves
        if (f.previous() != null && f.sinceStart() < 1.6) {
            double k = f.sinceStart() / 1.6;
            drawLines(ps, buf, mc.font, f.previous(), rx, ry, rz, theta, scale, (float) (k * k * 70), 1,
                    (float) ((1 - k) * 0.85), mode, true, sung, unsung);
        }
        drawLines(ps, buf, mc.font, text, rx, ry, rz, theta, scale, (float) ((1 - alpha) * -8), prog, alpha,
                mode, true, sung, unsung);
        if (c.showNextLine && f.next() != null && f.current() != null) {
            drawLines(ps, buf, mc.font, f.next(), rx, ry, rz, theta, scale * 0.65f, 14, 0, 0.45f, mode, false,
                    sung, unsung);
        }
    }

    /**
     * Draws text centred on a point, facing the camera horizontally. The sung part is bright,
     * the rest dim, so it reads like karaoke. yOffset is in text units (positive = down).
     */
    private static void drawLines(PoseStack ps, MultiBufferSource buf, Font font, String text,
                                  double rx, double ry, double rz, float theta, float scale, float yOffset,
                                  double progress, float alpha, Font.DisplayMode mode, boolean growUp,
                                  int sungColor, int unsungColor) {
        if (text == null || text.isBlank() || alpha < 0.04f) return;
        List<String> lines = wrap(font, text, 240);
        int total = 0;
        for (String l : lines) total += l.length();
        int remaining = (int) Math.round(Math.max(0, Math.min(1, progress)) * total);

        ps.pushPose();
        ps.translate(rx, ry, rz);
        ps.mulPose(new Quaternionf().rotationY(theta));
        ps.scale(scale, -scale, scale);
        Matrix4f m = ps.last().pose();
        int n = lines.size();
        float y0 = yOffset - 9 - (growUp ? (n - 1) * 10 : 0);
        for (int i = 0; i < n; i++) {
            String line = lines.get(i);
            int k = Math.max(0, Math.min(line.length(), remaining));
            remaining -= line.length();
            String done = line.substring(0, k);
            String rest = line.substring(k);
            float x = -font.width(line) / 2f;
            float y = y0 + i * 10;
            int doneCol = Glass.withAlpha(sungColor, alpha);
            int restCol = Glass.withAlpha(unsungColor, alpha * 0.6f);
            if (!done.isEmpty() && (doneCol >>> 24) >= 8)
                font.drawInBatch(done, x, y, doneCol, false, m, buf, mode, 0, FULL_BRIGHT);
            if (!rest.isEmpty() && (restCol >>> 24) >= 8)
                font.drawInBatch(rest, x + font.width(done), y, restCol, false, m, buf, mode, 0, FULL_BRIGHT);
        }
        ps.popPose();
    }

    private static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = cur.isEmpty() ? word : cur + " " + word;
            if (!cur.isEmpty() && font.width(candidate) > maxWidth) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(candidate);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3);
    }
}
