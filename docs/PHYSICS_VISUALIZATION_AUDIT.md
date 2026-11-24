# BlackHole V3 — Complete Physics-to-Rendering Audit & Gap Analysis

This document is the Phase 1 repository-wide physics-to-visualization audit for **BlackHole V3** (`3.0.0`). Every physical quantity computed across the Java solvers, simulation state, telemetry buffers, and GLSL shaders is traced from its exact calculation site to its GPU/HUD/F3 visual output.

## Classification Taxonomy

Throughout this audit, every physical quantity is classified into one of five categories:

1. **(1) Calculated and accurately visualized** — computed by the solver and faithfully represented visually with correct geometry/units/evolution.
2. **(2) Calculated, numeric telemetry only** — computed and displayed only as numbers in the HUD/F3 banner without spatial or graphical visualization.
3. **(3) Calculated, not visualized at all** — computed in Java or GPU SSBOs/shaders, never rendered or exposed visually.
4. **(4) Simplified or inaccurate visualization** — displayed via a coordinate bug, arbitrary floor/clamp, hardcoded mode ($m=2$ only), missing Kerr-Schild transform, or missing Kerr-mode-only guard.
5. **(5) Not calculated by current physical model** — must **never** be fabricated or presented as simulated physics (e.g., spin precession, full GRMHD, 3+1 NR binary spacetime, Faraday rotation, wormhole gravitational redshift).

---

## 1. Binary Black Hole Merger Subsystem

**Files audited:**
- Physics & Parameters: `math/BinaryParameters.java`, `math/EobBinaryDynamics.java`, `math/BinaryRemnantModel.java`, `math/GravitationalWaveDetector.java`, `math/BinarySpacetimeMetric.java`, `core/BinaryMergerPhysics.java`, `util/GwReferenceData.java`
- GPU Shader & Upload: `resources/shaders/simulation/binary_merger.frag`, `graphics/BlackHoleRenderer.java` (lines 155–216, 449–531)
- UI & Overlays: `ui/HudOverlay.java` (lines 689–724, 809–835, 1058–1114, 1191–1284, 1857–1883), `ui/ScientificVisualizer.java` (lines 68–79, 258–319)

