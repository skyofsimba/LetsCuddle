package xyz.skyofsimba.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 动作包 manifest 的解析与校验（格式 v1，见 {@code doc/animation-pack-format.md} §2）。
 *
 * <p>原则：**一个笔误只废掉这一条**。结构性错误（format / sender / receiver）拒绝整条；
 * 数值越界只夹紧并记警告。
 */
public final class CuddleAnimManifest {

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "format", "content_version", "sender", "receiver", "name_key", "name",
            "speed", "lock_ticks", "order", "icon");

    /** 解析结果；{@code def == null} 表示被拒绝，原因在 {@code error}。 */
    public record Result(CuddleAnimDef def, String error, List<String> warnings) {

        public boolean ok() {
            return def != null;
        }

        static Result reject(String error, List<String> warnings) {
            return new Result(null, error, Collections.unmodifiableList(warnings));
        }

        static Result accept(CuddleAnimDef def, List<String> warnings) {
            return new Result(def, null, Collections.unmodifiableList(warnings));
        }
    }

    private CuddleAnimManifest() {}

    /**
     * 解析一条 manifest。
     *
     * @param id   manifest 的资源路径推出的动作 id（{@code <ns>:<path>}）
     * @param json 文件内容
     */
    public static Result parse(String id, String json) {
        List<String> warnings = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return Result.reject("empty file", warnings);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > CuddleConstants.MAX_MANIFEST_BYTES) {
            return Result.reject("manifest larger than " + CuddleConstants.MAX_MANIFEST_BYTES + " bytes", warnings);
        }

        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return Result.reject("top level is not an object", warnings);
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return Result.reject("invalid JSON: " + e.getMessage(), warnings);
        }

        for (String field : root.keySet()) {
            if (!KNOWN_FIELDS.contains(field)) {
                warnings.add("unknown field '" + field + "' ignored");
            }
        }

        // format：必须存在且为 1
        JsonElement formatElement = root.get("format");
        if (formatElement == null || !isNumber(formatElement)) {
            return Result.reject("missing or non-numeric 'format'", warnings);
        }
        int format = formatElement.getAsInt();
        if (format != CuddleConstants.PACK_FORMAT) {
            return Result.reject(format > CuddleConstants.PACK_FORMAT
                    ? "format " + format + " needs a newer LetsCuddle"
                    : "unsupported format " + format, warnings);
        }

        // sender / receiver：必须存在、是字符串、是合法 id
        String sender = readId(root, "sender", warnings);
        if (sender == null) {
            return Result.reject("missing or invalid 'sender'", warnings);
        }
        String receiver = readId(root, "receiver", warnings);
        if (receiver == null) {
            return Result.reject("missing or invalid 'receiver'", warnings);
        }

        String contentVersion = CuddleIds.contentVersion(
                readString(root, "content_version", CuddleConstants.MAX_CONTENT_VERSION_LEN,
                        "1", "content_version", warnings));

        String nameKey = readString(root, "name_key", CuddleConstants.MAX_NAME_KEY_LEN, null, "name_key", warnings);
        String name = readString(root, "name", CuddleConstants.MAX_NAME_LEN, null, "name", warnings);
        String icon = readString(root, "icon", CuddleConstants.MAX_ICON_LEN, null, "icon", warnings);

        float speed = (float) readNumber(root, "speed", CuddleConstants.DEFAULT_SPEED,
                CuddleConstants.MIN_SPEED, CuddleConstants.MAX_SPEED, warnings);
        int lockTicks = CuddleCaps.clampLock((int) readNumber(root, "lock_ticks",
                CuddleConstants.DEFAULT_LOCK_TICKS, CuddleConstants.MIN_LOCK_TICKS,
                CuddleConstants.MAX_LOCK_TICKS, warnings));
        int order = (int) readNumber(root, "order", 0, Integer.MIN_VALUE, Integer.MAX_VALUE, warnings);

        CuddleAnimDef def = new CuddleAnimDef(id, contentVersion, sender, receiver,
                nameKey, name, speed, lockTicks, order, icon);
        return Result.accept(def, warnings);
    }

    // ------------------------------------------------------------------ 字段读取

    private static String readId(JsonObject root, String field, List<String> warnings) {
        JsonElement element = root.get(field);
        if (element == null || !isString(element)) {
            return null;
        }
        String id = CuddleIds.normalize(element.getAsString());
        if (id == null) {
            warnings.add("'" + field + "' is not a valid id");
        }
        return id;
    }

    private static String readString(JsonObject root, String field, int maxLength, String fallback,
                                     String label, List<String> warnings) {
        JsonElement element = root.get(field);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!isString(element)) {
            warnings.add("'" + label + "' is not a string; using default");
            return fallback;
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) {
            return fallback;
        }
        if (value.length() > maxLength) {
            warnings.add("'" + label + "' longer than " + maxLength + "; truncated");
            return value.substring(0, maxLength);
        }
        return value;
    }

    private static double readNumber(JsonObject root, String field, double fallback,
                                     double min, double max, List<String> warnings) {
        JsonElement element = root.get(field);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!isNumber(element)) {
            warnings.add("'" + field + "' is not a number; using default");
            return fallback;
        }
        double value = element.getAsDouble();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            warnings.add("'" + field + "' is not finite; using default");
            return fallback;
        }
        if (value < min || value > max) {
            warnings.add("'" + field + "' out of range [" + min + ", " + max + "]; clamped");
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && ((JsonPrimitive) element).isString();
    }

    private static boolean isNumber(JsonElement element) {
        return element.isJsonPrimitive() && ((JsonPrimitive) element).isNumber();
    }
}
