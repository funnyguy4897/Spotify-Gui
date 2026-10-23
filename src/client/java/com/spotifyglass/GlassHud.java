package com.spotifyglass;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** The rounded liquid-glass "now playing" card. */
public final class GlassHud {
    public static boolean visible = true;

    private static final int BASE_W = 186;
    private static final int BASE_H = 58;
    private static float fade = 0f;
    private static long lastNanos = 0;

    private GlassHud() {}

    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        GlassConfig c = SpotifyGlassClient.CONFIG;
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;

        PlayerWatcher.State st = PlayerWatcher.state();
        boolean show = visible && st != null && !mc.options.hideGui;
        fade += ((show ? 1f : 0f) - fade) * Math.min(1f, dt * 8f);
        if (fade < 0.02f || st == null) return;

        LyricsService.Frame lf = LyricsService.frame(st, c.lyricOffsetMs);
        boolean cardLyric = "off".equals(c.lyricMode);
        float s = Math.max(0.5f, Math.min(3f, c.scale));
        int w = Math.round(BASE_W * s);
        int h = Math.round((cardLyric ? BASE_H + 13 : BASE_H) * s);
        int r = Math.min(Math.round(c.radius * s), h / 2);
        int x = c.corner.endsWith("left") ? c.offsetX : g.guiWidth() - w - c.offsetX;
        int y = c.corner.startsWith("top") ? c.offsetY : g.guiHeight() - h - c.offsetY;
        float t = (System.currentTimeMillis() % 1_000_000L) / 1000f;
        float rim = c.rimStrength / 100f;
        boolean solid = fade > 0.95f; // textures can't fade, so they only appear once fully shown

        // Soft drop shadow
        for (int i = 4; i >= 1; i--) {
            Glass.roundedSolid(g, x - i, y - i + 2, w + 2 * i, h + 2 * i, r + i, Glass.argb(0.07f * fade, 0));
        }

        // Backdrop: blurred cover art seen "through" the glass, or a dark smoky fill
        if (c.albumBackdrop && ArtManager.ready() && solid) {
            Glass.roundedBlit(g, ArtManager.BACKDROP, x, y, w, h, r, ArtManager.BD_W, ArtManager.BD_H);
        } else {
            Glass.roundedSolid(g, x, y, w, h, r, Glass.argb(0.68f * fade, 0x15141F));
        }

        // Tint: stronger at the top, like light pooling in thick glass
        int tintRgb = c.tintRgb();
        float ta = c.tintAlpha / 255f;
        Glass.rounded(g, x, y, w, h, r, row -> {
            float f = row / (float) h;
            return Glass.argb(ta * (1.4f - 0.9f * f) * fade, tintRgb);
        });

        // Top sheen
        int sheenH = Math.round(h * 0.45f);
        Glass.rounded(g, x, y, w, h, r, row -> {
            if (row >= sheenH) return 0;
            float k = 1 - row / (float) sheenH;
            return Glass.argb(0.20f * rim * k * k * fade, 0xFFFFFF);
        });

        // Bottom bounce light
        Glass.rounded(g, x, y, w, h, r, row -> {
            float k = (row - h * 0.7f) / (h * 0.3f);
            return k <= 0 ? 0 : Glass.argb(0.08f * rim * k * fade, 0xFFFFFF);
        });

        // Liquid sheen: a slanted band of light that drifts across slowly
        if (c.liquidSheen) {
            float cycle = (t * 0.12f) % 1.6f - 0.3f;
            int band = Math.round(16 * s);
            for (int i = 0; i < h; i++) {
                int ins = Glass.inset(i, h, r);
                int bx = x + Math.round(cycle * w) + Math.round(i * 0.6f);
                int x1 = Math.max(x + ins, bx), x2 = Math.min(x + w - ins, bx + band);
                if (x2 > x1) g.fill(x1, y + i, x2, y + i + 1, Glass.argb(0.06f * rim * fade, 0xFFFFFF));
            }
        }

        // Chromatic fringe just inside the left and right edges
        if (c.chroma > 0) {
            float ch = c.chroma / 100f;
            for (int i = r; i < h - r; i++) {
                g.fill(x + 1, y + i, x + 2, y + i + 1, Glass.argb(0.35f * ch * fade, 0xFF5A82));
                g.fill(x + w - 2, y + i, x + w - 1, y + i + 1, Glass.argb(0.35f * ch * fade, 0x5AAFFF));
            }
        }

        // Specular rim
        Glass.rim(g, x, y, w, h, r,
                Glass.argb(0.85f * rim * fade, 0xFFFFFF),
                Glass.argb(0.25f * rim * fade, 0xFFFFFF),
                Glass.argb(0.45f * rim * fade, 0xFFFFFF));

