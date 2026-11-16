# BlackHole

A real-time interactive visualization of a relativistic Kerr black hole, built with **Java, LWJGL, OpenGL 3.3**, and Maven.

The application renders a GPU-accelerated black hole scene featuring gravitational lensing, an accretion disk, relativistic Doppler effects, HDR rendering, bloom, adaptive anti-aliasing, interactive camera controls, and live CPU/GPU telemetry.

## Features

- Full-screen, geodesic-style black hole ray tracing
- Kerr spin and gravitational lensing controls
- Procedural accretion disk with 3D volumetric noise
- Relativistic Doppler effects
- HDR rendering with a five-stage bloom pyramid
- Adaptive anti-aliasing
- Interactive camera orbit, zoom, roll, and presets
- Real-time performance telemetry:
  - FPS and frame time
  - CPU and GPU utilization
  - VRAM usage
  - JVM heap usage
- Optional grid and ray-order visualizers
- Auto-orbit, pause, and VSync controls

## Requirements

- Java Development Kit (JDK) 17 or newer
- Maven 3.8 or newer
- OpenGL 3.3-compatible graphics driver
- Desktop environment with access to a graphics display

The current Maven configuration includes Linux-native LWJGL and JavaFX artifacts. Running on other operating systems may require changing the native classifiers in `pom.xml`.

## Build

```bash
mvn clean package
```

The packaged application JAR is generated in the `target/` directory.

## Run

Using the JavaFX Maven plugin:

```bash
mvn clean javafx:run
```

Alternatively, launch the project from VS Code using the **Run (mvn javafx:run)** task.

The project also includes a **Launch BlackHole App** Java launch configuration.

If your system requires explicit native access configuration, run:

```bash
mvn clean javafx:run \
  -Djavafx.run.jvmArgs="--enable-native-access=ALL-UNNAMED"
```

## Controls

The **SPACETIME & OPTICS CONTROLS** panel provides access to the simulation parameters.

### Keyboard

| Key | Action |
|---|---|
| `1` | Edge view |
| `2` | Pole view |
| `3` | Ring view |
| `R` | Reset camera |

### Mouse

- **Left-click and drag:** Orbit the camera
- Additional parameters and controls are available in the on-screen panel.

## Project Structure

```text
src/
└── main/
    └── java/
        ├── module-info.java
        └── com/
            └── mycompany/
                └── mavenproject1/
                    ├── App.java
                    └── SystemInfo.java

pom.xml
```

## Notes

- This project is intended as a real-time graphics experiment.
- Rendering can be GPU-intensive at the default 1440 × 860 resolution.
- Performance and available hardware telemetry depend on the graphics driver and operating system.
- The visualization is an approximation and should not be interpreted as a scientific simulation without further validation.

