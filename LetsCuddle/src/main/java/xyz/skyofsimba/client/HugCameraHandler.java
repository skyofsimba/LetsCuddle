package xyz.skyofsimba.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import xyz.skyofsimba.Letscuddle;

/**
 * 拥抱期间的第三人称切换（仅客户端、仅当事人）。
 *
 * <p>规则（v1.4 定稿）：
 * <ul>
 *   <li>开始：记住进入前的视角，切到 {@link CameraType#THIRD_PERSON_BACK}；</li>
 *   <li>结束：只有当视角仍停留在我们切过去的 {@code THIRD_PERSON_BACK} 时才恢复原视角 ——
 *       玩家在拥抱期间自己按 F5 换过视角的，一律尊重玩家的选择，不覆盖。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Letscuddle.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HugCameraHandler {

    private static boolean active = false;
    private static CameraType previous = CameraType.FIRST_PERSON;

    private HugCameraHandler() {}

    /** 本地玩家（发起者或接受者）开始拥抱。 */
    public static void onHugStart() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options == null) {
            return;
        }
        if (!active) {
            previous = client.options.getCameraType();
            active = true;
        }
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    /** 本地玩家所在的拥抱结束（正常结束、蹲下取消、掉线、客户端兜底超时都走这里）。 */
    public static void onHugEnd() {
        if (!active) {
            return;
        }
        active = false;
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) {
            return;
        }
        // 玩家中途按过 F5 就不动他的选择，只有仍停在我们切过去的视角时才恢复
        if (client.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
            client.options.setCameraType(previous);
        }
    }

    /** 退出世界时兜底复位，避免把第三人称带到下一个存档。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        active = false;
    }
}
