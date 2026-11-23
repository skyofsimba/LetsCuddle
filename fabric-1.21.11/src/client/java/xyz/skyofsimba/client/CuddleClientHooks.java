package xyz.skyofsimba.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import xyz.skyofsimba.core.CuddleAnimDef;
import xyz.skyofsimba.item.CuddleTicketItem;
import xyz.skyofsimba.network.CuddleCapsPayload;
import xyz.skyofsimba.network.CuddleSelectPayload;

/** 客户端实现：选动作、显示名、能力上报。主源码集通过 {@link CuddleClientBridge} 调用。 */
public final class CuddleClientHooks extends CuddleClientBridge {

    public static final CuddleClientHooks INSTANCE = new CuddleClientHooks();

    private CuddleClientHooks() {}

    @Override
    public void selectNextAnimation(ItemStack stack, Hand hand) {
        String next = CuddleAnimRegistry.next(CuddleTicketItem.getSelectedAnimation(stack));
        ClientPlayNetworking.send(new CuddleSelectPayload(next, hand.ordinal()));
    }

    @Override
    public Text displayName(String animId) {
        CuddleAnimDef def = CuddleAnimRegistry.get(animId);
        if (def == null) {
            return Text.literal(animId);
        }
        return def.hasNameKey()
                ? Text.translatableWithFallback(def.nameKey(), def.fallbackName())
                : Text.literal(def.fallbackName());
    }

    @Override
    public void sendCaps() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            return;
        }
        ClientPlayNetworking.send(CuddleCapsPayload.of(CuddleAnimRegistry.snapshot().caps()));
    }

    /** action bar 提示当前选择。 */
    public static void showSelected(String animId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.translatable("letscuddle.message.animation_selected",
                    INSTANCE.displayName(animId)).formatted(Formatting.AQUA), true);
        }
    }

    /** 服务端请求能力时的回包（与 {@link #sendCaps()} 等价，单独留名便于阅读）。 */
    public static void onRequestCaps() {
        INSTANCE.sendCaps();
    }
}



