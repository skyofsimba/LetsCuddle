package xyz.skyofsimba.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import xyz.skyofsimba.core.CuddleAnimDef;
import xyz.skyofsimba.item.CuddleTicketItem;
import xyz.skyofsimba.network.CuddleCapsPayload;
import xyz.skyofsimba.network.CuddleNetwork;
import xyz.skyofsimba.network.CuddleSelectPayload;

/**
 * 只能从物理客户端触碰的入口（共享类通过 {@code DistExecutor} 调入，K15）。
 *
 * <p>客户端负责「下一个动作是谁」——只有它知道装了哪些包；服务端负责校验与写 NBT。
 */
public final class CuddleClientHooks {

    private CuddleClientHooks() {}

    /** 循环切到下一个动作，发 {@link CuddleSelectPayload}。 */
    public static void selectNextAnimation(ItemStack stack, InteractionHand hand) {
        String next = CuddleAnimRegistry.next(CuddleTicketItem.getSelectedAnimation(stack));
        CuddleNetwork.sendSelectToServer(new CuddleSelectPayload(next, hand.ordinal()));
    }

    /** 动作显示名：name_key → name → id。 */
    public static Component displayName(String animId) {
        CuddleAnimDef def = CuddleAnimRegistry.get(animId);
        if (def == null) {
            return Component.literal(animId);
        }
        return def.hasNameKey()
                ? Component.translatableWithFallback(def.nameKey(), def.fallbackName())
                : Component.literal(def.fallbackName());
    }

    /** 把当前注册表的能力上报给服务端（登录后、F3+T 重载后）。 */
    public static void sendCaps() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return;
        }
        CuddleNetwork.sendCapsToServer(CuddleCapsPayload.of(CuddleAnimRegistry.snapshot().caps()));
    }

    /** action bar 提示当前选择。 */
    public static void showSelected(String animId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.translatable("letscuddle.message.animation_selected", displayName(animId))
                            .withStyle(ChatFormatting.AQUA),
                    true);
        }
    }
}
