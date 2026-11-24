package xyz.skyofsimba.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端能力快照：这个客户端能播哪些动作、每个动作锁多久、内容版本与显示名。
 *
 * <p>服务端只持有它的副本，从不解析动作包；显示名随能力一起上报，服务端发提示时才能用上
 * 资源包里的名字（而不是 id）。所有入口都经过 {@link #sanitize}：丢弃非法 id、夹紧 lock_ticks、
 * 限制条数与文本长度、规整内容版本。
 */
public record CuddleCaps(int protocol, int format, Map<String, Anim> animations) {

    /**
     * 单条动作能力。
     *
     * @param nameKey 语言键（可空）；两端都有同一个包时客户端会本地化
     * @param name    纯文本回退名（可空）
     */
    public record Anim(int lockTicks, String contentVersion, String nameKey, String name) {

        public Anim(int lockTicks, String contentVersion) {
            this(lockTicks, contentVersion, null, null);
        }

        public boolean hasNameKey() {
            return nameKey != null && !nameKey.isEmpty();
        }

        public boolean hasName() {
            return name != null && !name.isEmpty();
        }
    }

    public static final CuddleCaps EMPTY =
            new CuddleCaps(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT, Map.of());

    /** 客户端还没上报能力时的兜底：只有内置动作。 */
    public static CuddleCaps builtinOnly() {
        Map<String, Anim> map = new LinkedHashMap<>();
        for (CuddleAnimDef def : CuddleBuiltins.ALL) {
            map.put(def.id(), new Anim(def.lockTicks(), def.contentVersion()));
        }
        return sanitize(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT, map);
    }

    public CuddleCaps {
        animations = animations == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(animations));
    }

    /** 这个客户端有没有这个动作。 */
    public boolean has(String animId) {
        return animId != null && animations.containsKey(animId);
    }

    /** 该动作的能力条目；没有时返回 {@code null}。 */
    public Anim anim(String animId) {
        return animId == null ? null : animations.get(animId);
    }

    /** 该动作的锁定 tick；没有这个动作时返回 {@code fallback}。 */
    public int lockTicks(String animId, int fallback) {
        Anim anim = anim(animId);
        return anim == null ? fallback : anim.lockTicks();
    }

    /** 该动作的内容版本；没有时返回 {@code null}。 */
    public String contentVersion(String animId) {
        Anim anim = anim(animId);
        return anim == null ? null : anim.contentVersion();
    }

    /** 两端同 id 的内容版本是否一致。 */
    public boolean sameContent(String animId, CuddleCaps other) {
        String mine = contentVersion(animId);
        String theirs = other == null ? null : other.contentVersion(animId);
        return mine != null && mine.equals(theirs);
    }

    /** 两端锁定时间取小并夹紧（D9）。 */
    public int negotiatedLock(String animId, CuddleCaps other) {
        int mine = clampLock(lockTicks(animId, CuddleConstants.DEFAULT_LOCK_TICKS));
        int theirs = clampLock(other == null
                ? CuddleConstants.DEFAULT_LOCK_TICKS
                : other.lockTicks(animId, CuddleConstants.DEFAULT_LOCK_TICKS));
        return Math.min(mine, theirs);
    }

    /** 协议/格式是否与本端一致。 */
    public boolean compatible() {
        return protocol == CuddleConstants.PROTOCOL && format == CuddleConstants.PACK_FORMAT;
    }

    /**
     * 清洗客户端上报的数据：非法 id 与越界条目直接丢弃，lock 夹紧，内容版本/名字规整，条数截断。
     *
     * @return 清洗后的快照；数据整体不可用时返回只有表头信息的空快照
     */
    public static CuddleCaps sanitize(int protocol, int format, Map<String, Anim> raw) {
        Map<String, Anim> clean = new LinkedHashMap<>();
        if (raw != null) {
            for (Map.Entry<String, Anim> entry : raw.entrySet()) {
                if (clean.size() >= CuddleConstants.MAX_ANIMS_PER_CLIENT) {
                    break;
                }
                String id = CuddleIds.normalize(entry.getKey());
                if (id == null) {
                    continue;
                }
                Anim anim = entry.getValue();
                int lock = anim == null ? CuddleConstants.DEFAULT_LOCK_TICKS : anim.lockTicks();
                String version = CuddleIds.contentVersion(anim == null ? null : anim.contentVersion());
                String nameKey = trim(anim == null ? null : anim.nameKey(),
                        CuddleConstants.MAX_NAME_KEY_LEN);
                String name = trim(anim == null ? null : anim.name(),
                        CuddleConstants.MAX_NAME_LEN);
                clean.put(id, new Anim(clampLock(lock), version, nameKey, name));
            }
        }
        return new CuddleCaps(protocol, format, clean);
    }

    /** 夹紧锁定 tick 到 [20, 400]。 */
    public static int clampLock(int ticks) {
        if (ticks < CuddleConstants.MIN_LOCK_TICKS) {
            return CuddleConstants.MIN_LOCK_TICKS;
        }
        return Math.min(ticks, CuddleConstants.MAX_LOCK_TICKS);
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
