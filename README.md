<div align="center">

# BlackHole

**Real-Time General-Relativistic Kerr-Schild Black Hole, Binary Merger, Morris-Thorne Wormhole & Compute TDE Engine**

[![Java 21+](https://img.shields.io/badge/Java-21%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![OpenGL 4.3](https://img.shields.io/badge/OpenGL-4.3%20Core-5586A4?style=flat-square&logo=opengl&logoColor=white)](https://www.khronos.org/opengl/)
[![LWJGL 3.3.4](https://img.shields.io/badge/LWJGL-3.3.4-2C3E50?style=flat-square)](https://www.lwjgl.org/)
[![Build Status](https://img.shields.io/github/actions/workflow/status/LaithAbuzaid/BlackHole/build.yml?branch=main&style=flat-square&label=CI%20Build)](https://github.com/LaithAbuzaid/BlackHole/actions)
[![Release](https://img.shields.io/github/v/release/LaithAbuzaid/BlackHole?style=flat-square&color=F5A623)](https://github.com/LaithAbuzaid/BlackHole/releases/latest)
[![License](https://img.shields.io/badge/License-MIT-2EA44F?style=flat-square)](LICENSE)

*Exact Cartesian Kerr-Schild RK4 geodesics · Binary Black Hole Merger & LIGO GW Chirp · Interstellar Morris-Thorne wormhole · Volumetric Lensed Compute TDE · Relativistic HDR TAA · JetBrains Mono MTSDF HUD*

</div>

---

<!--
  SHOWCASE VIDEO PLACEHOLDER:
  Drag and drop a 1080p .mp4 clip into a GitHub Issue or PR editor to generate a GitHub-hosted
  https://github.com/user-attachments/assets/... video URL, then paste that URL on its own line
  directly below this comment to render an auto-playing inline showcase player above the hero still.
-->
<p align="center">
  <img src="docs/media/hero_kerr_edge.png" alt="Gargantua-style edge-on Kerr black hole (a* = 0.96) with relativistic Doppler beaming and anamorphic flare" width="100%">
  <br>
  <sub><b>Kerr-Schild Spacetime (<i>a<sub>*</sub></i> = 0.96)</b> — Asymmetric frame-dragged shadow, Doppler-beamed accretion disk, lensed photon ring (<i>n</i> &ge; 1), and horizontal anamorphic glare rendered in real time at 1080p.</sub>
</p>

## Visual Showcase

<p align="center">
  <img src="docs/media/binary_merger.png" alt="Interactive Binary Black Hole Merger showing mutual eyebrow lensing, shock-heated tidal gas bridge, and propagating quadrupole gravitational waves" width="100%">
  <br>
  <sub><b>Binary Black Hole Merger &amp; Gravitational Waves (`[X]`)</b> — Superposed boosted Kerr–Schild geodesics, mutual secondary "eyebrow" shadow lensing, companion mini-disks, shock-heated tidal gas bridge, and propagating quadrupole gravitational waves (<i>h<sub>+</sub></i>, <i>h<sub>&times;</sub></i>) with live LIGO chirp audio.</sub>
</p>

<table>
  <tr>
    <td align="center" width="50%">
      <img src="docs/media/tde_shredding.png" alt="65,536-particle Tidal Disruption Event wrapping around the black hole shadow" width="100%">
      <br>
      <sub><b>Interactive TDE Lab (`[E]` / `[Shift+E]`)</b> — 4 stellar archetypes, penetration factor &beta; = <i>r<sub>t</sub>/r<sub>p</sub></i>, bound circularizing torus &amp; unbound escape tail.</sub>
    </td>
    <td align="center" width="50%">
      <img src="docs/media/wormhole_throat.png" alt="Morris-Thorne traversable wormhole showing Universe B nebula and lensed ghost arcs" width="100%">
      <br>
      <sub><b>Morris–Thorne Wormhole (`[W]`)</b> — Exact <i>Interstellar</i> Dneg metric linking two GPU-baked 1024²×6 HDR cubemap universes across <i>l</i> = 0.</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="50%">
      <img src="docs/media/eht_polarization.png" alt="Event Horizon Telescope 230 GHz VLBI radio palette with Kerr-dragged polarized magnetic field streamlines" width="100%">
      <br>
      <sub><b>EHT 230 GHz Polarized Magnetic Fields (`[N]` / `[Shift+N]`)</b> — Kerr-dragged synchrotron polarization swirl rendered in authentic 1.3 mm VLBI brightness temperature.</sub>
    </td>
    <td align="center" width="50%">
      <img src="docs/media/hud_telemetry.png" alt="Modular glassmorphic Observatory HUD with tabbed TDE Lab console and timelapse transport dock" width="100%">
      <br>
      <sub><b>Observatory HUD &amp; Timelapse Dock (`[H]`)</b> — 3-state HUD cycle, 4-tab console (<code>SCENE | PHYSICS | TDE LAB | OPTICS</code>), bottom timelapse dock, and live GPU timers.</sub>
    </td>
  </tr>
  <tr>
    <td align="center" colspan="2">
      <img src="docs/media/horizon_plunge.png" alt="Interior Kerr horizon plunge looking back at the blueshifted exterior sky and disk ring" width="50%">
      <br>
      <sub><b>Horizon Plunge (`[P]`)</b> — Doran / Kerr–Schild free-fall interior view (<i>r</i> &lt; <i>r<sub>+</sub></i>) looking back at the blueshifted exterior sky &amp; disk ring.</sub>
    </td>
  </tr>
</table>

---

## First-Impression Experience

- **Cinema First, Console Second:** Launches unobstructed in full-screen Cinema Mode with a **Director's Cut Attract Tour** (`[T]`) sweeping from an edge-on Kerr reveal through an automated 65,536-particle Tidal Disruption pass down to the photon ring.
- **Instant Zero-Jump Handoff:** Clicking, dragging, scrolling, or pressing any camera/mode key immediately hands off control from the tour with zero camera discontinuity.
- **Zero-Dependency 1080p Snapshots (`[F12]`):** Pure-Java PNG encoder (`util/PngWriter.java`, no `java.desktop`/AWT bloat) captures clean HUD-free frames asynchronously to `captures/` with subtle toast confirmations.

---

## Physics & Rendering Architecture

| Subsystem | Implementation Highlights |
|---|---|
| **Kerr–Schild RK4 Hamiltonian** | Exact Cartesian spheroidal radius $r$, analytic metric gradients ($\nabla r, \nabla H, \nabla(\mathbf{l}\cdot\mathbf{p})$), and RK4 null geodesic evolution conserving $\mathcal{H} = \frac{1}{2}g^{\mu\nu}p_\mu p_\nu = 0$. Exact $r_+ = M + \sqrt{M^2 - a^2}$, Bardeen–Press–Teukolsky prograde ISCO, cubic Hermite equatorial crossings ($n = 0, 1, \ge 2$), 128³ baked periodic 3D disk turbulence, and covariant Doppler beaming + blackbody LUT. |
| **Binary Black Hole Merger (`[X]`)** | Deterministic Peters–Mathews inspiral ($\frac{da}{dt} = -\frac{64}{5}\frac{\eta M^3}{a^3}$), coalescence, and $(l=2,m=2)$ QNM ringdown forming a spinning Kerr remnant ($a_f \approx 3.464\eta - 2.9\eta^2$). Ray-traced via superposed Lorentz-boosted Kerr–Schild geodesics (`binary_merger.frag`) with mutual secondary "eyebrow" shadow lensing, $m=2$ circumbinary disk, companion mini-disks, shock-heated tidal bridge, retarded quadrupole $h_+, h_\times$ skybox deflection + `[G]` orbital-plane ripple overlay, MRT `GL_RG16F` TAA motion vectors, live LIGO chirp audio, and a 160-sample HUD strain oscilloscope. |
| **Doran Horizon Plunge** | Horizon-penetrating Kerr–Schild / Doran observer frame (`[P]` / `[F]`) crossing $r_+$ cleanly ($\beta_{\text{in}} = c$ at $r_+$) with independent 360° free-look, proper time ($\tau$) vs. diverging coordinate time ($t_\infty \to \infty$), relativistic blueshift, Kerr inner Cauchy horizon ($r_-$) mass-inflation flash, Schwarzschild anisotropic optical spaghettification, and tidal camera micro-rumble. |
| **Morris–Thorne Wormhole** | Exact *Interstellar* / Dneg embedding radius $r(l)$ with live throat radius $\rho$, throat half-length $a$, and lensing width $M_w$. Smooth bidirectional flight across $l = 0$ between GPU-baked `1024×1024×6` mipmapped HDR cubemaps (**Universe A**: Milky Way; **Universe B**: teal-amber nebula & ringed gas giant) with `textureGrad` ray differentials. |
| **Global Timelapse (`-`/`=`/`0`)** | Unified `0.05×` slow-motion to `25×` timelapse across Kerr, Wormhole, Binary Merger, Horizon Plunge, and TDE without altering camera/UI wall-clock responsiveness. Fixed-substep integration prevents numerical blow-ups at `25×`, while TAA dynamically raises its blend floor above `4×` and OpenAL compresses pitch logarithmically. |
| **Interactive Compute TDE Lab** | `65,536` particles per star (2-slot `131,072` ring-buffered SSBO) across 4 stellar archetypes (**Solar G2V**, **Red Supergiant**, **Blue O-Type**, **White Dwarf**) with live penetration factor $\beta = r_t/r_p$ (`0.6`–`4.0`) and orbital inclination (`-65°` to `+65°`). Computes exact Paczyński–Wiita specific orbital energy ($\epsilon = \frac{1}{2}v^2 - \frac{1}{r - 0.88r_+}$) for bound fallback circularization shocks vs. unbound hyperbolic escape tails, with zero-stall ping-pong `GLsync` atomic counters driving an inner horizon accretion flare and audio surge. |
| **Relativistic Temporal AA (TAA)** | 16-phase Halton(2,3) sub-pixel jittered 1-ray/px geodesics resolved via `taa_resolve.frag` with exact 3D camera parallax reprojection (using linear world hit distance in `hdrFbo.a`), 5-tap Catmull-Rom bicubic history sampling, and Karis-weighted 3×3 YCoCg variance clipping for flicker-free photon rings and 180+ FPS 1080p performance. |
| **Karis Bloom + Anamorphic Glare** | 5-level HDR bloom pyramid (`GL_RGBA16F`) with weight-normalized Jimenez/Karis firefly suppression and quadratic soft-knee extraction, a 2-pass cascaded 13-tap *Interstellar*-style horizontal anamorphic streak at 1/8 resolution (shadow-guarded so black levels inside $r_+$ stay pure), ACES filmic tonemapping, and post-tonemap TPDF dithering. |
| **Modular Glassmorphic HUD** | 3-state `[H]` cycle (`CLEAN` → `COMPACT` → `FULL`), frosted vertical-gradient SDF panels with top specular rims, bottom-center **Timelapse Transport Bar**, 4-tab Observatory Console (`SCENE` \| `PHYSICS` \| `TDE LAB` \| `OPTICS`) with 1-line hover tooltips, and pre-baked 3-channel MSDF + SDF JetBrains Mono Medium typography (zero `java.desktop`/AWT dependency). |
| **Procedural OpenAL Audio** | Zero-asset 44.1 kHz stereo PCM daemon synthesizer (`lwjgl-openal`) generating gravitational-redshift sub-bass drones, Doppler-panned accretion roar, TDE periapsis plasma crackle, and sweeping wormhole throat harmonics (with graceful silent fallback). |
| **Hardware `GL_TIME_ELAPSED` Profiling** | 3-slot asynchronous ring-buffered OpenGL timer queries measuring **Raytrace**, **TDE**, **Bloom/TAA**, and **UI** passes independently alongside `nvidia-smi` / `GL_NVX_gpu_memory_info` telemetry, JVM/system CPU load, and a 120-frame oscilloscope trace — all with **zero per-frame Java heap allocations**. |

---

## Render Pipeline & Repository Layout

```text
  [GPU Bake Once @ Boot]
   ├─ noise_volume.frag        ──▶ 128³ R16F Mipmapped 3D Periodic Turbulence
   ├─ skybox_milkyway.frag     ──▶ 1024²×6 RGBA16F Cubemap (Universe A)
   └─ skybox_nebula.frag       ──▶ 1024²×6 RGBA16F Cubemap (Universe B)

  [Per-Frame Pipeline — Zero Heap Allocations]
   Pass 1A  kerr_blackhole.frag / binary_merger.frag (MRT + Vel) / morris_thorne_wormhole.frag ──▶ hdrFbo
   Pass 1B  tidal_disruption.comp ──▶ SSBO ──▶ Instanced Lensed Volumetric TDE (+hdrFbo)
   Pass 1C  taa_resolve.frag (3D Parallax / MRT Velocity + Catmull-Rom + Karis YCoCg) ──▶ taaFbo
   Pass 2A  5× bloom_downsample.frag (Karis) ──▶ 4× bloom_upsample.frag (Tent)
   Pass 2B  2× anamorphic_blur.frag (1/8 Res 13-Tap Horizontal Streak Cascade)
   Pass 3   tonemap_composite.frag (Bloom + Anamorphic + ACES + TPDF Dither) ──▶ Backbuffer
   Pass 4   hud.{vert,frag} (SDF Glassmorphism + JetBrains Mono MTSDF + LIGO Oscilloscope)
```

```text
src/
├── main/
│   ├── java/
│   │   ├── module-info.java
│   │   └── io/github/laithabuzaid/blackhole/
│   │       ├── Main.java                       # CLI entry (--capture-showcase)
│   │       ├── audio/RelativisticAudioEngine.java
│   │       ├── core/
│   │       │   ├── BinaryMergerPhysics.java    # Peters-Mathews inspiral, merger & QNM ringdown
│   │       │   ├── CameraController.java       # Orbit, Plunge, Binary, Wormhole & Attract Tour
│   │       │   ├── SimulationEngine.java       # GLFW loop, async [F12] & --capture-showcase
│   │       │   ├── SimulationState.java
│   │       │   └── StellarArchetype.java       # 4 TDE star classes, beta periapsis & substep plan
│   │       ├── graphics/
│   │       │   ├── BlackHoleRenderer.java
│   │       │   ├── GpuTimerQueries.java
│   │       │   ├── RenderTargets.java
│   │       │   ├── ShaderProgram.java
│   │       │   └── TidalDisruptionSystem.java
│   │       ├── telemetry/HardwareTelemetry.java
│   │       ├── ui/{HudOverlay.java, UiRenderer.java}
│   │       └── util/PngWriter.java             # Pure-Java zero-dependency PNG encoder
│   └── resources/
│       ├── fonts/jetbrains_mono_mtsdf.bin      # Pre-baked 3-channel MSDF + SDF atlas
│       └── shaders/
│           ├── common/fullscreen.vert
│           ├── bake/{noise_volume.frag, skybox_milkyway.frag, skybox_nebula.frag}
│           ├── simulation/{kerr_blackhole.frag, binary_merger.frag, morris_thorne_wormhole.frag, tidal_disruption.*}
│           ├── post/{taa_resolve.frag, bloom_downsample.frag, bloom_upsample.frag, anamorphic_blur.frag, tonemap_composite.frag}
│           └── ui/{hud.vert, hud.frag}
└── test/java/io/github/laithabuzaid/blackhole/ # Headless JUnit 5 physics, binary, camera & asset tests
```

---

## Download & Run (No Java Required)

Pre-built native releases (~22–24 MB with a minimal bundled `jlink` runtime) are published on the [**GitHub Releases**](https://github.com/LaithAbuzaid/BlackHole/releases/latest) page:

| Platform | Download | How to Run |
|---|---|---|
| **Windows (x64)** | `BlackHole-1.1.0-Windows-x64-Setup.exe` *(or `Portable.zip`)* | Run the installer (adds Start Menu & Desktop shortcuts), or unzip and launch `BlackHole.exe` |
| **Linux (x86_64)** | `BlackHole-1.1.0-Linux-x86_64.AppImage` *(or `.deb`)* | `chmod +x BlackHole-1.1.0-Linux-x86_64.AppImage && ./BlackHole-1.1.0-Linux-x86_64.AppImage` |

> **GPU Requirement:** OpenGL 4.3 Core (Windows 10/11 x64 or Linux x86_64). *(macOS is capped at OpenGL 4.1 by Apple and does not support OpenGL 4.3 Compute Shaders.)*

---

## Build from Source

**Prerequisites:** JDK 21+, Maven 3.8+, and an OpenGL 4.3-capable GPU driver.  
OS-specific LWJGL/OpenAL native classifiers (`natives-linux`, `natives-windows`) activate automatically via Maven profiles.

### Linux
```bash
mvn clean package
java --enable-native-access=ALL-UNNAMED -jar target/blackhole-1.1.0.jar
```

### Windows (PowerShell / CMD)
```powershell
mvn clean package
java --enable-native-access=ALL-UNNAMED -jar target\blackhole-1.1.0.jar
```

### Regenerate All 7 Curated 1080p Showcase PNGs
```bash
java --enable-native-access=ALL-UNNAMED -jar target/blackhole-1.1.0.jar --capture-showcase
```
> Renders all 6 `1920×1080` showcase stills into `docs/media/` using an offscreen FBO and exits cleanly with status `0`.

---

## Controls

| Input | Action |
|---|---|
| **Left-Drag** | Orbit camera (instantly disengages Attract Tour) / drag console sliders |
| **Scroll Wheel** | Logarithmic zoom (Black Hole / Binary) · Fly through throat `l ∈ [-35, +35]` (Wormhole) · Plunge speed (`[P]`) |
| `H` | Cycle HUD Visibility: **Cinema Clean** → **Compact Dock** → **Full Observatory Console** |
| `-` / `=` (or `,` / `.`) / `0` | Global Physics-Safe Timelapse (`0.05×` – `25×`) · `0`: Snap to `1.0×` Realtime |
| `X` / `Shift+X` | Toggle **Binary Black Hole Merger & GW Sandbox** · `Shift+X`: Replay Merger |
| `T` | Toggle **Director's Cut Attract Tour** |
| `P` | Launch / Abort / Replay **Horizon Plunge Dive** (`r+` → Interior) |
| `W` | Switch Spacetime: **Kerr–Schild Black Hole** ↔ **Morris–Thorne Wormhole** |
| `E` / `Shift+E` / `Shift+C` | Launch Star (TDE Lab) · `Shift+E`: Cycle Stellar Archetype · `Shift+C`: Clear Debris |
| `N` / `Shift+N` | Toggle **EHT Polarized Magnetic Fields** · `Shift+N`: Toggle **EHT 230 GHz VLBI Radio Palette** |
| `Q` | Toggle **Astrophysical Scale Ruler** ($r_g$) & Shadow / Binary Tracking Reticle |
| `F11` / `Alt+Enter` | Toggle Borderless Fullscreen Display |
| `F12` / `S` | Capture clean HUD-free PNG screenshot (`~/Pictures/BlackHole/` or `captures/`) |
| `A` | Toggle Procedural OpenAL Soundscape (including live **LIGO Chirp** in Binary mode) |
| `1` `2` `3` `4` | Presets — Black Hole: Edge / Polar / Photon Ring / Dive · Binary: Oblique / Top-Down / Close / Replay · Wormhole: Univ A / Throat / Univ B / Fly-Through |
| `Up` / `Down` | Fly forward / backward through the wormhole throat |
| `L` `D` `J` `B` `K` | Toggle Lensing · Accretion Disk · Bipolar Jets · Doppler Beaming · Kerr Spin |
| `F` `M` `C` `I` `O` | Toggle Free-Fall Frame · Temporal AA (TAA) · Celestial Grid · Image-Order Vis · Auto Orbit |
| `U` `G` `[` `]` `V` | Toggle Camera Roll · GW Strain Overlay (Binary) / Cycle Bloom · Exposure `−`/`+` · V-Sync |
| `Space` / `R` / `Esc` | Pause · Reset Simulation · Quit |
