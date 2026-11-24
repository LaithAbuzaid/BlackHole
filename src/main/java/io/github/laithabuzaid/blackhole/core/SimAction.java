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

    // Tab 5 & Visualization Layer Controls
    public static final int ACT_TAB_SCI_VIS           = 70;
    public static final int ACT_RESET_VIS             = 71;
    public static final int ACT_BIN_MODE_CYCLE        = 72;
    public static final int ACT_VIS_BIN_TRAJ          = 73;
    public static final int ACT_VIS_BIN_HORIZONS      = 74;
    public static final int ACT_VIS_BIN_MODES         = 75;
    public static final int ACT_VIS_BIN_DETECTOR      = 76;
    public static final int ACT_VIS_BIN_ENERGY        = 77;
    public static final int ACT_VIS_BIN_PROP          = 78;
    public static final int ACT_KERR_DISK_VIS_CYCLE   = 79;
    public static final int ACT_EHT_MODEL_TOGGLE      = 80;
    public static final int ACT_VIS_KERR_GEOM         = 81;
    public static final int ACT_VIS_KERR_DRAG         = 82;
    public static final int ACT_VIS_KERR_GEO          = 83;
    public static final int ACT_VIS_EHT_GLYPHS        = 84;
    public static final int ACT_TDE_COLOR_CYCLE       = 85;
    public static final int ACT_TDE_HYDRO_VIS_CYCLE   = 86;
    public static final int ACT_TDE_SLICE_CYCLE       = 87;
    public static final int ACT_VIS_TDE_OVERLAY       = 88;
    public static final int ACT_VIS_WH_EMBED          = 89;
    public static final int ACT_VIS_PLUNGE_DIAG       = 90;
}
