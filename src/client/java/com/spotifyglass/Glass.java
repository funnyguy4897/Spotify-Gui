package com.spotifyglass;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** GuiGraphics only draws rectangles, so rounded corners are built one row at a time. */
final class Glass {
    interface RowColor {
        int at(int row);
    }

    private Glass() {}

    /** How far row `row` of a rounded rectangle is pulled in from each side. */
    static int inset(int row, int h, int r) {
        if (r <= 0) return 0;
        int dy;
        if (row < r) dy = r - row;
        else if (row >= h - r) dy = row - (h - r - 1);
        else return 0;
        double d = dy - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - d * d)));
    }

    static void rounded(GuiGraphics g, int x, int y, int w, int h, int r, RowColor color) {
        r = Math.min(r, Math.min(w, h) / 2);
        for (int i = 0; i < h; i++) {
            int c = color.at(i);
            if ((c >>> 24) == 0) continue;
            int ins = inset(i, h, r);
            g.fill(x + ins, y + i, x + w - ins, y + i + 1, c);
        }
    }

    static void roundedSolid(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        r = Math.min(r, Math.min(w, h) / 2);
        if ((color >>> 24) == 0) return;
        for (int i = 0; i < r; i++) {
            int ins = inset(i, h, r);
            g.fill(x + ins, y + i, x + w - ins, y + i + 1, color);
            g.fill(x + ins, y + h - 1 - i, x + w - ins, y + h - i, color);
        }
        g.fill(x, y + r, x + w, y + h - r, color);
    }

    /** One-pixel rim around a rounded rectangle; top, sides and bottom can differ in brightness. */
    static void rim(GuiGraphics g, int x, int y, int w, int h, int r, int top, int side, int bottom) {
        r = Math.min(r, Math.min(w, h) / 2);
        for (int i = 0; i < h; i++) {
            int ins = inset(i, h, r);
            int prev = i > 0 ? inset(i - 1, h, r) : ins;
            int next = i < h - 1 ? inset(i + 1, h, r) : ins;
            int run = Math.max(ins + 1, Math.max(prev, next));
            float f = h <= 1 ? 0 : i / (float) (h - 1);
            int c = f < 0.5f ? lerpColor(top, side, f * 2) : lerpColor(side, bottom, (f - 0.5f) * 2);
            if (i == 0) {
                g.fill(x + ins, y, x + w - ins, y + 1, top);
            } else if (i == h - 1) {
                g.fill(x + ins, y + i, x + w - ins, y + i + 1, bottom);
            } else {
                g.fill(x + ins, y + i, x + run, y + i + 1, c);
                g.fill(x + w - run, y + i, x + w - ins, y + i + 1, c);
            }
        }
    }

    /** Draws a whole texture into a rounded rectangle by clipping it row by row. */
    static void roundedBlit(GuiGraphics g, Identifier tex, int x, int y, int w, int h, int r, int texW, int texH) {
        r = Math.min(r, Math.min(w, h) / 2);
        g.enableScissor(x, y + r, x + w, y + h - r);
        blit(g, tex, x, y, w, h, texW, texH);
        g.disableScissor();
        for (int i = 0; i < r; i++) {
            int ins = inset(i, h, r);
            g.enableScissor(x + ins, y + i, x + w - ins, y + i + 1);
            blit(g, tex, x, y, w, h, texW, texH);
            g.disableScissor();
            int yb = y + h - 1 - i;
            g.enableScissor(x + ins, yb, x + w - ins, yb + 1);
            blit(g, tex, x, y, w, h, texW, texH);
            g.disableScissor();
        }
    }

    private static void blit(GuiGraphics g, Identifier tex, int x, int y, int w, int h, int texW, int texH) {
        // pipeline, texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, w, h, texW, texH, texW, texH);
    }

    static int argb(float a, int rgb) {
        int ai = Math.max(0, Math.min(255, Math.round(a * 255)));
        return ai << 24 | (rgb & 0xFFFFFF);
    }

    static int withAlpha(int argb, float mul) {
        int a = Math.round(((argb >>> 24) & 255) * Math.max(0, Math.min(1, mul)));
        return a << 24 | (argb & 0xFFFFFF);
    }

    static int lerpColor(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int aa = (a >>> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }
}
