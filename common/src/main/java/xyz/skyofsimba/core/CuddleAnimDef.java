package xyz.skyofsimba.core;

/**
 * 一个动作的不可变定义，来源是动作包里的 manifest。
 *
 * @param id             动作 id（等于 manifest 路径，不来自字段）
 * @param contentVersion 内容版本，两端比对用
 * @param senderId       发起者动画 id
 * @param receiverId     接受者动画 id
 * @param nameKey        语言键（可空）
 * @param name           纯文本回退名（可空）
 * @param speed          播放速度倍率
 * @param lockTicks      锁定 tick
 * @param order          排序权重，小的在前
 * @param icon           v1 保留字段，当前忽略
 */
public record CuddleAnimDef(String id, String contentVersion, String senderId, String receiverId,
                            String nameKey, String name, float speed, int lockTicks, int order, String icon) {

    /** 平台层显示名的回退链：name_key → name → id。 */
    public String fallbackName() {
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return id;
    }

    public boolean hasNameKey() {
        return nameKey != null && !nameKey.isEmpty();
    }
}
