package xyz.skyofsimba.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.animation.AnimationManager;
import xyz.skyofsimba.animation.HugAnimationEnum;
import xyz.skyofsimba.events.RenderPlayerEventHandler;

import java.util.UUID;

/**
 * 仅物理客户端：由 {@link HugRenderPayload#handle} 经 {@code DistExecutor} 调入。
 * 服务端绝不引用这个类（K15）。
 */
public final class HugRenderClientHandler {

    private HugRenderClientHandler() {}

    public static void handle(HugRenderPayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }

        Player sender = client.level.getPlayerByUUID(UUID.fromString(payload.sender()));
        Player receiver = client.level.getPlayerByUUID(UUID.fromString(payload.receiver()));
        if (!(sender instanceof AbstractClientPlayer from) || !(receiver instanceof AbstractClientPlayer to)) {
            return;
        }

        try {
            if (!payload.done()) {
                RenderPlayerEventHandler.lockPlayers(from, to);
                HugAnimationEnum[] values = HugAnimationEnum.values();
                int index = payload.animation();
                if (index < 0 || index >= values.length) {
                    index = 0;                       // PORT-FIX K18 兜底：越界序号退回默认动作
                }
                Letscuddle.LOGGER.debug("Hug start: {} <-> {} anim={}",
                        from.getName().getString(), to.getName().getString(), values[index]);
                AnimationManager.playHugAnimation(from, to, values[index]);
            } else {
                RenderPlayerEventHandler.unlockPlayers(from, to);
            }
        } catch (Exception e) {
            // 原实现这里会断开连接；1.20.1 上没必要，记日志即可（port-plan 附录 C.7）
            Letscuddle.LOGGER.error("LetsCuddle: failed to handle hug render payload", e);
        }
    }
}
