package com.spotifyglass;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpotifyGlassClient implements ClientModInitializer {
    public static final String MOD_ID = "spotifyglass";
    public static final Logger LOGGER = LoggerFactory.getLogger("Spotify Glass");
    public static GlassConfig CONFIG = new GlassConfig();

    private static KeyMapping toggleKey, lyricsKey, playPauseKey, nextKey, prevKey;
    private static String lastTrackKey = null;
    private static boolean wasPresent = false;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        CONFIG = GlassConfig.load();
        GlassHud.visible = CONFIG.cardVisible;
        PlayerWatcher.start();

        KeyMapping.Category category = KeyMapping.Category.register(id("main"));
        int none = InputConstants.UNKNOWN.getValue();
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.spotifyglass.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_O, category));
        lyricsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.spotifyglass.lyrics", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));
        playPauseKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.spotifyglass.play_pause", InputConstants.Type.KEYSYM, none, category));
        nextKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.spotifyglass.next", InputConstants.Type.KEYSYM, none, category));
        prevKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.spotifyglass.previous", InputConstants.Type.KEYSYM, none, category));

        ClientTickEvents.END_CLIENT_TICK.register(SpotifyGlassClient::tick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id("now_playing"), GlassHud::render);
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(WorldLyrics::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(command()));
    }

    private static void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) toggleCard(mc);
        while (lyricsKey.consumeClick()) cycleLyrics(mc);
        while (playPauseKey.consumeClick()) PlayerWatcher.command("play-pause");
        while (nextKey.consumeClick()) PlayerWatcher.command("next");
        while (prevKey.consumeClick()) PlayerWatcher.command("previous");

        PlayerWatcher.State st = PlayerWatcher.state();
        boolean present = st != null;
        if (present && !wasPresent && CONFIG.autoShow) GlassHud.visible = true;
        wasPresent = present;
        if (present) {
            String key = st.trackKey();
            if (!key.equals(lastTrackKey)) {
                lastTrackKey = key;
                LyricsService.request(key, st.title(), st.artist(), st.album(), st.length(), false);
                ArtManager.request(st.artUrl());
            }
        }
        TreeFinder.tick(mc);
    }

    private static void toggleCard(Minecraft mc) {
        GlassHud.visible = !GlassHud.visible;
        CONFIG.cardVisible = GlassHud.visible;
        CONFIG.save();
        say(mc, GlassHud.visible ? "Card shown" : "Card hidden");
    }

    private static void cycleLyrics(Minecraft mc) {
        String next = switch (CONFIG.lyricMode) {
            case "horizon" -> "tree";
            case "tree" -> "off";
            default -> "horizon";
        };
        setLyricMode(next);
        say(mc, switch (next) {
            case "horizon" -> "Lyrics on the horizon";
            case "tree" -> "Lyrics in the nearest tree (horizon if none nearby)";
            default -> "Lyrics on the card";
        });
    }

    private static void setLyricMode(String mode) {
        CONFIG.lyricMode = mode;
        CONFIG.save();
    }

    private static void say(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), true);
    }

    // ── /sg command ──

    private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("sg");

        root.then(ClientCommandManager.literal("toggle").executes(ctx -> {
            toggleCard(Minecraft.getInstance());
            return 1;
        }));

        LiteralArgumentBuilder<FabricClientCommandSource> lyrics = ClientCommandManager.literal("lyrics");
        for (String mode : new String[]{"horizon", "tree", "off"}) {
            lyrics.then(ClientCommandManager.literal(mode).executes(ctx -> {
                setLyricMode(mode);
                return reply(ctx, "Lyrics placement: " + mode);
            }));
        }
        root.then(lyrics);

        LiteralArgumentBuilder<FabricClientCommandSource> corner = ClientCommandManager.literal("corner");
        for (String c : new String[]{"top_left", "top_right", "bottom_left", "bottom_right"}) {
            corner.then(ClientCommandManager.literal(c).executes(ctx -> {
                CONFIG.corner = c;
                CONFIG.save();
                return reply(ctx, "Card moved to " + c.replace('_', ' '));
            }));
        }
        root.then(corner);

        root.then(ClientCommandManager.literal("follow").executes(ctx -> {
            CONFIG.horizonFollowLook = !CONFIG.horizonFollowLook;
            CONFIG.save();
            return reply(ctx, CONFIG.horizonFollowLook
                    ? "Horizon lyrics follow where you look"
                    : "Horizon lyrics stay at bearing " + CONFIG.horizonBearing + "°");
        }));

        root.then(ClientCommandManager.literal("offset")
                .then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(-10000, 10000))
                        .executes(ctx -> {
                            CONFIG.lyricOffsetMs = IntegerArgumentType.getInteger(ctx, "ms");
                            CONFIG.save();
                            return reply(ctx, "Lyric offset " + CONFIG.lyricOffsetMs + " ms");
                        })));

        root.then(ClientCommandManager.literal("scale")
                .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0.5f, 3f))
                        .executes(ctx -> {
                            CONFIG.scale = FloatArgumentType.getFloat(ctx, "value");
                            CONFIG.save();
                            return reply(ctx, "Card scale " + CONFIG.scale);
                        })));

        root.then(ClientCommandManager.literal("radius")
                .then(ClientCommandManager.argument("px", IntegerArgumentType.integer(0, 30))
                        .executes(ctx -> {
                            CONFIG.radius = IntegerArgumentType.getInteger(ctx, "px");
                            CONFIG.save();
                            return reply(ctx, "Corner radius " + CONFIG.radius);
                        })));

        root.then(ClientCommandManager.literal("tint")
                .then(ClientCommandManager.argument("hex", StringArgumentType.word())
                        .executes(ctx -> {
                            CONFIG.tint = StringArgumentType.getString(ctx, "hex");
                            CONFIG.save();
                            return reply(ctx, "Glass tint " + CONFIG.tint);
                        })));

        root.then(ClientCommandManager.literal("refetch").executes(ctx -> {
            PlayerWatcher.State st = PlayerWatcher.state();
            if (st == null) return reply(ctx, "Spotify isn't playing");
            LyricsService.request(st.trackKey(), st.title(), st.artist(), st.album(), st.length(), true);
            return reply(ctx, "Refetching lyrics");
        }));

        root.then(ClientCommandManager.literal("reload").executes(ctx -> {
            CONFIG = GlassConfig.load();
            GlassHud.visible = CONFIG.cardVisible;
            return reply(ctx, "Reloaded config/spotifyglass.json");
        }));

        root.then(ClientCommandManager.literal("status").executes(ctx -> {
            PlayerWatcher.State st = PlayerWatcher.state();
            String err = PlayerWatcher.error();
            if (err != null) return reply(ctx, err);
            if (st == null) return reply(ctx, "Spotify not detected. Is it open and playing?");
            return reply(ctx, st.status() + ": " + st.title() + " by " + st.artist() + " (" + LyricsService.note() + ")");
        }));

        return root;
    }

    private static int reply(CommandContext<FabricClientCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(Component.literal(msg));
        return 1;
    }
}
