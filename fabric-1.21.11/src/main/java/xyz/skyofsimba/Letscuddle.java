package xyz.skyofsimba;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.skyofsimba.command.CuddleActionHandler;
import xyz.skyofsimba.command.CuddleCommandHandler;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.item.CuddleTicketItem;
import xyz.skyofsimba.network.CuddleCapsStore;
import xyz.skyofsimba.network.CuddleNetwork;

import java.nio.file.Files;
import java.nio.file.Path;

/** LetsCuddle 1.21.11 Fabric 入口：物品 / 创造栏 / 命令 / 连接事件 / 能力握手。 */
public final class Letscuddle implements ModInitializer {

    public static final String MODID = CuddleConstants.MODID;
    public static final Logger LOGGER = LoggerFactory.getLogger("LetsCuddle");

    public static final RegistryKey<Item> CUDDLE_TICKET_KEY =
            RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MODID, "cuddle_ticket"));

    /** 贴贴券：64 上限堆叠，选中的动作是数据组件。 */
    public static final Item CUDDLE_TICKET = new CuddleTicketItem(new Item.Settings()
            .maxCount(64)
            .registryKey(CUDDLE_TICKET_KEY)
            .component(CuddleTicketItem.SELECTED_ANIMATION, CuddleConstants.DEFAULT_ANIM_ID));

    /** 创造模式「贴贴」标签页。 */
    public static final RegistryKey<ItemGroup> CUDDLE_TAB_KEY =
            RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(MODID, "main"));

    private static final ItemGroup CUDDLE_TAB = FabricItemGroup.builder()
            .displayName(Text.translatable("itemGroup.letscuddle"))
            .icon(() -> new ItemStack(CUDDLE_TICKET))
            .entries((context, entries) -> entries.add(CUDDLE_TICKET))
            .build();

    @Override
    public void onInitialize() {
        Registry.register(Registries.ITEM, CUDDLE_TICKET_KEY, CUDDLE_TICKET);
        Registry.register(Registries.ITEM_GROUP, CUDDLE_TAB_KEY, CUDDLE_TAB);

        CuddleNetwork.register();
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> CuddleCommandHandler.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(CuddleCommandHandler::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CuddleCapsStore.clear());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            CuddleCapsStore.onJoin(handler.player);
            grantStarterTickets(handler.player, server);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CuddleCapsStore.onDisconnect(handler.player);
            CuddleActionHandler.onDisconnect(handler.player.getUuid());
            CuddleCommandHandler.onDisconnect(handler.player);
        });

        LOGGER.info("LetsCuddle: loaded (protocol {})", CuddleConstants.PROTOCOL);
    }

    /**
     * 首登判定与 1.20.1 一致：{@code world/playerdata/<uuid>.dat} 不存在即发 16 张。
     * 语义是「该存档首次登录」，不是「账号首次」。
     */
    private static void grantStarterTickets(ServerPlayerEntity player, MinecraftServer server) {
        Path dataFile = server.getSavePath(WorldSavePath.PLAYERDATA).resolve(player.getUuid() + ".dat");
        if (Files.exists(dataFile)) {
            return;
        }
        ItemStack stack = new ItemStack(CUDDLE_TICKET, CuddleConstants.STARTER_TICKETS);
        if (!player.getInventory().insertStack(stack)) {
            player.dropItem(stack, false);
        }
    }
}
