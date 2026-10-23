package com.spotifyglass;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** The liquid-glass "now playing" card and the on-screen lyrics. */
public final class GlassHud {
    public static boolean visible = true;

    private static final int BASE_W = 186;
    private static final int BASE_H = 58;
    private static float fade = 0f;
    private static float lyricFade = 0f;
    private static long lastNanos = 0;

    /** Last drawn on-screen lyrics box, so the editor can tell when you grab it. */
    static int lyricBoxX, lyricBoxY, lyricBoxW, lyricBoxH;

    private GlassHud() {}

    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof EditorScreen) return; // the editor draws its own live preview
        GlassConfig c = SpotifyGlassClient.CONFIG;
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;

        PlayerWatcher.State st = PlayerWatcher.state();
        boolean present = st != null && !mc.options.hideGui;
        float k = Math.min(1f, dt * 8f);
        fade += ((present && visible ? 1f : 0f) - fade) * k;
        lyricFade += ((present ? 1f : 0f) - lyricFade) * k;
        if (st == null) return;

        LyricsService.Frame lf = LyricsService.frame(st, c.lyricOffsetMs);
        if (fade > 0.02f) drawCard(g, st, lf, fade);
        if ("screen".equals(c.lyricMode) && lyricFade > 0.02f) drawLyrics(g, st, lf, lyricFade);
    }

    // ── card ──

    /** x, y, width, height of the card in GUI pixels. */
    static int[] cardBox(int sw, int sh) {
        GlassConfig c = SpotifyGlassClient.CONFIG;
        float s = Math.max(0.5f, Math.min(3f, c.scale));
        int w = Math.round(BASE_W * s);
        int h = Math.round(("off".equals(c.lyricMode) ? BASE_H + 13 : BASE_H) * s);
        int x, y;
        if (c.cardX < 0 || c.cardY < 0) {
            x = c.corner.endsWith("left") ? c.offsetX : sw - w - c.offsetX;
            y = c.corner.startsWith("top") ? c.offsetY : sh - h - c.offsetY;
        } else {
            x = Math.round(c.cardX * sw);
            y = Math.round(c.cardY * sh);
        }
        x = Math.max(0, Math.min(sw - w, x));
        y = Math.max(0, Math.min(sh - h, y));
        return new int[]{x, y, w, h};
    }

    static void drawCard(GuiGraphics g, PlayerWatcher.State st, LyricsService.Frame lf, float fade) {
        Minecraft mc = Minecraft.getInstance();
        GlassConfig c = SpotifyGlassClient.CONFIG;
        int[] box = cardBox(g.guiWidth(), g.guiHeight());
        int x = box[0], y = box[1], w = box[2], h = box[3];
        float s = Math.max(0.5f, Math.min(3f, c.scale));
        int r = Math.min(Math.round(c.radius * s), h / 2);
        float t = (System.currentTimeMillis() % 1_000_000L) / 1000f;
        float rim = c.rimStrength / 100f;
        boolean solid = fade > 0.95f; // textures can't fade, so they appear once fully shown

        // Soft drop shadow
        for (int i = 4; i >= 1; i--) {
            Glass.roundedSolid(g, x - i, y - i + 2, w + 2 * i, h + 2 * i, r + i, Glass.argb(0.07f * fade, 0));
        }

        // Backdrop
        boolean drewBackdrop = false;
        if ("blur".equals(c.backdrop) && !BackdropBlur.failed()) {
            BackdropBlur.capture(x, y, w, h, c.blurStrength);
            if (solid && BackdropBlur.ready()) {
                Glass.roundedBlit(g, BackdropBlur.ID, x, y, w, h, r, BackdropBlur.width(), BackdropBlur.height());
                drewBackdrop = true;
            }
        } else if ("album".equals(c.backdrop) && solid && ArtManager.ready()) {
            Glass.roundedBlit(g, ArtManager.BACKDROP, x, y, w, h, r, ArtManager.BD_W, ArtManager.BD_H);
            drewBackdrop = true;
        }
        if (!drewBackdrop) {
            Glass.roundedSolid(g, x, y, w, h, r, Glass.argb(0.68f * fade, 0x15141F));
        } else {
            // Gentle darkening so white text stays readable over bright scenery
            Glass.roundedSolid(g, x, y, w, h, r, Glass.argb(0.22f * fade, 0x0A0A12));
        }

        // Tint: the cover's colours, or your own colour
        if (c.albumTint && ArtManager.ready()) {
            int top = ArtManager.vivid() & 0xFFFFFF, bottom = ArtManager.accent() & 0xFFFFFF;
            float ata = c.albumTintAlpha / 255f;
            Glass.rounded(g, x, y, w, h, r, row -> {
                float f = row / (float) h;
                int rgb = Glass.lerpColor(0xFF000000 | top, 0xFF000000 | bottom, f) & 0xFFFFFF;
                return Glass.argb(ata * (1.15f - 0.4f * f) * fade, rgb);
            });
        }
        int tintRgb = c.tintRgb();
        float ta = c.tintAlpha / 255f;
        Glass.rounded(g, x, y, w, h, r, row -> {
            float f = row / (float) h;
            return Glass.argb(ta * (1.4f - 0.9f * f) * fade, tintRgb);
        });

        // Top sheen and bottom bounce light
        int sheenH = Math.round(h * 0.45f);
        Glass.rounded(g, x, y, w, h, r, row -> {
            if (row >= sheenH) return 0;
            float k = 1 - row / (float) sheenH;
            return Glass.argb(0.20f * rim * k * k * fade, 0xFFFFFF);
        });
        Glass.rounded(g, x, y, w, h, r, row -> {
            float k = (row - h * 0.7f) / (h * 0.3f);
            return k <= 0 ? 0 : Glass.argb(0.08f * rim * k * fade, 0xFFFFFF);
        });

        // Liquid sheen: a slanted band of light drifting across
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

        // Chromatic fringe
        if (c.chroma > 0) {
            float ch = c.chroma / 100f;
            for (int i = r; i < h - r; i++) {
                g.fill(x + 1, y + i, x + 2, y + i + 1, Glass.argb(0.35f * ch * fade, 0xFF5A82));
                g.fill(x + w - 2, y + i, x + w - 1, y + i + 1, Glass.argb(0.35f * ch * fade, 0x5AAFFF));
            }
        }

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
                    Glass.argb(0.8f * fade, 0xFFFFFF), false);
        }
        Glass.rim(g, ax, ay, art, art, ar, Glass.argb(0.35f * fade, 0xFFFFFF),
                Glass.argb(0.12f * fade, 0xFFFFFF), Glass.argb(0.2f * fade, 0xFFFFFF));

        int tx = ax + art + pad;
        int tw = x + w - pad - tx;
        text(g, font, trim(font, st.title(), tw / s), tx, y + Math.round(10 * s), s,
                Glass.argb(fade, 0xFFFFFF), true);
        text(g, font, trim(font, st.artist(), tw / (s * 0.85f)), tx, y + Math.round(22 * s), s * 0.85f,
                Glass.argb(0.8f * fade, 0xFFFFFF), true);

        double length = st.length();
        double pos = st.positionNow();
        float prog = length > 0 ? (float) Math.max(0, Math.min(1, pos / length)) : 0;
        int by = y + Math.round(36 * s);
        int bh = Math.max(2, Math.round(3 * s));
        Glass.roundedSolid(g, tx, by, tw, bh, bh / 2, Glass.argb(0.3f * fade, 0x000000));
        int fw = Math.max(bh, Math.round(tw * prog));
        Glass.roundedSolid(g, tx, by, fw, bh, bh / 2, Glass.argb(0.92f * fade, 0xFFFFFF));

        float ts = s * 0.7f;
        int tyy = by + bh + Math.round(4 * s);
        int timeCol = Glass.argb(0.7f * fade, 0xFFFFFF);
        text(g, font, fmt(pos), tx, tyy, ts, timeCol, true);
        String len = fmt(length);
        text(g, font, len, tx + tw - Math.round(font.width(len) * ts), tyy, ts, timeCol, true);
        int cx = tx + tw / 2, cy = tyy + Math.round(3 * s);
        int icon = Glass.argb(0.9f * fade, 0xFFFFFF);
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

        if ("off".equals(c.lyricMode)) {
            String line = lf.current() != null ? lf.current() : lf.note();
            if (line != null && !line.isBlank()) {
                float ls = s * 0.8f;
                text(g, font, trim(font, line, (w - pad * 2) / ls), x + pad, y + Math.round(57 * s), ls,
                        Glass.argb(0.9f * fade, 0xFFFFFF), true);
            }
        }
    }

    // ── on-screen lyrics ──

    static void drawLyrics(GuiGraphics g, PlayerWatcher.State st, LyricsService.Frame lf, float fade) {
        Minecraft mc = Minecraft.getInstance();
        GlassConfig c = SpotifyGlassClient.CONFIG;
        Font font = mc.font;
        int sw = g.guiWidth(), sh = g.guiHeight();
        float s = Math.max(0.5f, Math.min(4f, c.lyricHudSize));
        int cx = Math.round(c.lyricX * sw);
        int cy = Math.round(c.lyricY * sh);

        String text = lf.current();
        double prog = lf.progress();
        float alpha = fade;
        if (text == null) {
            text = "♪  " + st.title();
            prog = 1;
            alpha *= 0.7f;
        } else {
            alpha *= (float) ease(lf.sinceStart() / 0.35);
        }

        int maxUnits = Math.max(80, (int) (sw * 0.75f / s));
        List<String> lines = wrap(font, text, maxUnits);
        int lineH = Math.round(11 * s);
        int widest = 0;
        for (String l : lines) widest = Math.max(widest, Math.round(font.width(l) * s));
        int boxW = widest + Math.round(20 * s);
        int boxH = lines.size() * lineH + Math.round(9 * s);
        int bx = cx - boxW / 2, by = cy - boxH / 2;
        lyricBoxX = bx;
        lyricBoxY = by;
        lyricBoxW = boxW;
        lyricBoxH = boxH;

        if (c.lyricPill) {
            int pr = Math.min(boxH / 2, Math.round(9 * s));
            Glass.roundedSolid(g, bx, by, boxW, boxH, pr, Glass.argb(0.38f * fade, 0x0C0C14));
            if (c.albumTint && ArtManager.ready()) {
                Glass.roundedSolid(g, bx, by, boxW, boxH, pr,
                        Glass.withAlpha(ArtManager.vivid(), (c.albumTintAlpha / 255f) * 0.6f * fade));
            }
            Glass.rim(g, bx, by, boxW, boxH, pr, Glass.argb(0.55f * fade, 0xFFFFFF),
                    Glass.argb(0.15f * fade, 0xFFFFFF), Glass.argb(0.3f * fade, 0xFFFFFF));
        }

        int total = 0;
        for (String l : lines) total += l.length();
        int remaining = (int) Math.round(Math.max(0, Math.min(1, prog)) * total);
        int sung = Glass.lerpColor(0xFFFFFFFF, ArtManager.accent() | 0xFF000000, 0.2f);
        int ty = by + Math.round(5 * s);
        for (String line : lines) {
            int kChars = Math.max(0, Math.min(line.length(), remaining));
            remaining -= line.length();
            String done = line.substring(0, kChars), rest = line.substring(kChars);
            int lx = cx - Math.round(font.width(line) * s) / 2;
            text(g, font, done, lx, ty, s, Glass.withAlpha(sung, alpha), true);
            text(g, font, rest, lx + Math.round(font.width(done) * s), ty, s, Glass.argb(0.5f * alpha, 0xD8D8E8), true);
            ty += lineH;
        }

        // Previous line floats up and fades; next line waits underneath
        if (lf.previous() != null && lf.current() != null && lf.sinceStart() < 0.6) {
            float k = (float) (lf.sinceStart() / 0.6);
            float ps = s * 0.8f;
            String p = trim(font, lf.previous(), maxUnits);
            text(g, font, p, cx - Math.round(font.width(p) * ps) / 2, by - Math.round((8 + 10 * k) * s), ps,
                    Glass.argb((1 - k) * 0.7f * fade, 0xFFFFFF), true);
        }
        if (c.showNextLine && lf.next() != null && lf.current() != null) {
            float ns = s * 0.7f;
            String n = trim(font, lf.next(), maxUnits / 0.7f);
            text(g, font, n, cx - Math.round(font.width(n) * ns) / 2, by + boxH + Math.round(3 * s), ns,
                    Glass.argb(0.5f * fade, 0xFFFFFF), true);
        }
    }

    // ── helpers ──

    static void text(GuiGraphics g, Font font, String str, int x, int y, float scale, int color, boolean shadow) {
        if (str == null || str.isEmpty() || (color >>> 24) < 8) return;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.drawString(font, str, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    static String trim(Font font, String s, float maxWidth) {
        if (s == null) return "";
        int max = Math.max(0, (int) maxWidth);
        if (font.width(s) <= max) return s;
        return font.plainSubstrByWidth(s, Math.max(0, max - font.width("…"))) + "…";
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
        if (out.isEmpty()) out.add(text);
        return out;
    }

    private static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3);
    }

    private static String fmt(double seconds) {
        int sec = Math.max(0, (int) seconds);
        return sec / 60 + ":" + String.format("%02d", sec % 60);
    }
}
