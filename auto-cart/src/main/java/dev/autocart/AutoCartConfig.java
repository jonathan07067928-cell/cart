package dev.autocart;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Simple JSON config stored in config/autocart.json. Edit the file; it is read at game start. */
public final class AutoCartConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCart");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Ticks to pause between stages (20 ticks = 1 second). */
    public int stepDelayTicks = 3;
    /** Extra ticks to wait before checking a result; ping-based time is added on top. */
    public int verifyDelayTicks = 3;
    /** Max ticks to wait for a server-confirmed result (cart spawned, ignited, crossbow fired). */
    public int verifyTimeoutTicks = 40;
    /** Max ticks any single stage may take before the sequence is aborted. */
    public int stageTimeoutTicks = 100;
    /** Ticks between ignition confirmation and crossbow shot. */
    public int fireDelayTicks = 1;
    /** Rail is searched this many blocks in front of the player (min..max). */
    public int railDistanceMin = 2;
    public int railDistanceMax = 3;
    /** Horizontal distance behind the cart's centre where the player stands to ignite it. Must be under ~3. */
    public double standDistance = 2.5;
    /** Walking speed (blocks/tick) used for the "move behind" step. Vanilla walking is ~0.21. */
    public double walkSpeed = 0.2;
    /** Put moved items back where they were when the sequence ends or is cancelled. */
    public boolean restoreInventory = true;

    public static AutoCartConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("autocart.json");
        AutoCartConfig cfg = new AutoCartConfig();
        try {
            if (Files.exists(path)) {
                AutoCartConfig read = GSON.fromJson(Files.readString(path), AutoCartConfig.class);
                if (read != null) cfg = read;
            }
            Files.writeString(path, GSON.toJson(cfg)); // writes defaults / adds new fields
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read/write {}, using defaults: {}", path, e.toString());
        }
        cfg.sanitize();
        return cfg;
    }

    private void sanitize() {
        stepDelayTicks = Math.max(0, stepDelayTicks);
        verifyDelayTicks = Math.max(1, verifyDelayTicks);
        verifyTimeoutTicks = Math.max(5, verifyTimeoutTicks);
        stageTimeoutTicks = Math.max(20, stageTimeoutTicks);
        fireDelayTicks = Math.max(0, fireDelayTicks);
        railDistanceMin = Math.max(1, railDistanceMin);
        railDistanceMax = Math.max(railDistanceMin, railDistanceMax);
        standDistance = Math.min(2.9, Math.max(1.2, standDistance));
        walkSpeed = Math.min(0.215, Math.max(0.05, walkSpeed));
    }
}
