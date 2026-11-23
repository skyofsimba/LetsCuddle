package xyz.skyofsimba.client;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.core.CuddleAnimClip;
import xyz.skyofsimba.core.CuddleAnimDef;
import xyz.skyofsimba.core.CuddleAnimManifest;
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
 * <p>资源重载线程构建不可变快照，渲染线程只读；{@code letscuddle:normal} 缺失时保留旧快照。
 */
public final class CuddleAnimRegistry implements SimpleResourceReloadListener<CuddleAnimRegistry.Snapshot> {

    private static final String MANIFEST_DIR = "cuddle_animations";
    private static final String ANIMATION_DIR = "player_animations";

    private static volatile Snapshot snapshot = Snapshot.builtinFallback();

    public static void register() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new CuddleAnimRegistry());
    }

    @Override
    public Identifier getFabricId() {
        return Identifier.of(CuddleConstants.MODID, "cuddle_animations");
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

    public static List<CuddleAnimDef> all() {
        return snapshot.ordered;
    }

    /** 下一个动作 id（循环，顺序 = order asc + id）；当前 id 不在列表里时回到默认动作。 */
    public static String next(String currentId) {
        List<CuddleAnimDef> ordered = snapshot.ordered;
        if (ordered.isEmpty()) {
            return CuddleConstants.DEFAULT_ANIM_ID;
        }
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id().equals(currentId)) {
                return ordered.get((i + 1) % ordered.size()).id();
            }
        }
        return ordered.get(0).id();
    }

    // ------------------------------------------------------------------ 资源重载

    @Override
    public CompletableFuture<Snapshot> load(ResourceManager manager, Executor executor) {
        return CompletableFuture.supplyAsync(() -> scan(manager), executor);
    }

    @Override
    public CompletableFuture<Void> apply(Snapshot prepared, ResourceManager manager, Executor executor) {
        return CompletableFuture.runAsync(() -> {
            if (!prepared.byId.containsKey(CuddleConstants.DEFAULT_ANIM_ID)) {
                Letscuddle.LOGGER.error("LetsCuddle: '{}' is missing after reload; keeping the previous {} animations",
                        CuddleConstants.DEFAULT_ANIM_ID, snapshot.byId.size());
                return;
            }
            snapshot = prepared;
            Letscuddle.LOGGER.info("LetsCuddle: loaded {} animations, {} clips",
                    prepared.ordered.size(), prepared.clips.size());
            CuddleClientBridge.get().sendCaps();      // 能力列表可能变了，立刻重新上报
        }, executor);
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

    /** 把 playerAnimations 目录下的每份 JSON 按文件里的 {@code name} 建索引（文件名作为兜底）。 */
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
            String name = animationName(json);
            if (name != null) {
                clips.put(location.getNamespace() + ":" + name, clip);
            }
            String fileId = toId(location, ANIMATION_DIR);
            if (fileId != null) {
                clips.putIfAbsent(fileId, clip);
            }
        }
        return clips;
    }

    @Nullable
    private static String animationName(String json) {
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            com.google.gson.JsonElement name = root.getAsJsonObject().get("name");
            if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
                return null;
            }
            String value = name.getAsString().trim();
            return value.isEmpty() || value.contains(":") ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
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
            Map<String, CuddleAnimDef> defs = new LinkedHashMap<>();
            defs.put(CuddleConstants.BUILTIN_NORMAL, new CuddleAnimDef(
                    CuddleConstants.BUILTIN_NORMAL, "1",
                    CuddleConstants.MODID + ":cuddle_normal_sender",
                    CuddleConstants.MODID + ":cuddle_normal_receiver",
                    "letscuddle.animation.normal", "Normal cuddle",
                    CuddleConstants.DEFAULT_SPEED, CuddleConstants.DEFAULT_LOCK_TICKS, 0, null));
            defs.put(CuddleConstants.BUILTIN_TOUCH_HEAD, new CuddleAnimDef(
                    CuddleConstants.BUILTIN_TOUCH_HEAD, "1",
                    CuddleConstants.MODID + ":cuddle_touch_sender",
                    CuddleConstants.MODID + ":cuddle_touch_receiver",
                    "letscuddle.animation.touch_head", "Head-pat cuddle",
                    CuddleConstants.DEFAULT_SPEED, CuddleConstants.DEFAULT_LOCK_TICKS, 10, null));
            return of(defs, Map.of());
        }

        public boolean has(String animId) {
            return animId != null && byId.containsKey(animId);
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
