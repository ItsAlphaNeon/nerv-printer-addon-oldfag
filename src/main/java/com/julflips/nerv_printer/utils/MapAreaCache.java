package com.julflips.nerv_printer.utils;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import java.util.HashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class MapAreaCache {
    private static BlockPos mapCorner = null;
    private static Map<ChunkPos, Chunk> cachedChunks = new HashMap<>();
    private static long lastFallbackWarning = 0;

    public static boolean isWithingMap(BlockPos pos) {
        BlockPos relativePos = pos.subtract(mapCorner);
        return relativePos.getX() >= 0 && relativePos.getX() < 128 && relativePos.getZ() >= 0 && relativePos.getZ() < 128;
    }

    public static boolean isMapAreaClear() {
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                BlockPos pos = mapCorner.add(x, 0, z);
                int chunkX = pos.getX() >> 4;
                int chunkZ = pos.getZ() >> 4;
                BlockState blockState;
                if (mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                    blockState = mc.world.getBlockState(pos);
                } else {
                    // Chunk is unloaded - use the last known state if it was cached while the map
                    // area was tracked, otherwise we cannot verify the area is clear, so keep waiting
                    Chunk chunk = cachedChunks.get(new ChunkPos(chunkX, chunkZ));
                    if (chunk == null) return false;
                    blockState = chunk.getBlockState(pos);
                }
                if (!blockState.isAir() || !blockState.getFluidState().isEmpty()) return false;
            }
        }
        return true;
    }

    public static void reset(BlockPos newCorner) {
        mapCorner = new BlockPos(newCorner);
        cachedChunks.clear();
    }

    /**
     * Legacy lookup. NEVER use this for "is the map done?" checks - it falls back
     * to the client's placeholder (air) state for unloaded chunks, which makes
     * unbuilt rows look finished. Use {@link #getVerifiedBlockState} instead.
     */
    public static BlockState getCachedBlockState(BlockPos blockPos) {
        BlockState verified = getVerifiedBlockState(blockPos);
        if (verified != null) return verified;
        // Unknown chunk - the old behavior returned the placeholder (air) state.
        // Rate-limit the warning: this used to spam per call.
        long now = System.currentTimeMillis();
        if (now - lastFallbackWarning >= 5000) {
            lastFallbackWarning = now;
            ChatUtils.warning("Could not fetch Block at " + blockPos.toShortString() + ". Try loading the entire Map Area first.");
        }
        return mc.world.getBlockState(blockPos);
    }

    /**
     * Returns the block state, or {@code null} when the chunk is neither loaded
     * nor cached (i.e. the state is UNKNOWN). Callers doing completion checks
     * must treat null as "not finished / cannot verify" - never as air.
     */
    public static BlockState getVerifiedBlockState(BlockPos blockPos) {
        int chunkX = blockPos.getX() >> 4;
        int chunkZ = blockPos.getZ() >> 4;
        if (mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
            return mc.world.getBlockState(blockPos);
        }
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        Chunk chunk = cachedChunks.get(chunkPos);
        return chunk != null ? chunk.getBlockState(blockPos) : null;
    }

    @EventHandler()
    private static void onReceivePacket(PacketEvent.Receive event) {
        if (mapCorner != null && event.packet instanceof UnloadChunkS2CPacket packet) {
            BlockPos chunkCorner = packet.pos().getStartPos();
            if (isWithingMap(chunkCorner)) {
                cachedChunks.put(packet.pos(), mc.world.getChunk(packet.pos().getStartPos()));
            }
        }
    }
}
