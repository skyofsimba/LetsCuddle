package xyz.skyofsimba.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;

/**
 * 贴贴期间的第三人称切换（与 1.20.1 同规则）：
 * 开始自动切 {@code THIRD_PERSON_BACK}，结束时只有玩家没有自己按过 F5 才恢复原视角。
 */
public final class CuddleCameraHandler {

    private static boolean active = false;
    private static Perspective previous = Perspective.FIRST_PERSON;

    private CuddleCameraHandler() {}

    public static void onCuddleStart() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options == null) {
            return;
        }
        if (!active) {
            previous = client.options.getPerspective();
            active = true;
        }
        client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
    }

    public static void onCuddleEnd() {
        if (!active) {
            return;
        }
        active = false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options == null) {
            return;
        }
        if (client.options.getPerspective() == Perspective.THIRD_PERSON_BACK) {
            client.options.setPerspective(previous);
        }
    }

    /** 退出世界时复位，避免把第三人称带到下一个存档。 */
    public static void reset() {
        active = false;
    }
}