| Physical quantity | Where calculated | Current visual representation | Missing or inaccurate representation | Required implementation | Category |
|---|---|---|---|---|---|
| Component positions $\mathbf{x}_1, \mathbf{x}_2$, separation $r(t)$, orbital phase $\phi(t)$ | `EobBinaryDynamics.evaluate` / `BinaryMergerPhysics.publishInspiralOutputs` (L445–464) | Two black hole shadows ray-traced in `binary_merger.frag`; `[Q]` corner brackets (`HudOverlay.java:1857`) | **(4)** No orbital trajectory path or historical trails; no separation chord; no merger location or remnant marker; no orbital plane grid or inclination indicator | Record synchronous EOB trail buffer (`trail1/2`, phase-colored), COM, separation vector, merger point, orbital plane axes & inclination arc in `BinaryVisualizer` | (1)/(4) |
| Orbital velocity $\mathbf{v}_1, \mathbf{v}_2$, $dr/dt$, $\Omega(t)$, $\dot{\Omega}(t)$, eccentricity $e_0$ radial oscillation | `EobBinaryDynamics` 4-DOF $(r,\phi,p_{r*},p_\phi)$, `BinaryMergerPhysics` `vel1/2`, `eobEval.drDt`, `orbitalOmegaGeom` | Affects ray-traced 1PN gravitomagnetic shift (`binary_merger.frag:162`) and Doppler `v1Orb/v2Orb` | **(3)** Velocities, $\hat{\mathbf{L}}$ vector, $\Omega$, $d\Omega/dt$, and eccentric apastron/periapsis oscillation are invisible visually | Render 3D $\mathbf{v}_1, \mathbf{v}_2$ arrows, $\hat{\mathbf{L}}$ axis arrow, $e_0$ apastron/periapsis markers, and radial speed/frequency evolution | (3) |
| Component horizons $r_{+,i}$ & aligned spins $\chi_1, \chi_2$ | `BinarySpacetimeMetric.componentHorizonRadius`, `binary_merger.frag:762-764`, `a_i = m_i * chi_i` | Horizon shadows ray-traced in `binary_merger.frag` with spinning KS radius `kerrRadius` | **(3)** No visual horizon boundary contours, mass-dependent scale labels, or spin vector arrows along $\pm\hat{\mathbf{L}}$ | Draw individual oblate Kerr-Schild horizon contours, mass/spin labels, aligned spin arrows $\mathbf{S}_1, \mathbf{S}_2$ (explicitly non-precessing) | (1)/(3) |
| GW multipolar modes $(2,2), (2,1), (3,3), (4,4)$ amplitudes & phases | `EobBinaryDynamics` (L277–308), `BinaryMergerPhysics` (`modeAmp22..44`, `modePhase22..44` L168–169) | **NONE.** Uniforms not even uploaded to GPU! | **(3)/(4)** `binary_merger.frag:707-729` (`evalGwPlaneOverlayOrb`) and `:486-508` (`applyQuadrupoleGwDeflection`) hardcode pure $m=2$ quadrupole only, ignoring `(2,1),(3,3),(4,4)` completely! | Upload `uModeAmp`, `uModePhase`, `uGwModeFilter`; render individual mode waves, reconstructed total, mode-power bars, and time-freq chirp spectrogram | (3)/(4) |
| Spatial GW wave amplitude floor | `binary_merger.frag:720` | `float amp = max(0.28, uStrainAmp) * ...` | **(4)** Arbitrary `0.28` floor keeps GW ripples glowing even when physical strain is tiny or decayed! | Remove `max(0.28, ...)` floor; drive spatial wave strictly from actual mode amplitudes with explicit visual scale factor label | (4) |
| Polarizations $h_+(t), h_\times(t)$ & inclination $\iota_{\text{obs}}$ | `GravitationalWaveDetector.synthesize` (`geomHPlus/Cross`, `physHPlus/Cross` L117–168) | Left oscilloscope plots **only** normalized `strainPlus`; `GW DET` tab prints numbers only | **(2)** $h_\times$ is never plotted; inclination dependence (circular vs linear polarization) has no visual explanation | Separate, correctly labeled plots for $h_+(t)$, $h_\times(t)$, $h_{\text{det}}(t)$, plus polarization ellipse vs $\iota_{\text{obs}}$ | (2) |
| Detector response $(F_+, F_\times)$, sky $(\theta,\phi,\psi)$, SI strain $h_{\text{det}}(t)$ | `BinaryParameters.DetectorPreset`, `GravitationalWaveDetector.antennaFPlus/Cross` (L76–94, L164) | Printed as numeric strings in `GW DET` tab (`HudOverlay.java:1229-1262`) | **(2)** Detector antenna pattern lobes, orientation arms, source direction, and SI strain vs normalized strain difference are not visually shown | Draw polar antenna diagram $\sqrt{F_+^2 + F_\times^2}$, detector arms, source direction, and SI strain plot with `e-21` axis + optional `GwReferenceData` | (2) |
| Energy & angular momentum ($E_{\text{bind}}, E_{\text{rad}}, L_{\text{orb}}, J_{\text{rad}}, \Delta E_{\text{bal}}$) | `BinaryMergerPhysics.stepGeomChunk` (L348–350, L562–572) | Single snapshot numbers in left HUD / `[F3]` status bar | **(2)** Initial vs evolving vs final budget and energy-balance residual over time are not plotted | Plot time-evolution charts of $E_{\text{bind}}(t), E_{\text{rad}}(t), L_{\text{orb}}(t), J_{\text{rad}}(t)$ and balance residual | (2) |
| Remnant mass $M_f$, spin $a_f$, QNM $\omega_{lm0}, \tau_{lm0}$, two-to-one horizon transition | `BinaryRemnantModel.compute`, `BinaryMergerPhysics.beginMergerAttachment` (L402–560) | Remnant KS metric in `binary_merger.frag:223-290` + procedural expanding flash ring (`L689-704`) | **(4)** Procedural expanding `flashR` ring resembles a shockwave/explosion; QNM mode decay rates & frequencies not plotted | Replace expanding flash ring with gentle remnant settling; visualize 2-to-1 horizon transition, remnant spin vector, and per-mode QNM decay | (4)/(2) |
| Binary circumbinary disk, mini-disks, tidal bridge | `binary_merger.frag:542-705` (`evalBinaryPlasmaOrb`) | Volumetric ray-traced plasma + Doppler + Planck LUT | **(4)** Presented in HUD without explicit disclosure that it is a procedural visualization model (not GRMHD) | Label `[V] PROCEDURAL PLASMA MODEL (NON-GRMHD)` cleanly; add optional Temp/Doppler/Velocity diagnostic coloring (`uDiskVisMode`) | (1)/(4) |
| Spin precession, full 3+1 NR binary metric, GRMHD | Not modeled | None | Must not be fabricated | Explicitly disclose aligned-spin EOB & analytical effective metric boundaries | (5) |

