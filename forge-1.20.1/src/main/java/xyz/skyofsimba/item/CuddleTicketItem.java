package xyz.skyofsimba.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;
import xyz.skyofsimba.client.CuddleClientHooks;
import xyz.skyofsimba.command.CuddleCommandHandler;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.core.CuddleIds;

import java.util.List;

/**
 * 贴贴券：右键另一名玩家发起请求，潜行 + 右键循环切换动作。
 *
 * <p>选中的动作以字符串 id 存在 NBT {@value #ANIMATION_TAG} 里；1.x 的短 id（{@code normal} /
 * {@code touch_head}）由 {@link CuddleIds#normalize} 自动迁移。
 * 切换动作由客户端发起（{@link xyz.skyofsimba.network.CuddleSelectPayload}），服务端校验后写 NBT。
 */
public class CuddleTicketItem extends Item {

    /** 物品 NBT 里记录动作 id 的键（沿用 1.x 的键名，旧券直接可读）。 */
    private static final String ANIMATION_TAG = "Animation";

    public CuddleTicketItem(Properties properties) {
        super(properties);
    }

    /** 券上选中的动作 id；没有 NBT / 无法识别时返回默认动作。 */
    public static String getSelectedAnimation(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            String id = CuddleIds.normalize(tag.getString(ANIMATION_TAG));
            if (id != null) {
                return id;
            }
        }
        return CuddleConstants.DEFAULT_ANIM_ID;
    }

    /** 写回选中的动作（**只能在服务端调用**，否则客户端本地改动不会同步）。 */
    public static void setSelectedAnimation(ItemStack stack, String animId) {
        stack.getOrCreateTag().putString(ANIMATION_TAG, animId);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CuddleClientHooks.selectNextAnimation(stack, hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                  InteractionHand hand) {
        if (!(target instanceof Player targetPlayer)) {
            return InteractionResult.PASS;
        }

        // 潜行 + 右键玩家：只切换动作，不发起请求（避免误触）
        if (player.isShiftKeyDown()) {
            if (player.level().isClientSide) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> CuddleClientHooks.selectNextAnimation(stack, hand));
            }
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }

        if (!player.level().isClientSide
                && player instanceof ServerPlayer serverPlayer
                && targetPlayer instanceof ServerPlayer serverTarget) {
            if (CuddleCommandHandler.startCuddleRequest(serverPlayer, serverTarget, getSelectedAnimation(stack))) {
                stack.shrink(1);
            }
        }
        // 发起失败也返回 sidedSuccess（KEEP K16）：客户端照常播挥手动画
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.letscuddle.cuddle_ticket.desc").withStyle(ChatFormatting.GRAY));
        String animId = getSelectedAnimation(stack);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> tooltip.add(Component
                .translatable("item.letscuddle.cuddle_ticket.animation", CuddleClientHooks.displayName(animId))
                .withStyle(ChatFormatting.DARK_GRAY)));
    }

    /** 给某人发一条带 [同意]/[拒绝] 按钮的请求消息（原设计 3.2：只用原版 RUN_COMMAND 点击事件）。 */
    public static void sendRequestMessage(Player sender, Player target, Component animationName) {
        String senderName = sender.getName().getString();
        Component message = Component
                .translatable("letscuddle.message.sendrequest", sender.getName(), animationName)
                .withStyle(ChatFormatting.BLUE)
                .append(Component.translatable("letscuddle.message.acceptrequest").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/letscuddle acceptRequest " + senderName))))
                .append(Component.translatable("letscuddle.message.rejectrequest").withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/letscuddle rejectRequest " + senderName))));
        target.sendSystemMessage(message);
    }
}
