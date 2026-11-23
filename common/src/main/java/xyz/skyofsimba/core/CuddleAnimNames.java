package xyz.skyofsimba.core;

/**
 * 动作显示名的服务端侧回退。
 *
 * <p>服务端不解析动作包，拿不到 manifest 里的 {@code name_key}；内置动作的键是编译期常量，
 *  поэтому内置动作仍能显示本地化名字，外部动作只能显示 id。
 */
public final class CuddleAnimNames {

    private CuddleAnimNames() {}

    /** 内置动作的语言键；不是内置动作返回 {@code null}。 */
    public static String builtinNameKey(String animId) {
        if (CuddleConstants.BUILTIN_NORMAL.equals(animId)) {
            return "letscuddle.animation.normal";
        }
        if (CuddleConstants.BUILTIN_TOUCH_HEAD.equals(animId)) {
            return "letscuddle.animation.touch_head";
        }
        return null;
    }
}
