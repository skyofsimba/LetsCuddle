package xyz.skyofsimba.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 动作 JSON（{@code emote} 格式）的解析，只保留第三人称镜头需要的 {@code body} 水平位移。
 *
 * <p>动画本身由平台动画库播放（1.20.1 playerAnimator、1.21.11 PAL）；这里复刻 playerAnimator
 * 的采样语义，把模型相对实体坐标的偏移同步到相机：
 * <ul>
 *   <li>每个轴是独立通道；通道没有关键帧时取 0；</li>
 *   <li>首帧之前从 0 插值到首帧（INOUTSINE）；两帧之间用<b>前一帧</b>的缓动；</li>
 *   <li>{@code endTick} 之后保持最后一帧，到 {@code stopTick} 回落到 0。</li>
 * </ul>
 */
public final class CuddleAnimClip {

    private static final String BODY = "body";
    /** 旧动作 JSON 用 {@code torso} 表示根骨骼。 */
    private static final String TORSO = "torso";
    private static final String X = "x";
    private static final String Z = "z";

    private final Map<String, Channel> channels;
    private final String name;
    private final int beginTick;
    private final int endTick;
    private final int stopTick;

    private CuddleAnimClip(Map<String, Channel> channels, String name, int beginTick, int endTick, int stopTick) {
        this.channels = channels;
        this.name = name;
        this.beginTick = beginTick;
        this.endTick = endTick;
        this.stopTick = stopTick;
    }

    /** JSON 根节点的 {@code name}（动画 id 的 path）；缺失、为空或含冒号时返回 {@code null}。 */
    public String name() {
        return name;
    }

