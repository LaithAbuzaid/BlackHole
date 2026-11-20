# BlackHole — Relativistic Kerr-Schild Black Hole Engine

A real-time interactive general-relativistic Kerr black hole simulation built with **Java 17+, LWJGL 3.3.4, OpenGL 3.3 Core**, and Maven.

The engine integrates exact Cartesian Kerr-Schild null geodesics ($G = c = M = 1$, $r_s = 2M = 2.0$, spin $a_* \in [0.0, 0.998]$) on the GPU in real time, featuring volumetric accretion plasma, absorbing outer dust lanes, plunging-region streams, collimated bipolar relativistic synchrotron jets, a mipmapped HDR skybox with screen-space ray differentials, a 5-level Karis bloom pyramid, and a zero-allocation glassmorphic hardware telemetry & controls console.

## Key Features

- **Exact Cartesian Kerr-Schild Physics (`blackhole.frag`)**:
  - Exact spheroidal Kerr radius, exact analytic metric gradients ($\nabla r, \nabla H, \nabla(\mathbf{l}\cdot\mathbf{p})$), and Hamiltonian RK4 geodesic evolution conserving $\mathcal{H} = \frac{1}{2} g^{\mu\nu} p_\mu p_\nu = 0$.
  - Exact outer horizon $r_+ = M + \sqrt{M^2 - a^2}$ and Bardeen-Press-Teukolsky prograde ISCO $r_{\text{ISCO}}(a)$.
  - Dual observer frames (`[F]`): **Static Shell Observer** (local orthonormal tetrad aberration) and **Free-Fall Observer** (horizon-penetrating Doran/Kerr-Schild frame supporting travel inside $r_+$ down to $r \approx 0.25\,M$).
- **Volumetric Accretion Disk, Plunge Streams & Relativistic Jets**:
  - Startup GPU-baked `128³` seamless 4-octave periodic 3D turbulence volume (`GL_R16F`).
  - Newton-Raphson cubic Hermite equatorial crossings ($n=0, 1, \ge 2$ lensed photon rings) combined with front-to-back radiative transfer through a flared 3D slab, cooler outer absorbing dust lanes, and plunging-region filaments.
  - Exact covariant relativistic Doppler shift & gravitational redshift $g$ driving Planckian blackbody temperature $T_{\text{obs}} = g \cdot T(r)$ and Liouville bolometric boost $I_{\text{obs}} \propto g^4$.
  - Collimated bipolar parabolic synchrotron jets (`[J]`) along $\pm Y$ with helical magnetic filaments and $\beta_{\text{jet}} \approx 0.78c$ Doppler beaming.
- **Skybox, Ray Differentials & HDR Post-Processing**:
  - Startup GPU-baked `1024×1024`×6 mipmapped HDR cubemap (`GL_RGBA16F`, `GL_LINEAR_MIPMAP_LINEAR`) with Milky Way dust rift, H-alpha nebulae, Harvard O–M spectral stars, and spiral galaxies.
  - Screen-space ray differentials (`dFdx`/`dFdy` + `textureGrad`) and edge-aware adaptive `4×` R2 stratified AA (`[M]`).
  - 5-level Karis-averaged bloom pyramid, subtle lens dispersion, vignette, and ACES filmic tone mapping.
- **Glassmorphic Hardware Profiler & Controls Console (`[H]`)**:
  - Zero heap allocations inside the main render loop.
  - Asynchronous `GL_TIME_ELAPSED` GPU pass timers (**Raytrace**, **Bloom**, **UI**), live `GL_NVX_gpu_memory_info` + `nvidia-smi` GPU utilization / VRAM / temperature / clock, JVM & system CPU % (`OperatingSystemMXBean`), JVM heap, and a 120-frame GPU vs. CPU oscilloscope graph with 16.6ms (60 FPS) and 6.9ms (144 FPS) reference markers.

## Requirements

- Java Development Kit (JDK) 17 or newer
- Maven 3.8 or newer
- OpenGL 3.3-compatible graphics driver

## Build & Run

Build the standalone shaded JAR (with bundled GLSL shaders and LWJGL Linux natives):

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
- **Scroll wheel**: Logarithmic zoom (down to $r = 0.25\,M$ in Free-Fall mode)

### Keyboard Shortcuts

| Key | Action |
|---|---|
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
| `1` / `2` / `3` / `4` | Camera Presets: Gargantua Edge / Polar Vortex / Photon Ring / Horizon Dive |
| `R` | Reset All Parameters & Camera |
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
│       ├── core/
│       │   ├── Application.java
│       │   ├── CameraController.java
│       │   └── SimulationState.java
│       ├── graphics/
│       │   ├── BlackHoleRenderer.java
│       │   ├── FramebufferSet.java
│       │   ├── GpuTimerQueries.java
│       │   └── ShaderProgram.java
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
        ├── blackhole.frag
        ├── bloom_downsample.frag
        ├── bloom_upsample.frag
        ├── composite.frag
        ├── ui.vert
        └── ui.frag
```
