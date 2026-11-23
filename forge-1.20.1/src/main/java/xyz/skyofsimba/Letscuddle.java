package xyz.skyofsimba;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import xyz.skyofsimba.command.CuddleCommandHandler;
import xyz.skyofsimba.core.CuddleConstants;
import xyz.skyofsimba.item.CuddleTicketItem;
import xyz.skyofsimba.network.CuddleCapsStore;

import java.io.File;

/** LetsCuddle 1.20.1 Forge 入口：物品、创造栏、命令、首登发券、能力握手的服务端侧。 */
@Mod(Letscuddle.MODID)
public class Letscuddle {

    /** modid：必须与 mods.toml、资源命名空间（assets/letscuddle、data/letscuddle）一致。 */
    public static final String MODID = CuddleConstants.MODID;

    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    /** 贴贴券：64 上限堆叠，本体逻辑在 {@link CuddleTicketItem}。 */
    public static final RegistryObject<Item> CUDDLE_TICKET =
            ITEMS.register("cuddle_ticket", () -> new CuddleTicketItem(new Item.Properties().stacksTo(64)));

    public Letscuddle(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        ITEMS.register(modBus);
        modBus.addListener(this::addCreative);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CUDDLE_TICKET);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CuddleCommandHandler.register(event.getDispatcher());
    }

    /**
     * 首登判定沿用原实现：{@code world/playerdata/<uuid>.dat} 不存在即发 16 张（KEEP K20）。
     * 语义是「该存档首次登录」，不是「账号首次」。
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        CuddleCapsStore.onJoin(player);

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        File dataFile = new File(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile(),
                player.getUUID() + ".dat");
        if (dataFile.exists()) {
            return;
        }

        ItemStack stack = new ItemStack(CUDDLE_TICKET.get(), CuddleConstants.STARTER_TICKETS);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /** 换存档时不把上一个存档的能力快照带过去。 */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        CuddleCapsStore.clear();
    }
}
