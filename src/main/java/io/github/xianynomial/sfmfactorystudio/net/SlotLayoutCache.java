package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 槽位布局快照缓存（服务端，30 秒过期）。键=方块位置；方块状态变化
 * （换方块/换朝向）时立即失效，避免拿旧布局答新方块。
 */
public final class SlotLayoutCache {
    private SlotLayoutCache() {
    }

    public record Entry(int total, List<int[]> slots) {
    }

    private record Cached(Entry entry, BlockState state, long at) {
    }

    private static final long TTL_MS = 30_000;
    private static final ConcurrentHashMap<BlockPos, Cached> CACHE = new ConcurrentHashMap<>();

    public static Entry get(BlockPos pos, BlockState state) {
        Cached c = CACHE.get(pos);
        if (c == null) return null;
        if (c.state() != state || System.currentTimeMillis() - c.at() > TTL_MS) {
            CACHE.remove(pos);
            return null;
        }
        return c.entry();
    }

    public static void put(BlockPos pos, BlockState state, int total, List<int[]> slots) {
        CACHE.put(pos, new Cached(new Entry(total, List.copyOf(slots)), state, System.currentTimeMillis()));
    }

    public static void clear() {
        CACHE.clear();
    }
}