---

## 2. Kerr Black Hole & Horizon Plunge Subsystem

**Files audited:**
- Math & Physics: `math/KerrMetric.java`, `math/PlanckBlackbody.java`, `core/CameraController.java` (L297–465)
- GPU Shader & Upload: `resources/shaders/simulation/kerr_blackhole.frag`, `graphics/BlackHoleRenderer.java` (L89–136, L558–610)
- Overlays: `ui/ScientificVisualizer.java` (L81–197), `ui/HudOverlay.java` (L543–614, L756–785, L1141–1187, L1884–1905)

| Physical quantity | Where calculated | Current visual representation | Missing or inaccurate representation | Required implementation | Category |
|---|---|---|---|---|---|
| Oblate Kerr-Schild surfaces: $r_+$, $r_-$, $r_{\text{ergo}}(\theta)$, $r_{\text{ph}\pm}$, $r_{\text{ISCO}\pm}$ | `KerrMetric` (L27–82, L174–183) | `ScientificVisualizer.java:128-134` draws flat equatorial circles at radius `rPlus`, `rPh`, `2.0f` | **(4) Coordinate Bug!** `ScientificVisualizer` uses Cartesian `r * cos(phi)` instead of Kerr-Schild `sqrt(r^2 + a^2) * cos(phi)`! Missing $r_-$, oblate meridional ergosphere pinch, $r_{\text{ph}-}$, retrograde ISCO $r_{\text{ISCO}-}$, and spin axis $\mathbf{J}$ | Fix equatorial projection to `sqrt(r^2 + a^2)`; render full 3D oblate meridians for $r_+$, $r_-$, $r_{\text{ergo}}(\theta)$, prograde/retrograde $r_{\text{ph}\pm}$ and $r_{\text{ISCO}\pm}$, equatorial ticks, and spin axis $\mathbf{J}=(0,-a,0)$ | (4) |
| Bardeen critical shadow curve $(\alpha(r_p), \beta(r_p))$ | `KerrMetric.bardeenShadowPoint` (L92–123), `ScientificVisualizer.updateBardeenCache` (L335–401) | Projected cyan D-curve in `[F3]` mode | Accurately computed and projected with finite-distance static-shell factor | Preserve exact Bardeen curve inside `KerrVisualizer` | (1) |
| Frame-dragging $\omega_{\text{ZAMO}}(r,\theta,a)$ | `KerrMetric.zamoOmega` (L416–424); `kerr_blackhole.frag` geodesic integration | Indirectly skews shadow & photon ring | **(3)** No visual vector field or streamline showing $\omega_{\text{ZAMO}}$ direction, radial falloff, spin dependence, or prograde vs retrograde asymmetry | Sample `KerrMetric.zamoOmega` on equatorial & off-equatorial rings; draw tangential frame-dragging velocity arrows + prograde/retrograde ISCO speed comparison | (3) |
| Null geodesic rays (captured, critical multi-orbit, deflected) | `KerrMetric.nullGeodesicOde` + `DormandPrince54Integrator` | 4 equatorial rays in `ScientificVisualizer.java:403-454` | Limited to 4 rays without clear multi-orbit photon-ring or off-equatorial inclination examples or labels | Upgrade to 7 DOPRI5(4) rays (captured, multi-turn photon-orbit, prograde/retrograde deflected, inclined) with direction arrows & impact labels | (1)/(4) |
| Accretion disk $T(r)$, Doppler $g$, beaming $g^3$, orbital velocity $\mathbf{v}$, ISCO cutoff | `kerr_blackhole.frag:343-365`, `:699-790` | Rendered only as Blackbody optical intensity (or Luminet order `[I]`) | **(3)** User cannot inspect raw temperature $T(r)$, Doppler shift $g$, or orbital speed $v/c$ fields directly, nor is non-GRMHD prescription explicitly tagged | Add `uDiskVisMode` (`0=Blackbody, 1=Temp(K), 2=Doppler g, 3=Velocity v/c`) + calibrated colorbar & `[A/V] Novikov-Thorne prescription` label | (1)/(3) |
| EHT 230 GHz polarization & B-field (`MAD` vs `SANE`, Faraday omission) | `kerr_blackhole.frag:734-764` | Blended helical shimmer in shader; **Faraday disclosure only in GLSL comment!** | **(3)/(4)** No visible EVPA vector ticks or B-field glyphs across the image; no MAD vs SANE toggle; Faraday omission invisible to user | Add `uEhtModel` (MAD vs SANE), render 3D-projected EVPA polarization ticks ($\propto \hat{\mathbf{k}}\times\mathbf{B}$) & B-field arrows, and display explicit `"Faraday rotation not modeled"` disclosure | (3)/(4) |
| Doran horizon plunge ($\tau, t_\infty, v/c, 2GM/r^3$, Cauchy flash, spaghettification) | `CameraController.updatePlunge` (L352–440), `kerr_blackhole.frag:414-449`, `:878-894` | Full-screen ray-traced view + Cauchy/singularity effect + numeric top banner (`HudOverlay.java:543-614`) | **(2)** No trajectory curve $r(\tau)$, proper vs diverging $t_\infty$ plot, redshift readout, or tidal stretch/squeeze schematic | Record `PlungeHistory` in `CameraController`; render $r(\tau)$, $\tau$ vs $t_\infty$, $\beta_{\text{in}}$, redshift, tidal ellipsoid schematic, and Doran tetrad frame badge | (2) |

