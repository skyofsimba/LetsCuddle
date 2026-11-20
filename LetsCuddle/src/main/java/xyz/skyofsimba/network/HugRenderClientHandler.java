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
                HugAnimationEnum animation = HugAnimationEnum.byId(payload.animation());
                if (animation == null) {
                    // 未知/空 id（版本不一致或数据被改）退回默认动作，不让客户端崩
                    animation = HugAnimationEnum.NORMALHUG;
                }
                Letscuddle.LOGGER.debug("Hug start: {} <-> {} anim={}",
                        from.getName().getString(), to.getName().getString(), animation.id());
                AnimationManager.playHugAnimation(from, to, animation);
            } else {
                RenderPlayerEventHandler.unlockPlayers(from, to);
                if (payload.cancelled()) {
                    // 蹲下提前结束：立刻收掉动作，不然玩家会带着拥抱姿势走开
                    AnimationManager.stopHugAnimation(from, to);
                }
            }
        } catch (Exception e) {
            // 原实现这里会断开连接；1.20.1 上没必要，记日志即可（port-plan 附录 C.7）
            Letscuddle.LOGGER.error("LetsCuddle: failed to handle hug render payload", e);
        }
    }
}