        // ── content ──
        Font font = mc.font;
        int pad = Math.round(7 * s);
        int art = Math.round(44 * s);
        int ax = x + pad, ay = y + pad;
        int ar = Math.round(6 * s);
        if (ArtManager.ready() && solid) {
            Glass.roundedBlit(g, ArtManager.ART, ax, ay, art, art, ar, ArtManager.ART_SIZE, ArtManager.ART_SIZE);
        } else {
            int acc = ArtManager.accent();
            Glass.rounded(g, ax, ay, art, art, ar, row -> Glass.withAlpha(
                    Glass.lerpColor(acc, 0xFF201A33, row / (float) art), 0.9f * fade));
            text(g, font, "♪", ax + art / 2 - Math.round(3 * s), ay + art / 2 - Math.round(5 * s), s * 1.3f,
                    Glass.argb(0.8f * fade, 0xFFFFFF));
        }
        Glass.rim(g, ax, ay, art, art, ar, Glass.argb(0.35f * fade, 0xFFFFFF),
                Glass.argb(0.12f * fade, 0xFFFFFF), Glass.argb(0.2f * fade, 0xFFFFFF));

        int tx = ax + art + pad;
        int tw = x + w - pad - tx;
        text(g, font, trim(font, st.title(), tw / s), tx, y + Math.round(10 * s), s,
                Glass.argb(fade, 0xFFFFFF));
        text(g, font, trim(font, st.artist(), tw / (s * 0.85f)), tx, y + Math.round(22 * s), s * 0.85f,
                Glass.argb(0.72f * fade, 0xFFFFFF));

        // Progress bar
        double length = st.length();
        double pos = st.positionNow();
        float prog = length > 0 ? (float) Math.max(0, Math.min(1, pos / length)) : 0;
        int by = y + Math.round(36 * s);
        int bh = Math.max(2, Math.round(3 * s));
        Glass.roundedSolid(g, tx, by, tw, bh, bh / 2, Glass.argb(0.28f * fade, 0x000000));
        int fw = Math.max(bh, Math.round(tw * prog));
        Glass.roundedSolid(g, tx, by, fw, bh, bh / 2, Glass.argb(0.92f * fade, 0xFFFFFF));

        // Times and play state
        float ts = s * 0.7f;
        int tyy = by + bh + Math.round(4 * s);
        int timeCol = Glass.argb(0.6f * fade, 0xFFFFFF);
        text(g, font, fmt(pos), tx, tyy, ts, timeCol);
        String len = fmt(length);
        text(g, font, len, tx + tw - Math.round(font.width(len) * ts), tyy, ts, timeCol);
        int cx = tx + tw / 2, cy = tyy + Math.round(3 * s);
        int icon = Glass.argb(0.85f * fade, 0xFFFFFF);
        int ih = Math.max(4, Math.round(6 * s));
        if (st.playing()) {
            int bw = Math.max(1, Math.round(2 * s));
            g.fill(cx - bw - 1, cy - ih / 2, cx - 1, cy + ih / 2, icon);
            g.fill(cx + 1, cy - ih / 2, cx + bw + 1, cy + ih / 2, icon);
        } else {
            for (int i = 0; i < ih; i++) {
                int half = Math.min(i, ih - 1 - i);
                g.fill(cx - ih / 3, cy - ih / 2 + i, cx - ih / 3 + half + 1, cy - ih / 2 + i + 1, icon);
            }
        }

        // When world lyrics are off, the current line lives on the card instead
        if (cardLyric) {
            String line = lf.current() != null ? lf.current() : lf.note();
            if (line != null && !line.isBlank()) {
                float ls = s * 0.8f;
                int lw = w - pad * 2;
                text(g, font, trim(font, line, lw / ls), x + pad, y + Math.round(57 * s), ls,
                        Glass.argb(0.9f * fade, 0xFFFFFF));
            }
        }
    }

    private static void text(GuiGraphics g, Font font, String str, int x, int y, float scale, int color) {
        if (str == null || str.isEmpty() || (color >>> 24) < 8) return;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.drawString(font, str, 0, 0, color, false);
        g.pose().popMatrix();
    }

    private static String trim(Font font, String s, float maxWidth) {
        if (s == null) return "";
        int max = Math.max(0, (int) maxWidth);
        if (font.width(s) <= max) return s;
        return font.plainSubstrByWidth(s, Math.max(0, max - font.width("…"))) + "…";
    }

    private static String fmt(double seconds) {
        int sec = Math.max(0, (int) seconds);
        return sec / 60 + ":" + String.format("%02d", sec % 60);
    }
}
