# BlackHole — Relativistic Kerr-Schild Black Hole & Wormhole Engine

A real-time interactive general-relativistic astrophysics simulation built with **Java 17+, LWJGL 3.3.4, OpenGL 4.3 Core, OpenAL Soft**, and Maven.

The engine combines an exact Cartesian Kerr-Schild null geodesic black hole ray-tracer, an exact Interstellar / Morris-Thorne traversable wormhole integrator with dual HDR universes, an OpenGL 4.3 compute-shader 65,536-particle Tidal Disruption Event (TDE) solver, and a zero-asset procedural OpenAL relativistic soundscape — all running with zero per-frame Java heap allocations.

## Key Features

- **Exact Cartesian Kerr-Schild Black Hole (`blackhole.frag`)**:
  - Exact spheroidal Kerr radius, exact analytic metric gradients ($\nabla r, \nabla H, \nabla(\mathbf{l}\cdot\mathbf{p})$), and Hamiltonian RK4 geodesic evolution conserving $\mathcal{H} = \frac{1}{2} g^{\mu\nu} p_\mu p_\nu = 0$.
  - Exact outer horizon $r_+ = M + \sqrt{M^2 - a^2}$ and Bardeen-Press-Teukolsky prograde ISCO $r_{\text{ISCO}}(a)$.
  - Dual observer frames (`[F]`): **Static Shell Observer** and horizon-penetrating **Free-Fall Observer** (Doran / Kerr-Schild frame supporting travel inside $r_+$ down to $r \approx 0.25\,M$).
  - Volumetric accretion disk with GPU-baked `128³` periodic 3D noise, cubic Hermite equatorial crossings ($n=0, 1, \ge 2$), cooler outer absorbing dust lanes, plunging-region filaments, exact covariant Doppler beaming & blackbody spectrum, and collimated bipolar synchrotron jets (`[J]`).
- **Traversable Wormhole Mode (`[W]` — Exact Morris-Thorne / Interstellar Metric)**:
  - Dual GPU-baked `1024×1024×6` mipmapped HDR cubemaps: **Universe A** (Milky Way & spiral galaxies) and **Universe B** (golden-amber & teal emission nebula, dense star clusters, and a banded ringed gas giant near the throat axis).
  - Exact Dneg / Morris-Thorne RK4 geodesic integrator with live sliders for throat radius $\rho$, throat half-length $a$, and lensing width $M_w$, `textureGrad` ray differentials, and smooth throat flight (`Scroll`, `Up`/`Down`, or `[FLY THROUGH]`) across $l = 0$.
- **Real-Time Tidal Disruption Events (`[E]` — OpenGL 4.3 Compute Shaders)**:
  - `65,536` particles per spawned star (2-slot `131,072` ring-buffered SSBO capacity for simultaneous multi-star shredding) evolved via `tde_update.comp` under relativistic Paczyński-Wiita gravity, Kerr frame-dragging torque, tidal shear heating, and horizon capture.
  - Volumetric additive HDR blackbody + Doppler sprites rendered directly into `hdrFbo` with analytical shadow-cone occlusion feeding into the 5-stage Karis bloom pyramid.
- **Procedural Relativistic Soundscape (`[A]` — `lwjgl-openal`)**:
  - Zero-asset 44.1 kHz stereo PCM daemon synth with gravitational-redshift sub-bass drone, Doppler-panned accretion disk roar, crackling TDE periapsis plasma swell, and sweeping harmonic wormhole throat chords (with graceful silent fallback if no audio device is present).
- **Glassmorphic Hardware Profiler & Controls Console (`[H]`)**:
  - Zero heap allocations in the main render loop, asynchronous 3-slot ring-buffered `GL_TIME_ELAPSED` GPU pass timers (**Raytrace**, **TDE**, **Bloom**, **UI**), live `nvidia-smi` + `GL_NVX_gpu_memory_info` telemetry, JVM/System CPU %, JVM heap, and a 120-frame GPU vs. CPU oscilloscope graph.

