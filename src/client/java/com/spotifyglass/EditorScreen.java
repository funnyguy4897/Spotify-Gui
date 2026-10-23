package com.spotifyglass;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.Function;

/** Drag the card and lyrics where you want them; tweak the glass with live sliders. */
public class EditorScreen extends Screen {
    private enum Drag { NONE, CARD, LYRICS }

    private static final String[] BACKDROPS = {"blur", "album", "dark"};
    private static final String[] LYRIC_MODES = {"screen", "horizon", "tree", "off"};

    private Drag drag = Drag.NONE;
    private double grabX, grabY;

    public EditorScreen() {
        super(Component.literal("Spotify Glass editor"));
    }

    private static GlassConfig cfg() {
        return SpotifyGlassClient.CONFIG;
    }

    @Override
    protected void init() {
        GlassConfig c = cfg();
        int x = 8, y = 26, w = 150, h = 20, gap = 23;

        addRenderableWidget(cycle(x, y, w, h, "Lyrics", LYRIC_MODES, () -> c.lyricMode, v -> c.lyricMode = v,
                v -> switch (v) {
                    case "screen" -> "On screen";
                    case "horizon" -> "Horizon";
                    case "tree" -> "In a tree";
                    default -> "On the card";
                }));
        y += gap;
        addRenderableWidget(cycle(x, y, w, h, "Glass", BACKDROPS, () -> c.backdrop, v -> c.backdrop = v,
                v -> switch (v) {
                    case "blur" -> "Blur the game";
                    case "album" -> "Blurred cover";
                    default -> "Dark";
                }));
        y += gap;
        addRenderableWidget(toggle(x, y, w, h, "Cover colours", () -> c.albumTint, v -> c.albumTint = v));
        y += gap;
        addRenderableWidget(new Slider(x, y, w, h, "Card size", 0.5, 3.0, c.scale, false, v -> c.scale = (float) v));
        y += gap;
        addRenderableWidget(new Slider(x, y, w, h, "Roundness", 0, 30, c.radius, true, v -> c.radius = (int) v));
        y += gap;
        addRenderableWidget(new Slider(x, y, w, h, "Blur", 1, 12, c.blurStrength, true, v -> c.blurStrength = (int) v));
        y += gap;
        addRenderableWidget(new Slider(x, y, w, h, "Colour tint", 0, 200, c.albumTintAlpha, true, v -> c.albumTintAlpha = (int) v));
        y += gap;
        addRenderableWidget(new Slider(x, y, w, h, "Frost", 0, 120, c.tintAlpha, true, v -> c.tintAlpha = (int) v));
        y += gap;
        addRenderableWidget(new Slider(x, y, w, h, "Shine", 0, 100, c.rimStrength, true, v -> c.rimStrength = (int) v));
        y += gap;
        addRenderableWidget(new Slider(x, y, w, h, "Lyric size", 0.6, 3.0, c.lyricHudSize, false, v -> c.lyricHudSize = (float) v));
        y += gap;
        addRenderableWidget(toggle(x, y, w, h, "Lyric bubble", () -> c.lyricPill, v -> c.lyricPill = v));
        y += gap;
        addRenderableWidget(toggle(x, y, w, h, "Light sweep", () -> c.liquidSheen, v -> c.liquidSheen = v));
        y += gap + 4;
        addRenderableWidget(Button.builder(Component.literal("Reset positions"), b -> {
            c.cardX = -1;
            c.cardY = -1;
            c.corner = "top_right";
            c.lyricX = 0.5f;
            c.lyricY = 0.08f;
        }).bounds(x, y, w / 2 - 2, h).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(x + w / 2 + 2, y, w / 2 - 2, h).build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Keep the world visible (no menu blur) so you see exactly how it will look
        g.fill(0, 0, 166, height, 0x88000000);

        PlayerWatcher.State st = PlayerWatcher.state();
        if (st == null) {
            st = new PlayerWatcher.State("Playing", "Song title", "Artist name", "", 200, "", 80, "preview", System.nanoTime());
        }
        LyricsService.Frame lf = LyricsService.frame(st, cfg().lyricOffsetMs);
        if (lf.current() == null) {
            lf = new LyricsService.Frame("Your lyrics will show up here", null, "and the next line here", 0.45, 2, true, "");
        }

        GlassHud.drawCard(g, st, lf, 1f);
        int[] card = GlassHud.cardBox(width, height);
        outline(g, card[0] - 2, card[1] - 2, card[2] + 4, card[3] + 4, drag == Drag.CARD);
        GlassHud.text(g, font, "Drag to move", card[0], card[1] + card[3] + 4, 0.75f, 0xCCFFFFFF, true);

        if ("screen".equals(cfg().lyricMode)) {
            GlassHud.drawLyrics(g, st, lf, 1f);
            outline(g, GlassHud.lyricBoxX - 2, GlassHud.lyricBoxY - 2, GlassHud.lyricBoxW + 4, GlassHud.lyricBoxH + 4,
                    drag == Drag.LYRICS);
        }
        GlassHud.text(g, font, "Spotify Glass", 8, 9, 1f, 0xFFFFFFFF, true);
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, boolean active) {
        int col = active ? 0xFFFFFFFF : 0x99FFFFFF;
        for (int i = 0; i < w; i += 6) {
            g.fill(x + i, y, Math.min(x + w, x + i + 3), y + 1, col);
            g.fill(x + i, y + h - 1, Math.min(x + w, x + i + 3), y + h, col);
        }
        for (int i = 0; i < h; i += 6) {
            g.fill(x, y + i, x + 1, Math.min(y + h, y + i + 3), col);
            g.fill(x + w - 1, y + i, x + w, Math.min(y + h, y + i + 3), col);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;
        double mx = event.x(), my = event.y();
        if ("screen".equals(cfg().lyricMode) && inside(mx, my, GlassHud.lyricBoxX, GlassHud.lyricBoxY,
                GlassHud.lyricBoxW, GlassHud.lyricBoxH)) {
            drag = Drag.LYRICS;
            grabX = mx - cfg().lyricX * width;
            grabY = my - cfg().lyricY * height;
            return true;
        }
        int[] card = GlassHud.cardBox(width, height);
        if (inside(mx, my, card[0], card[1], card[2], card[3])) {
            drag = Drag.CARD;
            grabX = mx - card[0];
            grabY = my - card[1];
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        GlassConfig c = cfg();
        if (drag == Drag.CARD) {
            int[] card = GlassHud.cardBox(width, height);
            double nx = Math.max(0, Math.min(width - card[2], event.x() - grabX));
            double ny = Math.max(0, Math.min(height - card[3], event.y() - grabY));
            c.cardX = (float) (nx / width);
            c.cardY = (float) (ny / height);
            return true;
        }
        if (drag == Drag.LYRICS) {
            c.lyricX = (float) Math.max(0.02, Math.min(0.98, (event.x() - grabX) / width));
            c.lyricY = (float) Math.max(0.02, Math.min(0.98, (event.y() - grabY) / height));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (drag != Drag.NONE) {
            drag = Drag.NONE;
            cfg().save();
            return true;
        }
        return super.mouseReleased(event);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void onClose() {
        cfg().save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ── widgets ──

    private interface Getter<T> {
        T get();
    }

    private static Button cycle(int x, int y, int w, int h, String label, String[] values,
                                Getter<String> get, java.util.function.Consumer<String> set,
                                Function<String, String> name) {
        return Button.builder(Component.literal(label + ": " + name.apply(get.get())), b -> {
            int i = 0;
            for (int k = 0; k < values.length; k++) if (values[k].equals(get.get())) i = k;
            String next = values[(i + 1) % values.length];
            set.accept(next);
            b.setMessage(Component.literal(label + ": " + name.apply(next)));
        }).bounds(x, y, w, h).build();
    }

    private static Button toggle(int x, int y, int w, int h, String label, Getter<Boolean> get,
                                 java.util.function.Consumer<Boolean> set) {
        return Button.builder(Component.literal(label + ": " + (get.get() ? "On" : "Off")), b -> {
            boolean v = !get.get();
            set.accept(v);
            b.setMessage(Component.literal(label + ": " + (v ? "On" : "Off")));
        }).bounds(x, y, w, h).build();
    }

    private static final class Slider extends AbstractSliderButton {
        private final String label;
        private final double min, max;
        private final boolean whole;
        private final DoubleConsumer set;

        Slider(int x, int y, int w, int h, String label, double min, double max, double current, boolean whole,
               DoubleConsumer set) {
            super(x, y, w, h, Component.empty(), Math.max(0, Math.min(1, (current - min) / (max - min))));
            this.label = label;
            this.min = min;
            this.max = max;
            this.whole = whole;
            this.set = set;
            updateMessage();
        }

        private double real() {
            double v = min + value * (max - min);
            return whole ? Math.round(v) : v;
        }

        @Override
        protected void updateMessage() {
            if (label == null) return; // called before fields are set on some versions
            setMessage(Component.literal(label + ": " + (whole ? String.valueOf((long) real())
                    : String.format("%.2f", real()))));
        }

        @Override
        protected void applyValue() {
            set.accept(real());
        }
    }
}
