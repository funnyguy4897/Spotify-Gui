package com.spotifyglass;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;

/** Downloads album art and turns it into two textures: a crisp thumbnail and a blurred glass backdrop. */
public final class ArtManager {
    public static final Identifier ART = SpotifyGlassClient.id("dynamic/art");
    public static final Identifier BACKDROP = SpotifyGlassClient.id("dynamic/backdrop");
    public static final int ART_SIZE = 96;
    public static final int BD_W = 160;
    public static final int BD_H = 56;

    private static volatile String requested = "";
    private static volatile boolean ready = false;
    private static volatile int accent = 0xFF9A8CFF;

    private ArtManager() {}

    public static boolean ready() {
        return ready;
    }

    /** Average colour of the cover, used to tint the lyrics. */
    public static int accent() {
        return accent;
    }

    public static void request(String url) {
        if (url == null) url = "";
        if (url.equals(requested)) return;
        requested = url;
        ready = false;
        if (url.isEmpty()) return;
        final String u = url;
        Thread t = new Thread(() -> load(u), "spotifyglass-art");
        t.setDaemon(true);
        t.start();
    }

    private static void load(String url) {
        try {
            String fixed = url.replace("https://open.spotify.com/image/", "https://i.scdn.co/image/");
            BufferedImage src;
            if (fixed.startsWith("file://")) {
                src = ImageIO.read(new File(URI.create(fixed)));
            } else {
                URLConnection con = URI.create(fixed).toURL().openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(10000);
                con.setRequestProperty("User-Agent", "SpotifyGlass/1.0");
                try (InputStream in = con.getInputStream()) {
                    src = ImageIO.read(in);
                }
            }
            if (src == null) return;

            int side = Math.min(src.getWidth(), src.getHeight());
            BufferedImage square = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            int[] art = pixels(scale(square, ART_SIZE, ART_SIZE));

            BufferedImage tiny = scale(square, 12, 5);
            int[] t = pixels(tiny);
            long r = 0, g = 0, b = 0;
            for (int px : t) {
                r += (px >> 16) & 255;
                g += (px >> 8) & 255;
                b += px & 255;
            }
            int n = t.length;
            int acc = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);

            BufferedImage up = new BufferedImage(BD_W, BD_H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = up.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.drawImage(tiny, 0, 0, BD_W, BD_H, null);
            g2.dispose();
            int[] bd = pixels(up);
            boxBlur(bd, BD_W, BD_H, 5);
            boxBlur(bd, BD_W, BD_H, 5);
            for (int i = 0; i < bd.length; i++) {
                int px = bd[i];
                int rr = (int) (((px >> 16) & 255) * 0.78);
                int gg = (int) (((px >> 8) & 255) * 0.78);
                int bb = (int) ((px & 255) * 0.78);
                bd[i] = 0xD8 << 24 | rr << 16 | gg << 8 | bb;
            }

            Minecraft.getInstance().execute(() -> {
                if (!url.equals(requested)) return;
                upload(ART, ART_SIZE, ART_SIZE, art);
                upload(BACKDROP, BD_W, BD_H, bd);
                accent = acc;
                ready = true;
            });
        } catch (Exception e) {
            SpotifyGlassClient.LOGGER.warn("Album art failed: {}", e.toString());
        }
    }

    private static void upload(Identifier id, int w, int h, int[] argb) {
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setPixel(x, y, argb[y * w + x]);
            }
        }
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        tm.release(id);
        tm.register(id, new DynamicTexture(() -> "spotifyglass " + id.getPath(), img));
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        Image s = src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(s, 0, 0, null);
        g.dispose();
        return out;
    }

    private static int[] pixels(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] out = new int[w * h];
        img.getRGB(0, 0, w, h, out, 0, w);
        for (int i = 0; i < out.length; i++) out[i] |= 0xFF000000;
        return out;
    }

    private static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 2; pass++) {
            boolean horiz = pass == 0;
            int[] from = horiz ? px : tmp, to = horiz ? tmp : px;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int sr = 0, sg = 0, sb = 0, c = 0;
                    for (int k = -r; k <= r; k++) {
                        int xx = horiz ? Math.min(w - 1, Math.max(0, x + k)) : x;
                        int yy = horiz ? y : Math.min(h - 1, Math.max(0, y + k));
                        int p = from[yy * w + xx];
                        sr += (p >> 16) & 255;
                        sg += (p >> 8) & 255;
                        sb += p & 255;
                        c++;
                    }
                    to[y * w + x] = 0xFF000000 | (sr / c) << 16 | (sg / c) << 8 | (sb / c);
                }
            }
        }
    }
}