---

## 3. Tidal Disruption Event (TDE) Subsystem

**Files audited:**
- CPU Setup & Dispatch: `core/StellarArchetype.java`, `math/TdeInitialConditions.java`, `graphics/TidalDisruptionSystem.java`
- GPU Compute & Render: `resources/shaders/simulation/tidal_hydro_splat.comp`, `resources/shaders/simulation/tidal_disruption.comp`, `resources/shaders/simulation/tidal_disruption.vert`, `resources/shaders/simulation/tidal_disruption.frag`
- HUD/F3: `ui/HudOverlay.java` (L786–799, L1286–1368), `ui/ScientificVisualizer.java` (L323–327)

| Physical quantity | Where calculated | Current visual representation | Missing or inaccurate representation | Required implementation | Category |
|---|---|---|---|---|---|
| Stellar orbit, tidal radius $r_t$, periapsis $r_p = r_t/\beta$, $\beta$, deformation | `StellarArchetype`, `TdeInitialConditions.generateStar` | Particles spawn & orbit, numbers `r_t`, `r_p`, `beta` printed in `TDE LAB` tab | **(3)** No visual $r_t$ or $r_p$ rings, no approach trajectory curve, no inclination/tidal-stretching annotation | Extract shared `TdeInitialConditions.computeLaunchOrbit`; draw $r_t$ & $r_p$ rings + approach trajectory in launch plane | (3) |
| Debris classification: bound ($\epsilon<0$), unbound ($\epsilon\ge 0$), infalling, shearing, swallowed | `tidal_disruption.comp:280-292` (`CounterBuffer` SSBO) | Read back to numeric HUD counts (`ACT, BND, TAIL, SWL`) only; particles all shaded strictly by temperature | **(2)/(3)** User cannot visually distinguish bound fallback tail vs unbound ejecta tail vs hot shearing stream! | Add `uColorMode` (`Thermal / Bound-Unbound / Velocity / Temp`) in `tidal_disruption.vert` + consistent legend in `TdeVisualizer` | (2)/(3) |
| $64^3$ Eulerian Hydrodynamic Grid (`massFixed`, `momXYZFixed`), polytropic pressure $P = K\rho^{5/3}$, bulk velocity, shock dissipation | `tidal_hydro_splat.comp`, `tidal_disruption.comp:93-155`, `:240-260` | **COMPLETELY INVISIBLE** (used inside compute shader only!) | **(3)** The entire $64^3$ Eulerian grid `HydroGridBuffer` has zero visual representation | Create `tidal_hydro_vis.frag` reading `HydroGridBuffer` directly on GPU: equatorial/meridional slice modes (Density, Pressure, Bulk Velocity chevrons, Shock convergence $-\nabla\cdot\mathbf{v}$) + 3D density volume raymarch + grid domain wireframe & legend | (3) |
| Energy $\langle E \rangle$, angular momentum $\langle L_y \rangle$, $T_{\text{peak}}$, capture rate, substeps, timeline | `TidalDisruptionSystem.pollAsyncCounters`, `SimulationEngine.java:295-304` | Instantaneous numbers in `TDE LAB` / `[F3]` status bar | **(2)** No disruption timeline plots of population changes, energy/angular momentum conservation, peak temp, or capture rate; Paczynski-Wiita approximation not prominently labeled | Record synchronous TDE history buffer in `TidalDisruptionSystem`; render timeline plots + `[A] Paczynski-Wiita + Boris LT + 64^3 Polytropic Hydro (non-GRMHD)` disclosure | (2) |
| Full GRMHD, magnetic fields, nuclear burning, radiative transfer feedback | Not modeled | None | Must not fabricate uncomputed fields | Explicitly disclose non-GRMHD / Paczynski-Wiita approximations | (5) |

