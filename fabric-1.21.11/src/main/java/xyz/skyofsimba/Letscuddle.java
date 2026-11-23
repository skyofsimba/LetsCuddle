package xyz.skyofsimba;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LetsCuddle 1.21.11 Fabric 入口（服务端逻辑）。客户端专属逻辑在 {@code xyz.skyofsimba.client.LetscuddleClient}。
 */
public final class Letscuddle implements ModInitializer {

    public static final String MODID = "letscuddle";
    public static final Logger LOGGER = LoggerFactory.getLogger("LetsCuddle");

    @Override
    public void onInitialize() {
        LOGGER.info("LetsCuddle: initializing Fabric 1.21.11 build");
    }
}