## Requirements

- Java Development Kit (JDK) 17 or newer
- Maven 3.8 or newer
- OpenGL 4.3-compatible graphics driver

## Build & Run

Build the standalone shaded JAR (bundling all GLSL/compute shaders and Linux LWJGL/OpenAL natives):

```bash
mvn clean package
java --enable-native-access=ALL-UNNAMED -jar target/mavenproject1-1.0-SNAPSHOT.jar
```

Or run via the JavaFX Maven plugin:

```bash
mvn clean javafx:run
```

## Controls & Diagnostics

### Mouse
- **Left-click & drag**: Orbit camera (or drag UI sliders)
- **Scroll wheel**: Logarithmic zoom in Black Hole mode; fly through the wormhole throat ($l \in [-35, +35]$) in Wormhole mode

### Keyboard Shortcuts

| Key | Action |
|---|---|
| `W` | Toggle Spacetime Mode: **Black Hole** ↔ **Traversable Wormhole** |
| `E` | Spawn & Shred a 65,536-particle star (Tidal Disruption Event) |
| `A` | Toggle Procedural Relativistic Audio (`lwjgl-openal`) |
| `Up` / `Down` | Fly forward / backward through the wormhole throat ($l = 0$) |
| `H` | Toggle HUD & Hardware Profiler |
| `L` | Toggle Gravitational Lensing (Curved vs. Euclidean) |
| `D` | Toggle Accretion Disk |
| `J` | Toggle Bipolar Relativistic Jets |
| `B` | Toggle Relativistic Doppler Beaming |
| `K` | Toggle Kerr Spin ($a_*$ vs. Schwarzschild) |
| `F` | Toggle Free-Fall Horizon-Penetrating Observer |
| `M` | Toggle Edge-Aware Adaptive 4× AA |
| `C` | Toggle Celestial Lat/Lon Coordinate Grid |
| `I` | Toggle Luminet Image-Order False-Color Mode |
| `O` | Toggle Auto Orbit |
| `V` | Toggle V-Sync |
| `Space` | Pause / Resume Simulation |
| `1` / `2` / `3` / `4` | Presets — Black Hole: Edge / Polar / Ring / Horizon Dive; Wormhole: Univ A / Throat / Univ B / Fly Through |
| `R` | Reset Parameters, Clear TDE Stars & Reset Camera |
| `T` | Toggle Cinematic Camera Roll |
| `G` | Cycle Bloom Intensity |
| `[` / `]` | Decrease / Increase HDR Exposure |
| `Esc` | Quit |

## Project Structure

```text
src/main/
├── java/
│   ├── module-info.java
│   └── com/mycompany/mavenproject1/
│       ├── App.java
│       ├── SystemInfo.java
│       ├── audio/
│       │   └── RelativisticAudioEngine.java
│       ├── core/
│       │   ├── Application.java
│       │   ├── CameraController.java
│       │   └── SimulationState.java
│       ├── graphics/
│       │   ├── BlackHoleRenderer.java
│       │   ├── FramebufferSet.java
│       │   ├── GpuTimerQueries.java
│       │   ├── ShaderProgram.java
│       │   └── TdeParticleSystem.java
│       ├── telemetry/
│       │   └── HardwareTelemetry.java
│       └── ui/
│           ├── HudOverlay.java
│           └── UiRenderer.java
└── resources/
    └── shaders/
        ├── fullscreen.vert
        ├── noise3d_bake.frag
        ├── skybox_bake.frag
        ├── skybox_b_bake.frag
        ├── blackhole.frag
        ├── wormhole.frag
        ├── tde_update.comp
        ├── tde_render.vert
        ├── tde_render.frag
        ├── bloom_downsample.frag
        ├── bloom_upsample.frag
        ├── composite.frag
        ├── ui.vert
        └── ui.frag
```
