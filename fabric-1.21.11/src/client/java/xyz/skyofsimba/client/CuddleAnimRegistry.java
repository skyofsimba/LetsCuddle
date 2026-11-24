package xyz.skyofsimba.client;

import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.core.CuddleAnimClip;
import xyz.skyofsimba.core.CuddleAnimDef;
import xyz.skyofsimba.core.CuddleAnimManifest;
import xyz.skyofsimba.core.CuddleBuiltins;
import xyz.skyofsimba.core.CuddleCaps;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.core.CuddleIds;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 客户端动作注册表：同时扫描 manifest（{@code cuddle_animations}）与实际动画
 * （{@code player_animations}，PAL/playerAnimator 的复数目录）并建立 id → 剪辑的索引。
 *
 * <p>资源重载线程构建不可变快照，游戏线程替换；{@code letscuddle:normal} 缺失时保留旧快照。
 */
public final class CuddleAnimRegistry implements ResourceReloader {

    private static final Identifier RELOADER_ID =
            Identifier.of(CuddleConstants.MODID, "cuddle_animations");
    private static final String MANIFEST_DIR = "cuddle_animations";
    private static final String ANIMATION_DIR = "player_animations";

    private static volatile Snapshot snapshot = Snapshot.builtinFallback();

    private CuddleAnimRegistry() {}

    public static void register() {
        ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(RELOADER_ID, new CuddleAnimRegistry());
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    @Nullable
    public static CuddleAnimDef get(String animId) {
        return animId == null ? null : snapshot.byId.get(animId);
    }

    @Nullable
    public static CuddleAnimClip clip(String animationId) {
        return animationId == null ? null : snapshot.clips.get(animationId);
    }

    /** 下一个动作 id（循环，顺序 = order asc + id）；当前 id 不在列表里时回到默认动作。 */
    public static String next(String currentId) {
        return CuddleAnimDef.nextId(snapshot.ordered, currentId);
    }

    // ------------------------------------------------------------------ 资源重载

    @Override
    public CompletableFuture<Void> reload(ResourceReloader.Store store, Executor prepareExecutor,
                                          ResourceReloader.Synchronizer synchronizer,
                                          Executor applyExecutor) {
        return CompletableFuture.supplyAsync(() -> scan(store.getResourceManager()), prepareExecutor)
                .thenCompose(synchronizer::whenPrepared)
                .thenAcceptAsync(this::apply, applyExecutor);
    }

    /** 在游戏线程替换快照；缺默认动作时保留旧快照。 */
    private void apply(Snapshot prepared) {
        if (!prepared.byId.containsKey(CuddleConstants.DEFAULT_ANIM_ID)) {
            Letscuddle.LOGGER.error("LetsCuddle: '{}' is missing after reload; keeping the previous {} animations",
                    CuddleConstants.DEFAULT_ANIM_ID, snapshot.byId.size());
            return;
        }
        snapshot = prepared;
        Letscuddle.LOGGER.info("LetsCuddle: loaded {} animations, {} clips",
                prepared.ordered.size(), prepared.clips.size());
        CuddleClientBridge.get().sendCaps();      // 能力列表可能变了，立刻重新上报
    }

    private static Snapshot scan(ResourceManager manager) {
        Map<String, CuddleAnimDef> defs = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<Identifier, Resource> entry : manager.findResources(MANIFEST_DIR,
                path -> path.getPath().endsWith(".json")).entrySet()) {
            if (++total > CuddleConstants.MAX_TOTAL_ANIMS) {
                Letscuddle.LOGGER.warn("LetsCuddle: more than {} animation manifests, ignoring the rest",
                        CuddleConstants.MAX_TOTAL_ANIMS);
                break;
            }
            Identifier location = entry.getKey();
            String id = toId(location, MANIFEST_DIR);
            if (id == null) {
                Letscuddle.LOGGER.warn("LetsCuddle: skipping animation manifest with invalid id {}", location);
                continue;
            }
            String json = read(entry.getValue(), CuddleConstants.MAX_MANIFEST_BYTES);
            if (json == null) {
                Letscuddle.LOGGER.warn("LetsCuddle: cannot read animation manifest {}", id);
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
        return Snapshot.of(defs, scanClips(manager));
    }

    /** 把 player_animations 目录下的每份 JSON 按文件里的 {@code name} 建索引（文件名作为兜底）。 */
    private static Map<String, CuddleAnimClip> scanClips(ResourceManager manager) {
        Map<String, CuddleAnimClip> clips = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> entry : manager.findResources(ANIMATION_DIR,
                path -> path.getPath().endsWith(".json")).entrySet()) {
            Identifier location = entry.getKey();
            String json = read(entry.getValue(), CuddleConstants.MAX_ANIMATION_BYTES);
            if (json == null) {
                continue;
            }
            CuddleAnimClip clip = CuddleAnimClip.parse(json);
            if (clip == null) {
                Letscuddle.LOGGER.warn("LetsCuddle: invalid animation JSON {}", location);
                continue;
            }
            if (clip.name() != null) {
                clips.put(location.getNamespace() + ":" + clip.name(), clip);
            }
            String fileId = toId(location, ANIMATION_DIR);
            if (fileId != null) {
                clips.putIfAbsent(fileId, clip);
            }
        }
        return clips;
    }

    @Nullable
    private static String read(Resource resource, int maxBytes) {
        try (InputStream stream = resource.getInputStream()) {
            byte[] bytes = stream.readNBytes(maxBytes + 1);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code letscuddle:cuddle_animations/normal.json} → {@code letscuddle:normal}。 */
    @Nullable
    private static String toId(Identifier location, String directory) {
        String path = location.getPath();
        if (!path.startsWith(directory + "/") || !path.endsWith(".json")) {
            return null;
        }
        String relative = path.substring(directory.length() + 1, path.length() - ".json".length());
        String id = location.getNamespace() + ":" + relative;
        return CuddleIds.isValid(id) ? id : null;
    }

    /** 不可变快照。 */
    public static final class Snapshot {

        private final Map<String, CuddleAnimDef> byId;
        private final List<CuddleAnimDef> ordered;
        private final Map<String, CuddleAnimClip> clips;

        private Snapshot(Map<String, CuddleAnimDef> byId, List<CuddleAnimDef> ordered,
                         Map<String, CuddleAnimClip> clips) {
            this.byId = Map.copyOf(byId);
            this.ordered = List.copyOf(ordered);
            this.clips = Map.copyOf(clips);
        }

        static Snapshot of(Map<String, CuddleAnimDef> defs, Map<String, CuddleAnimClip> clips) {
            List<CuddleAnimDef> ordered = new ArrayList<>(defs.values());
            ordered.sort(Comparator.comparingInt(CuddleAnimDef::order).thenComparing(CuddleAnimDef::id));
            return new Snapshot(defs, ordered, clips);
        }

        static Snapshot builtinFallback() {
            return of(CuddleBuiltins.asMap(), Map.of());
        }

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