    /**
     * 解析一份动作 JSON。
     *
     * @return 解析结果；JSON 结构不可用时返回 {@code null}
     */
    public static CuddleAnimClip parse(String json) {
        if (json == null || json.isBlank()
                || json.getBytes(StandardCharsets.UTF_8).length > CuddleConstants.MAX_ANIMATION_BYTES) {
            return null;
        }
        JsonObject root;
        JsonObject emote;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return null;
            }
            root = parsed.getAsJsonObject();
            JsonElement element = root.get("emote");
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            emote = element.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }

        int begin = intField(emote, "beginTick", 0);
        int end = intField(emote, "endTick", 0);
        int stop = intField(emote, "stopTick", end);
        if (stop < end) {
            stop = end;
        }

        Map<String, Channel> channels = new HashMap<>();
        JsonElement moves = emote.get("moves");
        if (moves != null && moves.isJsonArray()) {
            for (JsonElement move : moves.getAsJsonArray()) {
                if (move.isJsonObject()) {
                    parseMove(move.getAsJsonObject(), channels);
                }
            }
        }
        return new CuddleAnimClip(channels, nameOf(root), begin, end, stop);
    }

    /** 只取 {@code body}/{@code torso} 的 x、z 位移；旋转与其它骨骼与镜头无关。 */
    private static void parseMove(JsonObject move, Map<String, Channel> channels) {
        if (!move.has("tick")) {
            return;
        }
        int tick;
        try {
            tick = move.get("tick").getAsInt();
        } catch (RuntimeException e) {
            return;
        }
        String easingName = move.has("easing") && isString(move.get("easing"))
                ? move.get("easing").getAsString()
                : "linear";
        float ease = Easing.weight(easingName);

        for (Map.Entry<String, JsonElement> entry : move.entrySet()) {
            String bone = TORSO.equals(entry.getKey()) ? BODY : entry.getKey();
            if (!BODY.equals(bone) || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject part = entry.getValue().getAsJsonObject();
            float x = floatField(part, X);
            if (!Float.isNaN(x)) {
                channel(channels, BODY + "." + X).add(tick, x, ease);
            }
            float z = floatField(part, Z);
            if (!Float.isNaN(z)) {
                channel(channels, BODY + "." + Z).add(tick, z, ease);
            }
        }
    }

    private static String nameOf(JsonObject root) {
        JsonElement element = root.get("name");
        if (!isString(element)) {
            return null;
        }
        String value = element.getAsString().trim();
        return value.isEmpty() || value.contains(":") ? null : value;
    }

    private static Channel channel(Map<String, Channel> channels, String key) {
        return channels.computeIfAbsent(key, ignored -> new Channel());
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && ((JsonPrimitive) element).isString();
    }

    private static int intField(JsonObject object, String field, int fallback) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsInt();
    }

    private static float floatField(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return Float.NaN;
        }
        return element.getAsFloat();
    }

    // ------------------------------------------------------------------ 采样

    /** {@code body} 在某一 tick 的 X 位移（米，模型空间）。 */
    public float bodyX(float tick) {
        return sample(BODY + "." + X, tick);
    }

    /** {@code body} 在某一 tick 的 Z 位移（米，模型空间；-1 表示压进对方怀里 1 格）。 */
    public float bodyZ(float tick) {
        return sample(BODY + "." + Z, tick);
    }

    private float sample(String key, float tick) {
        Channel channel = channels.get(key);
        return channel == null ? 0F : channel.sample(tick, beginTick, endTick, stopTick);
    }

    /** 一条通道：按 tick 升序的关键帧。 */
    private static final class Channel {

        private record Frame(int tick, float value, float ease) {}

        private final List<Frame> frames = new ArrayList<>();

        void add(int tick, float value, float ease) {
            int index = 0;
            while (index < frames.size() && frames.get(index).tick() <= tick) {
                index++;
            }
            frames.add(index, new Frame(tick, value, ease));
        }

        float sample(float tick, int beginTick, int endTick, int stopTick) {
            if (frames.isEmpty()) {
                return 0F;
            }
            int pos = -1;
            while (pos + 1 < frames.size() && frames.get(pos + 1).tick() <= tick) {
                pos++;
            }

            Frame before;
            Frame after;
            if (pos < 0) {
                // 首帧之前：从 beginTick 的默认值（0）插值到首帧
                if (tick < beginTick) {
                    return 0F;
                }
                before = new Frame(beginTick, 0F, Easing.INOUTSINE);
                after = frames.get(0);
            } else {
                before = frames.get(pos);
                if (pos == frames.size() - 1 && tick >= endTick && before.tick() < endTick) {
                    // 末帧发生在 endTick 之前：先保持在 endTick 的值，再回落
                    before = new Frame(endTick, before.value(), before.ease());
                }
                if (pos + 1 < frames.size()) {
                    after = frames.get(pos + 1);
                } else if (tick < endTick) {
                    // 末帧之后保持到 endTick
                    after = new Frame(endTick, before.value(), before.ease());
                } else {
                    // endTick → stopTick 回落到默认值
                    after = new Frame(stopTick, 0F, before.ease());
                }
            }

            if (before.tick() == after.tick()) {
                return before.value();
            }
            float progress = (tick - before.tick()) / (float) (after.tick() - before.tick());
            return lerp(Easing.apply(progress, before.ease()), before.value(), after.value());
        }

        private static float lerp(float delta, float start, float end) {
            return start + delta * (end - start);
        }
    }

    /** 缓动函数；{@link #weight(String)} 把 JSON 里的名字转成内部编号。 */
    private static final class Easing {

        static final float LINEAR = 0F;
        static final float INOUTSINE = 1F;
        static final float INQUAD = 2F;
        static final float OUTQUAD = 3F;
        static final float INOUTQUAD = 4F;
        static final float CONSTANT = 5F;

        static float weight(String name) {
            switch (name.toLowerCase(Locale.ROOT)) {
                case "easeinoutquad", "inoutquad":
                    return INOUTQUAD;
                case "easeinquad", "inquad":
                    return INQUAD;
                case "easeoutquad", "outquad":
                    return OUTQUAD;
                case "easeinoutsine", "inoutsine":
                    return INOUTSINE;
                case "constant":
                    return CONSTANT;
                default:
                    return LINEAR;
            }
        }

        static float apply(float progress, float easing) {
            float f = progress < 0F ? 0F : Math.min(progress, 1F);
            if (easing == INOUTQUAD) {
                return (float) (f < 0.5F ? 2.0F * f * f : 1.0D - Math.pow(-2.0F * f + 2.0F, 2.0D) / 2.0D);
            }
            if (easing == INQUAD) {
                return f * f;
            }
            if (easing == OUTQUAD) {
                return 1.0F - (1.0F - f) * (1.0F - f);
            }
            if (easing == INOUTSINE) {
                return (float) (-(Math.cos(Math.PI * (double) f) - 1.0D) / 2.0D);
            }
            if (easing == CONSTANT) {
                return 0F;
            }
            return f;
        }

        private Easing() {}
    }
}
