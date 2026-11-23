package xyz.skyofsimba.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 动作 id 的校验与规范化。
 *
 * <p>id 形式是 {@code <namespace>:<path>}，与资源路径一一对应；服务端不解析包，只校验字符串，
 * 所以正则与长度上限必须两端一致。
 */
public final class CuddleIds {

    /** 命名空间允许 {@code _ . -}，路径额外允许 {@code /}。 */
    private static final Pattern ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    /** 1.x 写进物品 NBT 的短 id → 2.0 的完整 id。 */
    private static final String[][] LEGACY = {
            {"normal", CuddleConstants.BUILTIN_NORMAL},
            {"touch_head", CuddleConstants.BUILTIN_TOUCH_HEAD},
    };

    private CuddleIds() {}

    /**
     * 规范化一个 id：去空白、小写、迁移 1.x 的短 id。
     *
     * @return 合法的完整 id；无法修复时返回 {@code null}
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        for (String[] pair : LEGACY) {
            if (pair[0].equals(id)) {
                return pair[1];
            }
        }
        return isValid(id) ? id : null;
    }

    /** 是否合法：格式、长度、`..` 段、路径不以斜杠开头。 */
    public static boolean isValid(String id) {
        if (id == null || id.isEmpty() || id.length() > CuddleConstants.MAX_ANIM_ID_LEN) {
            return false;
        }
        int split = id.indexOf(':');
        if (split <= 0 || split == id.length() - 1) {
            return false;
        }
        String path = id.substring(split + 1);
        if (path.startsWith("/") || path.contains("..")) {
            return false;
        }
        return ID.matcher(id).matches();
    }

    /** 是否指向内置命名空间的动作。 */
    public static boolean isBuiltin(String id) {
        return id != null && id.startsWith(CuddleConstants.MODID + ":");
    }

    /**
     * 规整内容版本：去掉首尾空白，超长截断，空串回退 {@code "1"}。
     * 内容版本只用于两端比对，不参与路径解析。
     */
    public static String contentVersion(String raw) {
        if (raw == null) {
            return "1";
        }
        String version = raw.trim();
        if (version.isEmpty()) {
            return "1";
        }
        return version.length() > CuddleConstants.MAX_CONTENT_VERSION_LEN
                ? version.substring(0, CuddleConstants.MAX_CONTENT_VERSION_LEN)
                : version;
    }

    /** 资源路径 {@code <ns>:<path>} 的命名空间；非法 id 返回空串。 */
    public static String namespace(String id) {
        int split = id == null ? -1 : id.indexOf(':');
        return split <= 0 ? "" : id.substring(0, split);
    }
}
