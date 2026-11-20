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
import org.jetbrains.annotations.Nullable;
import xyz.skyofsimba.animation.HugAnimationEnum;
import xyz.skyofsimba.command.HugCommandHandler;

import java.util.List;

/**
 * 抱抱券：右键另一名玩家发起拥抱请求。
 *
 * <p>行为与原实现一致（KEEP K16：发起失败也返回 sidedSuccess，客户端照常播挥手动画）：
 * 服务端登记请求、立刻扣 1 张券、给对方发带 [同意]/[拒绝] 点击按钮的复合消息。
 *
 * <p>v1.5：动作由发起者自选 —— 选中的动作记在物品 NBT（{@value #ANIMATION_TAG}）里，
 * {@code Shift + 右键}（空中或玩家身上都行）循环切换，服务端改 NBT 后自动同步给客户端，
 * 所以不需要额外的 C2S 包；发起请求时服务端直接读手上的券。
 */
public class HugTicketItem extends Item {

    /** 物品 NBT 里记录动作 id 的键。 */
    private static final String ANIMATION_TAG = "Animation";

    public HugTicketItem(Properties properties) {
        super(properties);
    }

    /** 读取券上选中的动作；没有 NBT（老券、创造栏刚拿的）时返回默认动作。 */
    public static HugAnimationEnum getSelectedAnimation(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        HugAnimationEnum animation = tag == null ? null : HugAnimationEnum.byId(tag.getString(ANIMATION_TAG));
        return animation != null ? animation : HugAnimationEnum.NORMALHUG;
    }

    /** 循环切到下一个动作并写回 NBT（**只能在服务端调用**，否则客户端本地改动不会同步）。 */
    private static HugAnimationEnum cycleSelectedAnimation(ItemStack stack) {
        HugAnimationEnum[] values = HugAnimationEnum.values();
        HugAnimationEnum next = values[(getSelectedAnimation(stack).ordinal() + 1) % values.length];
        stack.getOrCreateTag().putString(ANIMATION_TAG, next.id());
        return next;
    }

    /** Shift + 右键空气：切换动作，action bar 提示当前选择。（1.20.1 的 Item#use 返回 InteractionResultHolder） */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            notifySelection(serverPlayer, cycleSelectedAnimation(stack));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                  InteractionHand hand) {
        if (!(target instanceof Player targetPlayer)) {
            return InteractionResult.PASS;
        }

        // Shift + 右键玩家：只切换动作，不发请求（避免误触发起拥抱）
        if (player.isShiftKeyDown()) {
            if (!player.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
                notifySelection(serverPlayer, cycleSelectedAnimation(stack));
            }
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }

        if (!player.level().isClientSide
                && player instanceof ServerPlayer serverPlayer
                && targetPlayer instanceof ServerPlayer serverTarget) {
            HugAnimationEnum animation = getSelectedAnimation(stack);
            if (HugCommandHandler.startHugRequest(serverPlayer, serverTarget, animation)) {
                sendHugRequest(player, targetPlayer, animation);
                player.sendSystemMessage(Component
                        .translatable("letscuddle.message.sendsuccess",
                                targetPlayer.getName().getString(), animation.displayName())
                        .withStyle(ChatFormatting.GREEN));
                stack.shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.letscuddle.hug_ticket.desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.letscuddle.hug_ticket.animation",
                getSelectedAnimation(stack).displayName()).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void notifySelection(ServerPlayer player, HugAnimationEnum animation) {
        player.displayClientMessage(
                Component.translatable("letscuddle.message.animation_selected", animation.displayName())
                        .withStyle(ChatFormatting.AQUA),
                true);
    }

    /**
     * 请求消息：蓝字提示 + 绿色 [同意] + 红色 [拒绝]。
     * 按钮只发原版 {@code ClickEvent(RUN_COMMAND)}，零额外协议（原设计 3.2）。
     */
    private void sendHugRequest(Player sender, Player target, HugAnimationEnum animation) {
        String senderName = sender.getName().getString();
        Component message = Component
                .translatable("letscuddle.message.sendrequest", sender.getName(), animation.displayName())
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