---

## 4. Morris–Thorne Wormhole Subsystem

**Files audited:**
- Math: `math/MorrisThorneMetric.java`
- Shader & Upload: `resources/shaders/simulation/morris_thorne_wormhole.frag`, `graphics/BlackHoleRenderer.java` (L138–152, L532–557)
- Overlays: `ui/HudOverlay.java` (L725–754), `ui/ScientificVisualizer.java` (L68–71, L262–296)

| Physical quantity | Where calculated | Current visual representation | Missing or inaccurate representation | Required implementation | Category |
|---|---|---|---|---|---|
| Throat radius $\rho$, half-length $a$, lensing width $M_w$, radial coord $l$, areal radius $r(l)$, flare-out $r''(l) > 0$, embedding $z(l)$ | `MorrisThorneMetric.radius`, `dRadiusDl`, `d2RadiusDl2`, `morris_thorne_wormhole.frag:56-63` | Only distorted dual-skybox image + numeric `l, rho, a, Mw` in HUD | **(3)** No visual throat rim boundary, no embedding diagram $z(r(l))$, no flare-out visualization, no clear Universe A/B & traversal marker | Add `MorrisThorneMetric.embeddingZ`; render 3D critical throat portal rim ($\sin\theta = \rho / r(l)$) + 2D Flamm/Dneg embedding cross-section showing $\rho, a, M_w$, Universe A/B, and live camera $l$ | (3) |
| Null ray paths, angular deflection $\Delta\phi$, entry/exit side, null error $|v_l^2 + H^2/r^2 - 1|$ | `MorrisThorneMetric.nullGeodesicOde`, `morris_thorne_wormhole.frag:82-154`, `:202-207` | `[F3]` modes 1 & 2 render per-pixel null error / step heatmap, **without** a wormhole-specific colorbar scale! | **(4)** Colorbar in `ScientificVisualizer.java:206-243` labels `NULL CONSTRAINT ERROR log10(|H|)` generically; no traced ray trajectories on embedding diagram | Integrate 6 DOPRI5(4) null rays ($H < \rho$ through-throat vs $H > \rho$ reflected) onto embedding diagram with $\Delta\phi$, entry/exit, and null error table; fix Wormhole colorbar label | (1)/(4) |
| Gravitational redshift ($g_{tt} = -1$) | `MorrisThorneMetric` ultrastatic metric | None (correctly zero) | Must never fabricate redshift | Explicitly display `[E] ULTRASTATIC g_tt = -1: ZERO GRAVITATIONAL REDSHIFT • EXOTIC NEC VIOLATION` | (5) |

---

## 5. Scientific Visualizer (`[F3]`) & HUD (`HudOverlay`) Cross-Mode Audit

- **`ScientificVisualizer.java:68-71`**: Currently suppresses analytical overlays in Binary (`!state.binaryMode`) and Wormhole (`!state.wormholeMode`) modes and provides no TDE or Plunge overlays.
- **`HudOverlay.java:312`**: Reticle (`[Q]`) only draws scale bar + basic M1/M2 brackets in Binary or shadow extent in Kerr, missing TDE $r_t/r_p$ and Wormhole throat markers.
- **`HudOverlay.java:918-949`**: 4-tab right console has no per-overlay layer selection or reset button; users cannot selectively toggle individual scientific layers without entering `[F3]` all-or-nothing.
- **`HudOverlay.java:809-834`**: Binary left oscilloscope renders only normalized visual $h_+(t)$ while printing SI $h_{\text{det}}$ as one number; lacks dedicated multi-panel mode/detector/energy diagnostics.

