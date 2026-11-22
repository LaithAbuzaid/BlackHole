package io.github.laithabuzaid.blackhole.util;

import io.github.laithabuzaid.blackhole.core.SimulationState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Zero-dependency user preference persistence backed by {@link Properties} in
 * {@code ~/.config/blackhole/settings.properties} (Linux) or
 * {@code %APPDATA%/BlackHole/settings.properties} (Windows).
 */
public final class SettingsStore {

    private SettingsStore() {
    }

    public static Path defaultSettingsPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "BlackHole", "settings.properties");
            }
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "blackhole", "settings.properties");
        }
        return Path.of(System.getProperty("user.home", "."), ".config", "blackhole", "settings.properties");
    }

    public static void load(SimulationState state) {
        load(defaultSettingsPath(), state);
    }

    public static void load(Path path, SimulationState state) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(path)) {
            props.load(is);
            state.vsync = parseBool(props, "vsync", state.vsync);
            state.audioEnabled = parseBool(props, "audioEnabled", state.audioEnabled);
            state.audioVolume = parseFloat(props, "audioVolume", state.audioVolume,
                    SimulationState.MIN_VOLUME, SimulationState.MAX_VOLUME);
            state.adaptiveAa4x = parseBool(props, "adaptiveAa4x", state.adaptiveAa4x);
            state.anamorphicEnabled = parseBool(props, "anamorphicEnabled", state.anamorphicEnabled);
            state.anamorphicStrength = parseFloat(props, "anamorphicStrength", state.anamorphicStrength,
                    SimulationState.MIN_ANAMORPHIC, SimulationState.MAX_ANAMORPHIC);
            state.bloomStrength = parseFloat(props, "bloomStrength", state.bloomStrength,
                    SimulationState.MIN_BLOOM, SimulationState.MAX_BLOOM);
            state.exposure = parseFloat(props, "exposure", state.exposure,
                    SimulationState.MIN_EXPOSURE, SimulationState.MAX_EXPOSURE);
            state.showReticle = parseBool(props, "showReticle", state.showReticle);
            state.tdeHydroEnabled = parseBool(props, "tdeHydroEnabled", state.tdeHydroEnabled);
            int savedInteg = parseInt(props, "integratorMode", state.integratorMode);
            state.integratorMode = (savedInteg == SimulationState.INTEGRATOR_DOPRI54)
                    ? SimulationState.INTEGRATOR_DOPRI54 : SimulationState.INTEGRATOR_RK4;
            int savedHud = parseInt(props, "hudMode", state.hudMode);
            state.setHudMode(savedHud);
        } catch (IOException ignored) {
            // Gracefully ignore corrupt or unreadable settings file
        }
    }

    public static void save(SimulationState state) {
        save(defaultSettingsPath(), state);
    }

    public static void save(Path path, SimulationState state) {
        Properties props = new Properties();
        props.setProperty("vsync", Boolean.toString(state.vsync));
        props.setProperty("audioEnabled", Boolean.toString(state.audioEnabled));
        props.setProperty("audioVolume", Float.toString(state.audioVolume));
        props.setProperty("adaptiveAa4x", Boolean.toString(state.adaptiveAa4x));
        props.setProperty("anamorphicEnabled", Boolean.toString(state.anamorphicEnabled));
        props.setProperty("anamorphicStrength", Float.toString(state.anamorphicStrength));
        props.setProperty("bloomStrength", Float.toString(state.bloomStrength));
        props.setProperty("exposure", Float.toString(state.exposure));
        props.setProperty("showReticle", Boolean.toString(state.showReticle));
        props.setProperty("tdeHydroEnabled", Boolean.toString(state.tdeHydroEnabled));
        props.setProperty("integratorMode", Integer.toString(state.integratorMode));
        props.setProperty("hudMode", Integer.toString(state.hudMode));

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream os = Files.newOutputStream(path)) {
                props.store(os, "BlackHole Observatory Engine User Preferences");
            }
        } catch (IOException ignored) {
            // Non-fatal if filesystem is read-only
        }
    }

    private static boolean parseBool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int parseInt(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float parseFloat(Properties p, String key, float def, float min, float max) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            float parsed = Float.parseFloat(v.trim());
            if (!Float.isFinite(parsed)) return def;
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
