package xyz.skyofsimba.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.animation.AnimationManager;
import xyz.skyofsimba.client.CuddleClientHooks;
import xyz.skyofsimba.core.CuddleAnimDef;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.client.CuddleAnimRegistry;
import xyz.skyofsimba.events.RenderPlayerEventHandler;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 仅物理客户端：由各 payload 经 {@code DistExecutor} 调入（K15）。
 *
 * <p>缺包 / 内容版本不一致时回退内置默认动作（§6.3）；连默认动作都没有才放弃播放。
 */
public final class CuddleRenderClientHandler {

    /** 已经提示过缺失的 id，避免每帧刷日志。 */
    private static final Set<String> WARNED = new HashSet<>();

    private CuddleRenderClientHandler() {}

    public static void handle(CuddleRenderPayload payload) {
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
                CuddleAnimDef def = resolve(payload.animId(), payload.contentVersion());
                if (def == null) {
                    return;
                }
                RenderPlayerEventHandler.lockPlayers(from, to);
                AnimationManager.playCuddleAnimation(from, to, def);
            } else {
                RenderPlayerEventHandler.unlockPlayers(from, to);
                if (payload.cancelled()) {
                    AnimationManager.stopCuddleAnimation(from, to);
                }
            }
        } catch (Exception e) {
            Letscuddle.LOGGER.error("LetsCuddle: failed to handle cuddle render payload", e);
        }
    }

    /** 广播里的动作在本端解析：缺失或内容版本不同都退回默认动作。 */
    private static CuddleAnimDef resolve(String animId, String contentVersion) {
        CuddleAnimDef def = CuddleAnimRegistry.get(animId);
        if (def == null) {
            warnOnce(animId);
            return CuddleAnimRegistry.get(CuddleConstants.DEFAULT_ANIM_ID);
        }
        if (contentVersion != null && !contentVersion.isEmpty() && !def.contentVersion().equals(contentVersion)) {
            warnOnce(animId + "#" + contentVersion);
            return CuddleAnimRegistry.get(CuddleConstants.DEFAULT_ANIM_ID);
        }
        return def;
    }

    private static void warnOnce(String key) {
        if (WARNED.add(key)) {
            Letscuddle.LOGGER.debug("LetsCuddle: animation {} unavailable locally, falling back to {}",
                    key, CuddleConstants.DEFAULT_ANIM_ID);
        }
    }

    public static void handleSelected(CuddleSelectedPayload payload) {
        CuddleClientHooks.showSelected(payload.animId());
    }

    /** 服务端请求能力时回包。 */
    public static void sendCaps() {
        CuddleClientHooks.sendCaps();
    }
}
