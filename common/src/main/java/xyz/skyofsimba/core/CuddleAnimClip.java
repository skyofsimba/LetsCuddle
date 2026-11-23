package xyz.skyofsimba.core;

import com.google.gson.JsonArray;
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
 * 内置/第三方动作 JSON（Emotecraft 的 {@code emote} 格式）的解析与采样。
 *
 * <p>这是 1.21.11 Fabric 端的内部播放器：1.20.1 用 playerAnimator，这边没有等价库，
 * 所以按 playerAnimator 1.0.2 的采样语义逐条复刻（{@code KeyframeAnimationPlayer}）：
 * <ul>
 *   <li>每个「骨骼 × 轴」是独立通道，各自插值；</li>
 *   <li>通道没有关键帧时取默认值 0；</li>
 *   <li>首帧之前从 {@code beginTick} 的默认值插值到首帧（缓动用隐式关键帧的 INOUTSINE）；</li>
 *   <li>两帧之间用<b>前一帧</b>的缓动；</li>
 *   <li>{@code endTick} 之后用最后一帧的值保持，到 {@code stopTick} 再回落到默认值。</li>
 * </ul>
 */
public final class CuddleAnimClip {

    /** JSON 里的骨骼名（{@code torso} 是旧名，等价于 {@code body}）。 */
    private static final String[] BONES = {"body", "head", "leftArm", "rightArm", "leftLeg", "rightLeg"};
    /** 三个位移轴（米）。 */
    private static final String[] POSITIONS = {"x", "y", "z"};
    /** 三个旋转轴（弧度）。 */
    private static final String[] ROTATIONS = {"pitch", "yaw", "roll"};

    private static final float TWO_PI = (float) (Math.PI * 2.0);

    private final Map<String, Channel> channels;
    private final int beginTick;
    private final int endTick;
    private final int stopTick;

    private CuddleAnimClip(Map<String, Channel> channels, int beginTick, int endTick, int stopTick) {
        this.channels = channels;
        this.beginTick = beginTick;
        this.endTick = endTick;
        this.stopTick = stopTick;
    }

    /** 动画自身的总 tick（{@code stopTick}），播放器据此判断是否播完。 */
    public int stopTick() {
        return stopTick;
    }

    public int endTick() {
        return endTick;
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
        JsonObject emote;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonElement element = root.getAsJsonObject().get("emote");
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
        boolean degrees = emote.has("degrees") && emote.get("degrees").isJsonPrimitive()
                && emote.getAsJsonPrimitive("degrees").isBoolean()
                && emote.getAsJsonPrimitive("degrees").getAsBoolean();

        Map<String, Channel> channels = new HashMap<>();
        JsonElement moves = emote.get("moves");
        if (moves != null && moves.isJsonArray()) {
            for (JsonElement moveElement : moves.getAsJsonArray()) {
                if (!moveElement.isJsonObject()) {
                    continue;
                }
                parseMove(moveElement.getAsJsonObject(), degrees, channels);
            }
        }
        return new CuddleAnimClip(channels, begin, end, stop);
    }

    private static void parseMove(JsonObject move, boolean degrees, Map<String, Channel> channels) {
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
        int turn = move.has("turn") ? move.get("turn").getAsInt() : 0;

        for (Map.Entry<String, JsonElement> entry : move.entrySet()) {
            String boneName = "torso".equals(entry.getKey()) ? "body" : entry.getKey();
            if (!isBone(boneName) || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject part = entry.getValue().getAsJsonObject();
            for (String axis : POSITIONS) {
                float value = floatField(part, axis);
                if (!Float.isNaN(value)) {
                    channel(channels, boneName, axis).add(tick, value, ease);
                }
            }
            for (String axis : ROTATIONS) {
                float value = floatField(part, axis);
                if (!Float.isNaN(value)) {
                    if (degrees) {
                        value = (float) Math.toRadians(value);
                    }
                    value += TWO_PI * turn;
                    channel(channels, boneName, axis).add(tick, value, ease);
                }
            }
        }
    }

    private static Channel channel(Map<String, Channel> channels, String bone, String axis) {
        return channels.computeIfAbsent(bone + "." + axis, key -> new Channel());
    }

    private static boolean isBone(String name) {
        for (String bone : BONES) {
            if (bone.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && ((JsonPrimitive) element).isString();
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

    /** 采样某一 tick 的姿势（tick 可以为小数，用于帧插值）。 */
    public CuddlePose sample(float tick) {
        return new CuddlePose(
                bone("body", tick),
                bone("head", tick),
                bone("leftArm", tick),
                bone("rightArm", tick),
                bone("leftLeg", tick),
                bone("rightLeg", tick));
    }

    /** 某个通道在某一 tick 的值；没有该通道时为 0。 */
    public float value(String bone, String axis, float tick) {
        Channel channel = channels.get(bone + "." + axis);
        return channel == null ? 0F : channel.sample(tick, beginTick, endTick, stopTick);
    }

    private Bone bone(String name, float tick) {
        Channel x = channels.get(name + ".x");
        Channel y = channels.get(name + ".y");
        Channel z = channels.get(name + ".z");
        Channel pitch = channels.get(name + ".pitch");
        Channel yaw = channels.get(name + ".yaw");
        Channel roll = channels.get(name + ".roll");
        return new Bone(
                sampleOrZero(x, tick), sampleOrZero(y, tick), sampleOrZero(z, tick),
                sampleOrZero(yaw, tick), sampleOrZero(pitch, tick), sampleOrZero(roll, tick));
    }

    private float sampleOrZero(Channel channel, float tick) {
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
