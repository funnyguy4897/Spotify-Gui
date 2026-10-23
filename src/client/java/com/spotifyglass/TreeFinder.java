package com.spotifyglass;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Once a second, finds a nearby tree (preferring ones you're looking at) to hang the lyrics on. */
public final class TreeFinder {
    private static volatile Vec3 anchor;
    private static BlockPos currentBase;
    private static int ticker;

    private TreeFinder() {}

    /** Point just above the canopy of the chosen tree, or null if there's no tree around. */
    public static Vec3 anchor() {
        return anchor;
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || !"tree".equals(SpotifyGlassClient.CONFIG.lyricMode)) {
            anchor = null;
            currentBase = null;
            return;
        }
        if (++ticker % 20 != 0) return;

        Level level = mc.level;
        BlockPos origin = mc.player.blockPosition();
        Vec3 look = mc.player.getLookAngle();
        double lookLen = Math.max(1e-3, Math.hypot(look.x, look.z));
        int radius = Math.max(4, Math.min(32, SpotifyGlassClient.CONFIG.treeSearchRadius));

        double bestScore = Double.MAX_VALUE;
        BlockPos bestBase = null;
        Vec3 bestAnchor = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -6; dy <= 10; dy++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.getBlockState(m).is(BlockTags.LOGS)) continue;
                    if (level.getBlockState(m.below()).is(BlockTags.LOGS)) continue; // only trunk bases
                    BlockPos base = m.immutable();
                    Vec3 a = canopyAnchor(level, base);
                    if (a == null) continue;
                    double ddx = a.x - mc.player.getX(), ddz = a.z - mc.player.getZ();
                    double dist = Math.sqrt(ddx * ddx + ddz * ddz);
                    double facing = (ddx * look.x + ddz * look.z) / Math.max(1e-3, dist * lookLen);
                    double score = dist - facing * 8 - (base.equals(currentBase) ? 4 : 0);
                    if (score < bestScore) {
                        bestScore = score;
                        bestBase = base;
                        bestAnchor = a;
                    }
                }
            }
        }
        currentBase = bestBase;
        anchor = bestAnchor;
    }

    private static Vec3 canopyAnchor(Level level, BlockPos base) {
        BlockPos.MutableBlockPos p = base.mutable();
        int logs = 0;
        while (logs < 32 && level.getBlockState(p).is(BlockTags.LOGS)) {
            p.move(0, 1, 0);
            logs++;
        }
        if (logs < 2) return null;
        int topLog = p.getY() - 1;
        int top = topLog;
        int leaves = 0;
        for (int i = 0; i < 8; i++) {
            if (level.getBlockState(p).is(BlockTags.LEAVES)) {
                leaves++;
                top = p.getY();
            }
            p.move(0, 1, 0);
        }
        if (leaves == 0) {
            // Some trees (acacia, big oaks) end in branches: look for leaves beside the top log
            BlockPos t = new BlockPos(base.getX(), topLog, base.getZ());
            int around = 0;
            for (BlockPos q : BlockPos.betweenClosed(t.offset(-2, -1, -2), t.offset(2, 2, 2))) {
                if (level.getBlockState(q).is(BlockTags.LEAVES)) around++;
            }
            if (around < 4) return null;
            top = topLog + 2;
        }
        return new Vec3(base.getX() + 0.5, top + 1.6, base.getZ() + 0.5);
    }
}
