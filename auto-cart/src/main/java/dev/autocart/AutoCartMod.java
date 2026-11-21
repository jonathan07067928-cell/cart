package dev.autocart;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class AutoCartMod implements ClientModInitializer {
    public static final String MOD_ID = "autocart";

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    @Override
    public void onInitializeClient() {
        AutoCartConfig config = AutoCartConfig.load();
        AutoCartController controller = new AutoCartController(config);

        KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autocart.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // X is strictly the master ON/OFF switch; each press flips it exactly once.
            while (toggleKey.consumeClick()) {
                controller.toggle(mc);
            }
            controller.tick(mc);
        });
    }
}
