package xyz.skyofsimba.animation;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 贴贴动作枚举。
 *
 * <p>v1.5 起<b>不再把 ordinal 发到网络上</b>（K18）：每个动作有稳定的 {@link #id()}，
 * 请求随贴贴券的 NBT 一起提交，网络包里也传字符串 id。以后新增动作（例如 FLYCUDDLE）
 * 只追加常量，不影响已发布的协议，也不再依赖枚举顺序。
 */
public enum CuddleAnimationEnum {

    NORMAL("normal", "letscuddle.animation.normal"),
    TOUCH_HEAD("touch_head", "letscuddle.animation.touch_head");

    private final String id;
    private final String translationKey;

    CuddleAnimationEnum(String id, String translationKey) {
        this.id = id;
        this.translationKey = translationKey;
    }

    /** 稳定 id：写进物品 NBT、发到网络上，**不要改**（改了老存档里的券会退回默认动作）。 */
    public String id() {
        return id;
    }

    /** 玩家可见的名字（lang 文件里的键）。 */
    public Component displayName() {
        return Component.translatable(translationKey);
    }

    /** 按 id 查枚举；未知 id 返回 null（调用方决定退回哪个默认值）。 */
    @Nullable
    public static CuddleAnimationEnum byId(@Nullable String id) {
        if (id != null) {
            for (CuddleAnimationEnum value : values()) {
                if (value.id.equals(id)) {
                    return value;
                }
            }
        }
        return null;
    }
}