---

## Summary of Implementation Scope

All gaps identified in Categories 2, 3, and 4 above have been implemented across Stages 1–5 while strictly respecting Category 5 boundaries so every visual element reflects actual computed simulation physics.

---

## Implementation & Verification Summary

- **Binary Black Hole Merger (`BinaryVisualizer.java`, `binary_merger.frag`, `BinaryMergerPhysics.java`):** Implemented synchronized 3D EOB inspiral/plunge/merger trails, component/remnant Kerr-Schild oblate horizons and aligned spin vectors, angular momentum axis $\hat{L}_{\text{orb}}$ and orbital plane indicators, genuine multipolar $(2,2),(2,1),(3,3),(4,4)$ GPU & 2D waveform synthesis (removing the arbitrary amplitude floor and post-merger shockwave ring), chirp spectrogram (`f_220`, `tau_220`), detector antenna pattern + polarization ellipse + separate physical SI `h+`, `hx`, `h_det` traces (plus optional GW150914 GWOSC reference curve), and energy/angular-momentum/residual evolution panels with explicit non-precessing aligned-spin & procedural plasma disclosures.
- **Kerr Black Hole & Doran Plunge (`KerrVisualizer.java`, `kerr_blackhole.frag`, `CameraController.java`):** Fixed Kerr-Schild Cartesian mapping using `KerrMetric.oblateToCartesian` ($\rho_{\text{KS}}=\sqrt{r^2+a^2}\sin\theta$), added oblate $r_+/r_-$ & pinched ergosphere meridians, prograde/retrograde $r_{\text{ph}}$ and ISCO boundaries collapsing identically at $a^*=0$, ZAMO frame-dragging $\omega$ vector field, 7 DOPRI5(4) null geodesics, Novikov–Thorne disk colormaps (`Temp K`, `Doppler g`, `Vel v/c`), EHT EVPA vector glyphs (`MAD`/`SANE` toggle + Faraday rotation omission disclosure), and Doran proper-vs-coordinate time + tidal stretch/compression plunge diagnostics.
- **Tidal Disruption Event (`TdeVisualizer.java`, `tidal_disruption.vert`, `tidal_hydro_vis.frag`, `TidalDisruptionSystem.java`):** Visualized tilted $r_t$ & $r_p$ rings, cached DOPRI5(4) Paczyński–Wiita launch trajectories (`TdeInitialConditions.computeLaunchOrbit`), Bound/Unbound/Infalling/Hot-Shearing particle color modes, calibrated XZ/XY slices & 44-step 3D volume raymarch over the previously unvisualized $64^3$ `HydroGridBuffer` SSBO, and live disruption fraction & energy timeline plots with non-GRMHD disclosure.
- **Morris–Thorne Wormhole (`WormholeVisualizer.java`, `MorrisThorneMetric.java`):** Added critical throat portal rim ($\sin\theta_{\text{crit}} = \rho/r(l_{\text{cam}})$), Universe A/B orientation indicator, Flamm/Dneg isometric embedding silhouette $z(r(l))$ (`|l|<=a` throat cylinder & flare-out), 6 DOPRI5(4) rays showing through-throat vs reflection trajectories & `max|nullErr|`, and ultrastatic $g_{tt}=-1$ zero-redshift / NEC violation disclosure.
- **HUD & Tooling (`HudOverlay.java`, `ScientificVisualizer.java`, `SimulationEngine.java`, `BenchmarkAndShowcaseRunner.java`):** Upgraded to a 5-tab Observatory Console (`SCENE | PHYS | TDE/GW DET | OPTICS | LAYERS`), mode-specific `[F3]` overlays/colorbars/banners with provenance badges `[E]/[A]/[F]/[V]`, preserved 8 baseline images in `docs/media/before/`, generated 10 `1920x1080` verification images in `docs/media/after/` (`--capture-verification`), refreshed all 8 `docs/media/*.png` captures (`--capture-showcase`), verified zero GL errors under `--benchmark`, and validated **53/53** headless JUnit 5 tests (`mvn -B test`).
