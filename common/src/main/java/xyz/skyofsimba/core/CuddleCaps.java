package xyz.skyofsimba.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端能力快照：这个客户端能播哪些动作、每个动作锁多久、内容版本是多少。
 *
 * <p>服务端只持有它的副本，从不解析动作包。所有入口都经过 {@link #sanitize}：
 * 丢弃非法 id、夹紧 lock_ticks、限制条数、规整内容版本。
 */
public record CuddleCaps(int protocol, int format, Map<String, Anim> animations) {

    /** 单条动作能力。 */
    public record Anim(int lockTicks, String contentVersion) {}

    public static final CuddleCaps EMPTY =
            new CuddleCaps(CuddleConstants.PROTOCOL, CuddleConstants.PACK_FORMAT, Map.of());

    public CuddleCaps {
        animations = animations == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(animations));
    }

    /** 这个客户端有没有这个动作。 */
    public boolean has(String animId) {
        return animId != null && animations.containsKey(animId);
    }

    /** 该动作的锁定 tick；没有这个动作时返回 {@code fallback}。 */
    public int lockTicks(String animId, int fallback) {
        Anim anim = animId == null ? null : animations.get(animId);
        return anim == null ? fallback : anim.lockTicks();
    }

    /** 该动作的内容版本；没有时返回 {@code null}。 */
    public String contentVersion(String animId) {
        Anim anim = animId == null ? null : animations.get(animId);
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
     * 清洗客户端上报的数据：非法 id 与越界条目直接丢弃，lock 夹紧，内容版本规整，条数截断。
     *
     * @return 清洗后的快照；数据整体不可用时返回 {@link #EMPTY}
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
                clean.put(id, new Anim(clampLock(lock), version));
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
}
