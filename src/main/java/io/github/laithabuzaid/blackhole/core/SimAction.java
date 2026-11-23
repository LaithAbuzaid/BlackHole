package io.github.laithabuzaid.blackhole.core;

/**
 * Shared simulation action and preset identifiers owned in {@code core} so core input/dispatch
 * logic does not depend on {@code ui.HudOverlay}.
 */
public final class SimAction {

    private SimAction() {
    }

    public static final int ACT_INTEGRATOR_TOGGLE = 64;
    public static final int ACT_SCIENTIFIC_DIAG   = 65;
    public static final int ACT_TDE_HYDRO_TOGGLE  = 66;
    public static final int ACT_GW_REF_OVERLAY    = 67;
    public static final int ACT_LIGO_ALIGNED_SPIN = 68;
    public static final int ACT_BIN_DETECTOR_CYCLE = 69;
}
