package xyz.skyofsimba.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import xyz.skyofsimba.command.HugCommandHandler;

import java.util.List;

/**
 * 抱抱券：右键另一名玩家发起拥抱请求。
 *
 * <p>行为与原实现一致（KEEP K16：发起失败也返回 sidedSuccess，客户端照常播挥手动画）：
 * 服务端登记请求、立刻扣 1 张卷、给对方发带 [同意]/[拒绝] 点击按钮的复合消息。
 */
public class HugTicketItem extends Item {

    public HugTicketItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                  InteractionHand hand) {
        if (!(target instanceof Player targetPlayer)) {
            return InteractionResult.PASS;
        }

        if (!player.level().isClientSide
                && player instanceof ServerPlayer serverPlayer
                && targetPlayer instanceof ServerPlayer serverTarget) {
            if (HugCommandHandler.startHugRequest(serverPlayer, serverTarget)) {
                sendHugRequest(player, targetPlayer);
                player.sendSystemMessage(Component
                        .translatable("letscuddle.message.sendsuccess", targetPlayer.getName().getString())
                        .withStyle(ChatFormatting.GREEN));
                stack.shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.letscuddle.hug_ticket.desc").withStyle(ChatFormatting.GRAY));
    }

    /**
     * 请求消息：蓝字提示 + 绿色 [同意] + 红色 [拒绝]。
     * 按钮只发原版 {@code ClickEvent(RUN_COMMAND)}，零额外协议（原设计 3.2）。
     */
    private void sendHugRequest(Player sender, Player target) {
        String senderName = sender.getName().getString();
        Component message = Component
                .translatable("letscuddle.message.sendrequest", sender.getName())
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
