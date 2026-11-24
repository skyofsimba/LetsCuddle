package xyz.skyofsimba.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置动作（jar 内最低优先级包）的静态定义。
 *
 * <p>资源重载完成前、客户端还没上报能力时，两端都靠它兜底，所以必须与
 * {@code assets/letscuddle/cuddle_animations/*.json} 保持一致（单元测试会校验）。
 */
public final class CuddleBuiltins {

    public static final CuddleAnimDef NORMAL = new CuddleAnimDef(
            CuddleConstants.BUILTIN_NORMAL, "1",
            CuddleConstants.MODID + ":cuddle_normal_sender",
            CuddleConstants.MODID + ":cuddle_normal_receiver",
            "letscuddle.animation.normal", "Normal cuddle",
            CuddleConstants.DEFAULT_SPEED, CuddleConstants.DEFAULT_LOCK_TICKS, 0, null);

    public static final CuddleAnimDef TOUCH_HEAD = new CuddleAnimDef(
            CuddleConstants.BUILTIN_TOUCH_HEAD, "1",
            CuddleConstants.MODID + ":cuddle_touch_sender",
            CuddleConstants.MODID + ":cuddle_touch_receiver",
            "letscuddle.animation.touch_head", "Head-pat cuddle",
            CuddleConstants.DEFAULT_SPEED, CuddleConstants.DEFAULT_LOCK_TICKS, 10, null);

    public static final List<CuddleAnimDef> ALL = List.of(NORMAL, TOUCH_HEAD);

    private CuddleBuiltins() {}

    /** 内置动作的 id → 定义（可变副本，调用方可以继续增删）。 */
    public static Map<String, CuddleAnimDef> asMap() {
        Map<String, CuddleAnimDef> map = new LinkedHashMap<>();
        for (CuddleAnimDef def : ALL) {
            map.put(def.id(), def);
        }
        return map;
    }

    /** 内置动作的语言键；不是内置动作时返回 {@code null}。 */
    public static String builtinNameKey(String animId) {
        for (CuddleAnimDef def : ALL) {
            if (def.id().equals(animId)) {
                return def.nameKey();
            }
        }
        return null;
    }
}
