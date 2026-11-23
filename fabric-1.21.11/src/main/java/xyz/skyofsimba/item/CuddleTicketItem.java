package xyz.skyofsimba.item;

import com.mojang.serialization.Codec;
import net.minecraft.component.ComponentType;
import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import xyz.skyofsimba.command.CuddleCommandHandler;
import xyz.skyofsimba.client.CuddleClientBridge;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.core.CuddleIds;

import java.util.function.Consumer;

/**
 * 贴贴券（1.21.11）：选中的动作存在数据组件 {@code letscuddle:selected_animation} 里。
 *
 * <p>切换动作由客户端发起，服务端校验后写组件；旧 NBT 里的短 id 由 {@link CuddleIds#normalize} 迁移。
 */
public class CuddleTicketItem extends Item {

    /** 选中的动作 id；默认值是内置默认动作。 */
    public static final ComponentType<String> SELECTED_ANIMATION = Registry.register(
            Registries.DATA_COMPONENT_TYPE,
            Identifier.of(CuddleConstants.MODID, "selected_animation"),
            ComponentType.<String>builder()
                    .codec(Codec.STRING)
                    .packetCodec(PacketCodecs.STRING)
                    .build());

    public CuddleTicketItem(Settings settings) {
        super(settings);
    }

    /** 券上选中的动作 id；组件缺失/无法识别时返回默认动作。 */
    public static String getSelectedAnimation(ItemStack stack) {
        String raw = stack.get(SELECTED_ANIMATION);
        String id = CuddleIds.normalize(raw);
        return id != null ? id : CuddleConstants.DEFAULT_ANIM_ID;
    }

    /** 写回选中的动作（**只能在服务端调用**，客户端本地改动不会同步）。 */
    public static void setSelectedAnimation(ItemStack stack, String animId) {
        stack.set(SELECTED_ANIMATION, animId);
    }

    @Override
    public ActionResult use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!player.isSneaking()) {
            return ActionResult.PASS;
        }
        if (world.isClient()) {
            CuddleClientBridge.get().selectNextAnimation(stack, hand);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity player, LivingEntity entity, Hand hand) {
        if (!(entity instanceof PlayerEntity target)) {
            return ActionResult.PASS;
        }
        if (player.isSneaking()) {
            if (player.getEntityWorld().isClient()) {
                CuddleClientBridge.get().selectNextAnimation(stack, hand);
            }
            return ActionResult.SUCCESS;
        }
        if (player instanceof ServerPlayerEntity serverPlayer && target instanceof ServerPlayerEntity serverTarget) {
            if (CuddleCommandHandler.startCuddleRequest(serverPlayer, serverTarget, getSelectedAnimation(stack))) {
                stack.decrement(1);
            }
        }
        // 发起失败也返回 SUCCESS（KEEP K16）：客户端照常播挥手动画
        return ActionResult.SUCCESS;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, TooltipDisplayComponent displayComponent,
                              Consumer<Text> tooltip, TooltipType type) {
        tooltip.accept(Text.translatable("item.letscuddle.cuddle_ticket.desc").formatted(Formatting.GRAY));
        tooltip.accept(Text.translatable("item.letscuddle.cuddle_ticket.animation",
                        CuddleClientBridge.get().displayName(getSelectedAnimation(stack)))
                .formatted(Formatting.DARK_GRAY));
    }

    /** 请求消息：蓝字提示 + 绿色 [同意] + 红色 [拒绝]（只发原版 RUN_COMMAND 点击事件）。 */
    public static void sendRequestMessage(PlayerEntity sender, PlayerEntity target, Text animationName) {
        String senderName = sender.getName().getString();
        Text message = Text.translatable("letscuddle.message.sendrequest", sender.getName(), animationName)
                .formatted(Formatting.BLUE)
                .append(Text.translatable("letscuddle.message.acceptrequest").styled(style -> style
                        .withColor(Formatting.GREEN)
                        .withClickEvent(new ClickEvent.RunCommand("/letscuddle acceptRequest " + senderName))))
                .append(Text.translatable("letscuddle.message.rejectrequest").styled(style -> style
                        .withColor(Formatting.RED)
                        .withClickEvent(new ClickEvent.RunCommand("/letscuddle rejectRequest " + senderName))));
        target.sendMessage(message, false);
    }
}


