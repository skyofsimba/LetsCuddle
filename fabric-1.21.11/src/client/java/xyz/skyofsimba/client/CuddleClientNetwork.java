package xyz.skyofsimba.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import xyz.skyofsimba.Letscuddle;
import xyz.skyofsimba.core.CuddleAnimDef;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.network.CuddleRenderPayload;
import xyz.skyofsimba.network.CuddleRequestCapsPayload;
import xyz.skyofsimba.network.CuddleSelectedPayload;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端收包：加锁/解锁、选择回执、能力询问。
 *
 * <p>缺包 / 内容版本不一致时回退内置默认动作（§6.3）；连默认动作都没有才放弃。
 */
public final class CuddleClientNetwork {

    private static final Set<String> WARNED = new HashSet<>();

    private CuddleClientNetwork() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(CuddleRenderPayload.ID, (payload, context) ->
                context.client().execute(() -> handleRender(payload)));

        ClientPlayNetworking.registerGlobalReceiver(CuddleSelectedPayload.ID, (payload, context) ->
                context.client().execute(() -> CuddleClientHooks.showSelected(payload.animId())));

        ClientPlayNetworking.registerGlobalReceiver(CuddleRequestCapsPayload.ID, (payload, context) ->
                context.client().execute(() -> CuddleClientHooks.INSTANCE.sendCaps()));

        ClientTickEvents.END_CLIENT_TICK.register(client -> CuddleAnimBackend.tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CuddleAnimBackend.clear());
    }

    private static void handleRender(CuddleRenderPayload payload) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        PlayerEntity sender = client.world.getPlayerByUuid(UUID.fromString(payload.sender()));
        PlayerEntity receiver = client.world.getPlayerByUuid(UUID.fromString(payload.receiver()));
        if (sender == null || receiver == null) {
            return;
        }

        try {
            if (payload.done()) {
                CuddleAnimBackend.stop(sender, receiver);
                return;
            }
            CuddleAnimDef def = resolve(payload.animId(), payload.contentVersion());
            if (def != null) {
                CuddleAnimBackend.start(sender, receiver, def);
            }
        } catch (RuntimeException e) {
            Letscuddle.LOGGER.error("LetsCuddle: failed to handle cuddle render payload", e);
        }
    }

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
}

