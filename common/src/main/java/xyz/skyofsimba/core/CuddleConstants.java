package xyz.skyofsimba.core;

/** 两个平台共享的常量。含义与使用方见 {@code doc/architecture.md} §8。 */
public final class CuddleConstants {

    public static final String MODID = "letscuddle";

    /** 网络协议版本：2.0 起为 4（1.x 是 3）。两端不一致不能互连。 */
    public static final int PROTOCOL = 4;

    /** 动作包 manifest 格式版本。 */
    public static final int PACK_FORMAT = 1;

    /** 请求过期：60 秒。 */
    public static final long REQUEST_TIMEOUT_TICKS = 20L * 60L;

    /** 默认锁定：6 秒。 */
    public static final int DEFAULT_LOCK_TICKS = 20 * 6;
    public static final int MIN_LOCK_TICKS = 20;
    public static final int MAX_LOCK_TICKS = 400;

    /** 按住潜行多久提前结束（0.25 秒）。 */
    public static final int SNEAK_CANCEL_TICKS = 5;

    /** 接受时的最大距离。 */
    public static final double MAX_DISTANCE = 5.0D;

    /** 广播半径。 */
    public static final double NEARBY_RADIUS = 64.0D;

    /** 接受者落在发起者另一侧的格数。 */
    public static final double STAND_OFFSET = 1.3D;

    /** 站位检测半宽。 */
    public static final double STAND_HALF_WIDTH = 0.3D;

    /** 服务端等待客户端上报能力的 tick 数；超时按「只有内置动作」处理。 */
    public static final int CAPS_TIMEOUT_TICKS = 100;

    /** 客户端最多上报的动作条数。 */
    public static final int MAX_ANIMS_PER_CLIENT = 256;

    /** 注册表最多接受的动作条数（跨所有包）。 */
    public static final int MAX_TOTAL_ANIMS = 512;

    public static final int MAX_ANIM_ID_LEN = 128;
    public static final int MAX_CONTENT_VERSION_LEN = 32;
    public static final int MAX_NAME_KEY_LEN = 128;
    public static final int MAX_NAME_LEN = 64;
    public static final int MAX_ICON_LEN = 128;

    /** 单个 manifest 文件的大小上限。 */
    public static final int MAX_MANIFEST_BYTES = 16 * 1024;
    /** 单个动画 JSON 的大小上限（内置动作约 8 KiB）。 */
    public static final int MAX_ANIMATION_BYTES = 512 * 1024;

    /** 内置动作 id；永远可用。 */
    public static final String DEFAULT_ANIM_ID = MODID + ":normal";

    /** 内置动作 id 列表（manifest 在 jar 里的最低优先级包）。 */
    public static final String BUILTIN_NORMAL = DEFAULT_ANIM_ID;
    public static final String BUILTIN_TOUCH_HEAD = MODID + ":touch_head";

    /** 默认播放速度倍率；动作 JSON 是 61 tick，0.5 倍速 ≈ 122 tick，刚好略长于 120 tick 锁定。 */
    public static final float DEFAULT_SPEED = 0.5F;
    public static final float MIN_SPEED = 0.1F;
    public static final float MAX_SPEED = 2.0F;

    /** 客户端兜底：收不到结束包时最多锁这么久。 */
    public static final long CLIENT_LOCK_FALLBACK_TICKS = 200L;

    /** 首登赠送的贴贴券数量。 */
    public static final int STARTER_TICKETS = 16;

    private CuddleConstants() {}
}
