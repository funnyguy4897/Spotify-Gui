package com.spotifyglass;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;

/**
 * Real "frosted glass": copies the part of the game image behind the card off the GPU,
 * blurs it, and hands it back as a texture. Results arrive a frame or two later, which
 * is invisible in practice. If anything about this fails, the card quietly falls back.
 */
public final class BackdropBlur {
    public static final Identifier ID = SpotifyGlassClient.id("dynamic/blur");

    private static DynamicTexture texture;
    private static int texW, texH;
    private static volatile boolean pending;
    private static boolean ready;
    private static boolean failed;

    private BackdropBlur() {}

    public static boolean ready() {
        return ready && !failed;
    }

    public static boolean failed() {
        return failed;
    }

    public static int width() {
        return texW;
    }

    public static int height() {
        return texH;
    }

    /** Asks for a blurred copy of what's behind the GUI-space rectangle. */
    public static void capture(int gx, int gy, int gw, int gh, int strength) {
        if (failed || pending || gw <= 0 || gh <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            GpuTexture src = mc.getMainRenderTarget().getColorTexture();
            if (src == null) return;
            double scale = mc.getWindow().getGuiScale();
            int fbW = src.getWidth(0), fbH = src.getHeight(0);
            int px = Math.max(0, Math.min(fbW - 1, (int) Math.floor(gx * scale)));
            int py = Math.max(0, Math.min(fbH - 1, (int) Math.floor(gy * scale)));
            int pw = Math.min(fbW - px, (int) Math.ceil(gw * scale));
            int ph = Math.min(fbH - py, (int) Math.ceil(gh * scale));
            if (pw <= 0 || ph <= 0) return;
            int glY = fbH - (py + ph); // the GPU counts rows from the bottom
            int pixel = src.getFormat().pixelSize();
            int size = pw * ph * pixel;

            GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                    () -> "spotifyglass blur readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            pending = true;
            final int outW = gw, outH = gh, radius = Math.max(1, Math.min(12, strength));
            encoder.copyTextureToBuffer(src, buffer, 0, () -> {
                try (GpuBuffer.MappedView view = encoder.mapBuffer(buffer, true, false)) {
                    int[] px2 = downsample(view.data(), pw, ph, pixel, outW, outH);
                    blur(px2, outW, outH, radius);
                    upload(px2, outW, outH);
                } catch (Throwable t) {
                    fail(t);
                } finally {
                    buffer.close();
                    pending = false;
                }
            }, 0, px, glY, pw, ph);
        } catch (Throwable t) {
            pending = false;
            fail(t);
        }
    }

    private static void fail(Throwable t) {
        if (!failed) SpotifyGlassClient.LOGGER.warn("Backdrop blur unavailable, using a plain glass backdrop", t);
        failed = true;
    }

    /** Averages blocks of real pixels down to one pixel per GUI pixel, flipping right side up. */
    private static int[] downsample(ByteBuffer data, int pw, int ph, int pixel, int outW, int outH) {
        int[] out = new int[outW * outH];
        for (int oy = 0; oy < outH; oy++) {
            int y0 = oy * ph / outH, y1 = Math.max(y0 + 1, (oy + 1) * ph / outH);
            for (int ox = 0; ox < outW; ox++) {
                int x0 = ox * pw / outW, x1 = Math.max(x0 + 1, (ox + 1) * pw / outW);
                int r = 0, g = 0, b = 0, n = 0;
                for (int y = y0; y < y1 && y < ph; y++) {
                    int row = ph - 1 - y;
                    for (int x = x0; x < x1 && x < pw; x++) {
                        int i = (row * pw + x) * pixel;
                        r += data.get(i) & 255;
                        g += data.get(i + 1) & 255;
                        b += data.get(i + 2) & 255;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                out[oy * outW + ox] = 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
        return out;
    }

    private static void blur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        for (int round = 0; round < 2; round++) {
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

    private static void upload(int[] argb, int w, int h) {
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        if (texture == null || texW != w || texH != h) {
            tm.release(ID);
            NativeImage img = new NativeImage(w, h, false);
            fill(img, argb, w, h);
            texture = new DynamicTexture(() -> "spotifyglass blur", img);
            tm.register(ID, texture);
            texW = w;
            texH = h;
        } else {
            NativeImage img = texture.getPixels();
            if (img == null) return;
            fill(img, argb, w, h);
            texture.upload();
        }
        ready = true;
    }

    private static void fill(NativeImage img, int[] argb, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setPixel(x, y, argb[y * w + x]);
            }
        }
    }
}
