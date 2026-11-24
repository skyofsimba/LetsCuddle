package xyz.skyofsimba.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.core.CuddleAnimDef;
import xyz.skyofsimba.core.CuddleAnimManifest;
import xyz.skyofsimba.core.CuddleBuiltins;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.core.CuddleIds;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端动作注册表：扫描所有资源包里的 {@code assets/<ns>/cuddle_animations/*.json}。
 *
 * <p>资源重载线程构建不可变快照 → 原子替换；渲染线程只读 {@link #snapshot()}。
 * 重载后如果 {@code letscuddle:normal} 没了（被坏包破坏），保留上一份可用快照，避免所有人不能贴贴。
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CuddleAnimRegistry extends SimplePreparableReloadListener<CuddleAnimRegistry.Snapshot> {

    private static final String DIRECTORY = "cuddle_animations";

    private static volatile Snapshot snapshot = Snapshot.builtinFallback();

    private CuddleAnimRegistry() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new CuddleAnimRegistry());
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    @Nullable
    public static CuddleAnimDef get(String animId) {
        return animId == null ? null : snapshot.byId.get(animId);
    }

    /** 下一个动作 id（循环，顺序 = order asc + id）；当前 id 不在列表里时回到默认动作。 */
    public static String next(String currentId) {
        return CuddleAnimDef.nextId(snapshot.ordered, currentId);
    }

    // ------------------------------------------------------------------ 资源重载

    @Override
    protected Snapshot prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, CuddleAnimDef> defs = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> files = manager.listResources(DIRECTORY,
                location -> location.getPath().endsWith(".json"));
        int total = 0;
        for (Map.Entry<ResourceLocation, Resource> entry : files.entrySet()) {
            if (++total > CuddleConstants.MAX_TOTAL_ANIMS) {
                Letscuddle.LOGGER.warn("LetsCuddle: more than {} animation manifests, ignoring the rest",
                        CuddleConstants.MAX_TOTAL_ANIMS);
                break;
            }
            ResourceLocation location = entry.getKey();
            String id = toId(location);
            if (id == null) {
                Letscuddle.LOGGER.warn("LetsCuddle: skipping animation manifest with invalid id {}", location);
                continue;
            }
            String json;
            try (InputStream stream = entry.getValue().open()) {
                json = new String(stream.readNBytes(CuddleConstants.MAX_MANIFEST_BYTES + 1), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Letscuddle.LOGGER.warn("LetsCuddle: cannot read animation manifest {}: {}", id, e.getMessage());
                continue;
            }
            CuddleAnimManifest.Result result = CuddleAnimManifest.parse(id, json);
            for (String warning : result.warnings()) {
                Letscuddle.LOGGER.warn("LetsCuddle: manifest {}: {}", id, warning);
            }
            if (!result.ok()) {
                Letscuddle.LOGGER.warn("LetsCuddle: rejected animation manifest {}: {}", id, result.error());
                continue;
            }
            if (defs.putIfAbsent(id, result.def()) != null) {
                Letscuddle.LOGGER.warn("LetsCuddle: duplicate animation id {}, keeping the first", id);
            }
        }
        return Snapshot.of(defs);
    }

    @Override
    protected void apply(Snapshot prepared, ResourceManager manager, ProfilerFiller profiler) {
        if (!prepared.byId.containsKey(CuddleConstants.DEFAULT_ANIM_ID)) {
            Letscuddle.LOGGER.error("LetsCuddle: '{}' is missing after reload; keeping the previous {} animations",
                    CuddleConstants.DEFAULT_ANIM_ID, snapshot.byId.size());
            return;
        }
        snapshot = prepared;
        Letscuddle.LOGGER.info("LetsCuddle: loaded {} animations", prepared.ordered.size());
        CuddleClientHooks.sendCaps();      // 能力列表可能变了，立刻重新上报
    }

    /** {@code letscuddle:cuddle_animations/normal.json} → {@code letscuddle:normal}。 */
    @Nullable
    private static String toId(ResourceLocation location) {
        String path = location.getPath();
        if (!path.startsWith(DIRECTORY + "/") || !path.endsWith(".json")) {
            return null;
        }
        String relative = path.substring(DIRECTORY.length() + 1, path.length() - ".json".length());
        String id = location.getNamespace() + ":" + relative;
        return CuddleIds.isValid(id) ? id : null;
    }

    /** 不可变快照。 */
    public static final class Snapshot {

        private final Map<String, CuddleAnimDef> byId;
        private final List<CuddleAnimDef> ordered;

        private Snapshot(Map<String, CuddleAnimDef> byId, List<CuddleAnimDef> ordered) {
            this.byId = Map.copyOf(byId);
            this.ordered = List.copyOf(ordered);
        }

        static Snapshot of(Map<String, CuddleAnimDef> defs) {
            List<CuddleAnimDef> ordered = new ArrayList<>(defs.values());
            ordered.sort(Comparator.comparingInt(CuddleAnimDef::order).thenComparing(CuddleAnimDef::id));
            return new Snapshot(defs, ordered);
        }

        /** 资源还没加载时也能显示名字、播放默认动作的静态兜底。 */
        static Snapshot builtinFallback() {
            return of(CuddleBuiltins.asMap());
        }

        /** 上报给服务端的能力快照（最多 {@link CuddleConstants#MAX_ANIMS_PER_CLIENT} 条）。 */
        public CuddleCaps caps() {
            Map<String, CuddleCaps.Anim> map = new LinkedHashMap<>();
            for (CuddleAnimDef def : ordered) {
                if (map.size() >= CuddleConstants.MAX_ANIMS_PER_CLIENT) {
                    break;
                }
                map.put(def.id(), new CuddleCaps.Anim(def.lockTicks(), def.contentVersion(),
                        def.nameKey(), def.name()));
            }
            return new CuddleCaps(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT, map);
        }
    }
}
