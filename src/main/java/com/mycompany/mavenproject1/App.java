package com.mycompany.mavenproject1;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class App {

    public App() {
    }

    private static final int BLOOM_LEVELS = 5;
    private static final int NOISE_VOL_SIZE = 128;
    private static final int HISTORY_LEN = 120;

    private int windowW = 1440;
    private int windowH = 860;
    private boolean framebufferResized = false;

    // Smooth Inertial Camera State
    private double targetDist = 17.5, camDist = 17.5;
    private double targetYaw = 0.26, camYaw = 0.26;
    private double targetPitch = 0.135, camPitch = 0.135;
    private double targetRoll = -0.17, camRoll = -0.17;
    private boolean draggingCamera = false;
    private double anchorX, anchorY;
    private double mouseX, mouseY;
    private boolean mouseDown = false;

    // Interactive Simulation Parameters (Controlled by UI Sliders & Toggles)
    private double simTime = 0.0;
    private boolean showUi = true;
    private boolean controlsExpanded = true;
    private boolean lensingEnabled = true;
    private boolean showDisk = true;
    private boolean dopplerEnabled = true;
    private boolean kerrSpinEnabled = true;
    private boolean adaptiveAa4x = true;
    private boolean showGrid = false;
    private boolean orderVisualizer = false;
    private boolean autoOrbit = false;
    private boolean paused = false;
    private boolean vsync = true;

    private float spinParam = 0.94f;       // Kerr dimensionless spin a* [0.00 .. 0.99]
    private float diskTempScale = 1.00f;   // Blackbody temperature multiplier [0.55 .. 1.65]
    private float diskDensity = 1.00f;     // Accretion optical density [0.25 .. 2.00]
    private float bloomStrength = 0.32f;   // Mipmap bloom intensity [0.00 .. 0.80]
    private float exposure = 1.12f;        // HDR exposure [0.40 .. 2.40]
    private float fovDeg = 22.5f;          // Camera half-FOV degrees [14.0 .. 40.0]

    // Active UI Slider Drag State
    private int activeSliderId = -1;
    private final List<UiHitBox> hitBoxes = new ArrayList<>();

    // OpenGL FBOs, 3D Noise Volume, Skybox Cubemap & Bloom Pyramid
    private int skyboxCubemap;
    private int noise3dTex;
    private int hdrFbo, hdrColorTex;
    private final int[] bloomFbo = new int[BLOOM_LEVELS];
    private final int[] bloomTex = new int[BLOOM_LEVELS];
    private final int[] bloomW = new int[BLOOM_LEVELS];
    private final int[] bloomH = new int[BLOOM_LEVELS];

    // Asynchronous OpenGL Hardware Timer Queries (Ping-Pong)
    private final int[] queryRay = new int[2];
    private final int[] queryBloom = new int[2];
    private int queryIndex = 0;
    private boolean queriesPrimed = false;

    // Real-Time Performance Metrics & Rolling History
    private float currentFps = 60.0f;
    private float wallFrameMs = 16.6f;
    private float cpuSubmitMs = 0.15f;
    private float gpuRayMs = 2.5f;
    private float gpuBloomMs = 0.3f;
    private float gpuTotalMs = 2.8f;

    private final float[] gpuHistory = new float[HISTORY_LEN];
    private final float[] cpuHistory = new float[HISTORY_LEN];
    private int historyCursor = 0;

    private final HardwareTelemetry telemetry = new HardwareTelemetry();
    private UiRenderer ui;

    // =========================================================================
    // GLSL SHADERS
    // =========================================================================
    private static final String FULLSCREEN_VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0,
                                (gl_VertexID == 2) ? 3.0 : -1.0);
                vUv = pos * 0.5 + 0.5;
                gl_Position = vec4(pos, 0.0, 1.0);
            }
            """;

    // Pass 0A: Precompute Seamless Tileable 128^3 3D FBM Turbulence Texture
    private static final String NOISE3D_BAKE_FRAG = """
            #version 330 core
            out vec4 FragColor;

            uniform float uSliceZ;
            uniform float uSize;

            vec3 modWrap(vec3 p, float period) {
                return mod(p, period);
            }

            float hash31(vec3 p) {
                p = fract(p * vec3(443.8975, 397.2973, 491.1871));
                p += dot(p, p.yxz + 19.19);
                return fract((p.x + p.y) * p.z);
            }

            float periodicNoise3D(vec3 p, float period) {
                vec3 i = floor(p);
                vec3 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);

                vec3 i0 = modWrap(i, period);
                vec3 i1 = modWrap(i + 1.0, period);

                float n000 = hash31(vec3(i0.x, i0.y, i0.z));
                float n100 = hash31(vec3(i1.x, i0.y, i0.z));
                float n010 = hash31(vec3(i0.x, i1.y, i0.z));
                float n110 = hash31(vec3(i1.x, i1.y, i0.z));
                float n001 = hash31(vec3(i0.x, i0.y, i1.z));
                float n101 = hash31(vec3(i1.x, i0.y, i1.z));
                float n011 = hash31(vec3(i0.x, i1.y, i1.z));
                float n111 = hash31(vec3(i1.x, i1.y, i1.z));

                float nx00 = mix(n000, n100, f.x);
                float nx10 = mix(n010, n110, f.x);
                float nx01 = mix(n001, n101, f.x);
                float nx11 = mix(n011, n111, f.x);

                return mix(mix(nx00, nx10, f.y), mix(nx01, nx11, f.y), f.z);
            }

            void main() {
                vec3 uvw = vec3(gl_FragCoord.xy / uSize, uSliceZ);
                float sum = 0.0;
                float amp = 0.52;
                float period = 8.0;
                for (int o = 0; o < 4; o++) {
                    sum += amp * periodicNoise3D(uvw * period, period);
                    period *= 2.0;
                    amp *= 0.49;
                }
                FragColor = vec4(sum, 0.0, 0.0, 1.0);
            }
            """;

    // Pass 0B: Precompute Seamless 6-Face HDR Deep-Space Milky Way, Nebulae & Spiral Galaxies
    private static final String CUBEMAP_SKY_FRAG = """
            #version 330 core
            out vec4 FragColor;

            uniform float uFaceSize;
            uniform int uFace;
            uniform sampler3D uNoiseVol;

            float hash31(vec3 p) {
                p = fract(p * vec3(443.8975, 397.2973, 491.1871));
                p += dot(p, p.yxz + 19.19);
                return fract((p.x + p.y) * p.z);
            }

            vec3 getCubemapDir(int face, vec2 uv) {
                if (face == 0) return normalize(vec3( 1.0, -uv.y, -uv.x));
                if (face == 1) return normalize(vec3(-1.0, -uv.y,  uv.x));
                if (face == 2) return normalize(vec3( uv.x,  1.0,  uv.y));
                if (face == 3) return normalize(vec3( uv.x, -1.0, -uv.y));
                if (face == 4) return normalize(vec3( uv.x, -uv.y,  1.0));
                return normalize(vec3(-uv.x, -uv.y, -1.0));
            }

            vec3 renderGalaxy(vec3 dir, vec3 center, vec3 axisU, vec3 axisV, float radius, vec3 tint) {
                vec3 diff = dir - center;
                if (length(diff) > radius * 2.6) return vec3(0.0);

                float x = dot(diff, axisU) / radius;
                float y = dot(diff, axisV) / (radius * 0.42);
                float r = sqrt(x * x + y * y);
                if (r > 2.3) return vec3(0.0);

                float ang = atan(y, x);
                float spiral = 0.5 + 0.5 * cos(2.0 * ang - 6.8 * log(max(0.07, r)));
                float bulge = exp(-r * r * 15.0) * 2.5;
                float disk = exp(-r * 2.5) * (0.42 + 0.78 * spiral);
                return tint * (bulge + disk);
            }

            vec3 starSpectralColor(float s) {
                if (s < 0.15) return vec3(0.62, 0.78, 1.38);
                if (s < 0.35) return vec3(0.84, 0.92, 1.22);
                if (s < 0.60) return vec3(1.05, 1.02, 0.96);
                if (s < 0.82) return vec3(1.24, 0.92, 0.62);
                return vec3(1.36, 0.68, 0.44);
            }

            void main() {
                vec2 uv = (gl_FragCoord.xy / uFaceSize) * 2.0 - 1.0;
                vec3 dir = getCubemapDir(uFace, uv);

                float galLat = dir.y * 0.838 - dir.x * 0.545;
                float band = exp(-(galLat * galLat) / 0.036);

                float neb1 = texture(uNoiseVol, dir * 0.55 + vec3(0.15, 0.42, -0.26)).r;
                float neb2 = texture(uNoiseVol, dir * 1.45 - vec3(0.51, 0.22, 0.36)).r;
                float dustRift = smoothstep(0.35, 0.68, neb1 * 0.62 + neb2 * 0.38);

                vec3 warmBulge = vec3(0.56, 0.34, 0.21);
                vec3 blueNebula = vec3(0.14, 0.25, 0.48);
                vec3 hAlpha = vec3(0.52, 0.12, 0.24) * smoothstep(0.60, 0.84, neb2);
                vec3 nebCol = mix(blueNebula, warmBulge, exp(-(galLat * galLat) / 0.011) * neb2) + hAlpha;

                vec3 col = vec3(0.0012, 0.0018, 0.0040)
                         + nebCol * band * (0.25 + 0.52 * neb1)
                         * (1.0 - 0.85 * dustRift * exp(-(galLat * galLat) / 0.0065));

                col += renderGalaxy(dir, normalize(vec3(-0.26, 0.08, -0.96)),
                        normalize(vec3(0.96, 0.0, -0.26)), vec3(0.0, 1.0, 0.0), 0.052, vec3(1.16, 0.92, 0.70));
                col += renderGalaxy(dir, normalize(vec3(0.22, -0.11, -0.97)),
                        normalize(vec3(0.97, 0.0, 0.22)), vec3(0.0, 1.0, 0.0), 0.042, vec3(0.76, 0.94, 1.26));
                col += renderGalaxy(dir, normalize(vec3(-0.88, 0.14, 0.45)),
                        normalize(vec3(0.45, 0.0, 0.88)), vec3(0.0, 1.0, 0.0), 0.048, vec3(1.10, 0.88, 0.66));

                for (int layer = 0; layer < 3; layer++) {
                    float scale = (layer == 0) ? 215.0 : ((layer == 1) ? 115.0 : 58.0);
                    vec3 cell = floor(dir * scale);
                    vec3 local = fract(dir * scale) - 0.5;
                    float rnd = hash31(cell + float(layer) * 47.0);

                    float thresh = (layer == 0) ? 0.955 : ((layer == 1) ? 0.978 : 0.991);
                    if (rnd > thresh) {
                        vec3 jitter = vec3(
                            hash31(cell + 1.3) - 0.5,
                            hash31(cell + 2.7) - 0.5,
                            hash31(cell + 4.1) - 0.5
                        ) * 0.62;
                        float d = length(local - jitter);
                        float mag = (rnd - thresh) / (1.0 - thresh);

                        float core = exp(-d * d * (layer == 2 ? 75.0 : 120.0));
                        float halo = exp(-d * d * (layer == 2 ? 12.0 : 22.0)) * 0.22;

                        vec3 starTint = starSpectralColor(hash31(cell + 8.9));
                        float gain = (layer == 0) ? 1.8 : ((layer == 1) ? 3.5 : 7.5);
                        col += starTint * (core + halo) * pow(mag, 1.9) * gain;
                    }
                }

                FragColor = vec4(col, 1.0);
            }
            """;

    // Pass 1: Hardware-Accelerated Geodesic Ray-Tracer with 3D Noise Volume & Adaptive 4x AA
    private static final String BLACKHOLE_FRAG = """
            #version 330 core
            layout(location = 0) out vec4 FragColor;

            uniform vec2 uResolution;
            uniform float uTime;
            uniform vec3 uCamPos;
            uniform vec3 uCamForward;
            uniform vec3 uCamRight;
            uniform vec3 uCamUp;
            uniform samplerCube uSkybox;
            uniform sampler3D uNoiseVol;
            uniform float uSpinA;
            uniform float uDiskTempScale;
            uniform float uDiskDensity;
            uniform float uTanHalfFov;
            uniform int uDoppler;
            uniform int uLensing;
            uniform int uShowDisk;
            uniform int uShowGrid;
            uniform int uOrderVis;
            uniform int uAdaptiveAa;

            const float PI = 3.14159265359;
            const float RS = 1.0;
            const float R_DISK_OUT = 15.2;
            const float R_ESCAPE = 32.0;

            vec3 blackbody(float tempKelvin) {
                float t = clamp(tempKelvin, 1100.0, 23000.0) / 100.0;
                vec3 col;
                if (t <= 66.0) {
                    col.r = 1.0;
                    col.g = clamp((99.4708025861 * log(max(1.0, t)) - 161.1195681661) / 255.0, 0.0, 1.0);
                } else {
                    col.r = clamp((329.698727446 * pow(t - 60.0, -0.1332047592)) / 255.0, 0.0, 1.0);
                    col.g = clamp((288.1221695283 * pow(t - 60.0, -0.0755148492)) / 255.0, 0.0, 1.0);
                }
                if (t >= 66.0) {
                    col.b = 1.0;
                } else if (t <= 19.0) {
                    col.b = 0.0;
                } else {
                    col.b = clamp((138.5177312231 * log(t - 10.0) - 305.0447927307) / 255.0, 0.0, 1.0);
                }
                return pow(col, vec3(2.2));
            }

            vec3 geodesicAccel(vec3 pos, vec3 vel, float h2, float spinA) {
                if (uLensing == 0) return vec3(0.0);

                float r2 = dot(pos, pos);
                float r = sqrt(r2);
                float invR5 = 1.0 / (r2 * r2 * r);
                vec3 a = (-1.5 * RS * h2 * invR5) * pos;

                if (spinA > 0.0) {
                    float rXZ2 = dot(pos.xz, pos.xz);
                    if (rXZ2 > 1e-5) {
                        vec3 phiHat = vec3(-pos.z, 0.0, pos.x) * inversesqrt(rXZ2);
                        float drag = 0.42 * spinA * RS * RS / (r2 * r2);
                        vec3 dragVec = phiHat * drag;
                        dragVec -= dot(dragVec, vel) * vel;
                        a += dragVec;
                    }
                }
                return a;
            }

            void rk4Step(inout vec3 p, inout vec3 v, float ds, float h2, float spinA) {
                if (uLensing == 0) {
                    p += v * ds;
                    return;
                }
                vec3 k1_p = v;
                vec3 k1_v = geodesicAccel(p, v, h2, spinA);

                vec3 p2 = p + 0.5 * ds * k1_p;
                vec3 v2 = normalize(v + 0.5 * ds * k1_v);
                vec3 k2_p = v2;
                vec3 k2_v = geodesicAccel(p2, v2, h2, spinA);

                vec3 p3 = p + 0.5 * ds * k2_p;
                vec3 v3 = normalize(v + 0.5 * ds * k2_v);
                vec3 k3_p = v3;
                vec3 k3_v = geodesicAccel(p3, v3, h2, spinA);

                vec3 p4 = p + ds * k3_p;
                vec3 v4 = normalize(v + ds * k3_v);
                vec3 k4_p = v4;
                vec3 k4_v = geodesicAccel(p4, v4, h2, spinA);

                p += (ds / 6.0) * (k1_p + 2.0 * k2_p + 2.0 * k3_p + k4_p);
                v = normalize(v + (ds / 6.0) * (k1_v + 2.0 * k2_v + 2.0 * k3_v + k4_v));
            }

            vec3 intersectEquatorialHermite(vec3 p0, vec3 v0, vec3 p1, vec3 v1, float ds) {
                float y0 = p0.y;
                float y1 = p1.y;
                float m0 = v0.y * ds;
                float m1 = v1.y * ds;

                float t = clamp(abs(y0) / (abs(y0) + abs(y1) + 1e-8), 0.0, 1.0);
                for (int iter = 0; iter < 2; iter++) {
                    float t2 = t * t;
                    float t3 = t2 * t;
                    float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
                    float h10 = t3 - 2.0 * t2 + t;
                    float h01 = -2.0 * t3 + 3.0 * t2;
                    float h11 = t3 - t2;

                    float f = h00 * y0 + h10 * m0 + h01 * y1 + h11 * m1;
                    float df = (6.0 * t2 - 6.0 * t) * y0
                             + (3.0 * t2 - 4.0 * t + 1.0) * m0
                             + (-6.0 * t2 + 6.0 * t) * y1
                             + (3.0 * t2 - 2.0 * t) * m1;
                    if (abs(df) > 1e-7) {
                        t = clamp(t - f / df, 0.0, 1.0);
                    }
                }

                float t2 = t * t;
                float t3 = t2 * t;
                vec3 hit = (2.0 * t3 - 3.0 * t2 + 1.0) * p0
                         + (t3 - 2.0 * t2 + t) * (v0 * ds)
                         + (-2.0 * t3 + 3.0 * t2) * p1
                         + (t3 - t2) * (v1 * ds);
                hit.y = 0.0;
                return hit;
            }

            vec3 sampleSkyWithOptionalGrid(vec3 dir) {
                vec3 sky = texture(uSkybox, dir).rgb;
                if (uShowGrid == 1) {
                    float lon = atan(dir.z, dir.x);
                    float lat = asin(clamp(dir.y, -1.0, 1.0));

                    float gridSpacing = PI / 12.0;
                    float dLon = abs(fract(lon / gridSpacing + 0.5) - 0.5) * gridSpacing * cos(lat);
                    float dLat = abs(fract(lat / gridSpacing + 0.5) - 0.5) * gridSpacing;

                    float line = 1.0 - smoothstep(0.0025, 0.0065, min(dLon, dLat));
                    float equator = 1.0 - smoothstep(0.004, 0.010, abs(lat));
                    float prime = 1.0 - smoothstep(0.004, 0.010, abs(lon) * cos(lat));

                    sky += vec3(0.18, 0.55, 0.95) * line * 0.45;
                    sky += vec3(1.0, 0.42, 0.18) * max(equator, prime) * 0.85;
                }
                return sky;
            }

            vec3 traceRay(vec2 pixelCoord, out bool highCurvatureZone) {
                vec2 ndc = (pixelCoord / uResolution) * 2.0 - 1.0;
                float aspect = uResolution.x / uResolution.y;

                vec3 p = uCamPos;
                float rCam = length(p);

                float horizonR = 1.0 * RS;
                float iscoR = mix(3.0, 2.30, uSpinA);
                float photonSphereR = 1.5 * RS;

                vec3 vLocal = normalize(uCamForward
                                      + ndc.x * aspect * uTanHalfFov * uCamRight
                                      + ndc.y * uTanHalfFov * uCamUp);

                vec3 v;
                if (uLensing == 1 && rCam > horizonR * 1.05) {
                    vec3 rHat = p / rCam;
                    vec3 vRad = dot(vLocal, rHat) * rHat;
                    vec3 vTan = vLocal - vRad;
                    float metricStretch = sqrt(max(0.06, 1.0 - horizonR / rCam));
                    v = normalize(vTan + vRad * metricStretch);
                } else {
                    v = vLocal;
                }

                vec3 L = cross(p, v);
                float h2 = dot(L, L);

                vec3 accumColor = vec3(0.0);
                float transmittance = 1.0;
                bool captured = false;
                bool touchedDisk = false;
                float minPhotonApproach = 100.0;
                float totalDeflectionAngle = 0.0;

                float r = rCam;

                for (int step = 0; step < 300; step++) {
                    minPhotonApproach = min(minPhotonApproach, r);

                    if (r <= horizonR * 1.02 || (uLensing == 1 && r < 1.32 * RS && dot(p, v) < 0.0)) {
                        captured = true;
                        break;
                    }
                    if (r > R_ESCAPE && dot(p, v) > 0.0) {
                        break;
                    }
                    if (transmittance < 0.008) {
                        break;
                    }

                    float sdfHorizon = max(0.0, r - horizonR);
                    float sdfDisk = abs(p.y);
                    float ds = clamp(min(0.070 * sdfHorizon + 0.020, 0.22 + sdfDisk * 0.36), 0.022, 1.35);

                    vec3 prevP = p;
                    vec3 prevV = v;
                    rk4Step(p, v, ds, h2, uSpinA);
                    r = length(p);

                    totalDeflectionAngle += length(cross(prevP, p)) / max(1e-6, length(prevP) * r);

                    if (r <= horizonR * 1.02 || (uLensing == 1 && r < 1.32 * RS && dot(p, v) < 0.0)) {
                        captured = true;
                        break;
                    }

                    if (uShowDisk == 1) {
                        float slabHalfH = 0.022 * pow(max(0.0, r - iscoR), 0.75) + 0.012;
                        bool crossed = (prevP.y > 0.0 && p.y <= 0.0) || (prevP.y < 0.0 && p.y >= 0.0);
                        bool inSlab = (abs(p.y) < slabHalfH) && (r > horizonR * 1.15) && (r <= R_DISK_OUT);

                        if (crossed || inSlab) {
                            vec3 hitP;
                            float stepWeight;
                            if (crossed) {
                                hitP = intersectEquatorialHermite(prevP, prevV, p, v, ds);
                                stepWeight = 0.88;
                            } else {
                                hitP = p;
                                float vProfile = exp(-(hitP.y * hitP.y) / (slabHalfH * slabHalfH * 0.38));
                                stepWeight = min(0.32, ds * 1.25) * vProfile;
                            }

                            float hitR = length(hitP.xz);
                            if (hitR > horizonR * 1.15 && hitR <= R_DISK_OUT) {
                                touchedDisk = true;
                                float phi = atan(hitP.z, hitP.x);
                                bool inPlunge = hitR < iscoR;

                                float density = 0.0;
                                float structure = 0.5;
                                float uNorm = clamp((hitR - iscoR) / (R_DISK_OUT - iscoR), 0.0, 1.0);

                                if (inPlunge) {
                                    float plungeU = (hitR - horizonR * 1.15) / max(0.01, iscoR - horizonR * 1.15);
                                    float spiral = phi + uTime * 3.4 - 5.5 * log(max(0.08, plungeU));
                                    vec3 volCoord = vec3(cos(spiral) * 0.25, hitP.y * 0.8, sin(spiral) * 0.25);
                                    float stream = texture(uNoiseVol, volCoord).r;
                                    float filament = pow(max(0.0, sin(spiral * 3.0 + stream * 5.2)), 4.0);
                                    density = filament * smoothstep(0.0, 0.45, plungeU) * 0.48;
                                    structure = filament;
                                } else {
                                    float omega = 2.75 * pow(iscoR / hitR, 1.35);
                                    float logSpiral = phi + uTime * omega + 8.0 * log(hitR / iscoR);

                                    vec3 volCoord = vec3(
                                        cos(logSpiral) * (0.22 + uNorm * 0.65),
                                        hitP.y * 0.95 - uTime * 0.025,
                                        sin(logSpiral) * (0.22 + uNorm * 0.65) + uNorm * 0.9
                                    );
                                    float turb = texture(uNoiseVol, volCoord).r;

                                    float mainWaves = 0.5 + 0.5 * sin(uNorm * 72.0 + turb * 7.6);
                                    float fineThreads = 0.5 + 0.5 * cos(uNorm * 175.0 - turb * 11.0 + logSpiral * 2.0);
                                    structure = 0.58 * mainWaves + 0.42 * fineThreads;

                                    float innerRamp = smoothstep(0.0, 0.032, uNorm);
                                    float outerFade = pow(1.0 - uNorm, 1.40);
                                    float iscoPeak = exp(-pow((uNorm - 0.025) / 0.045, 2.0)) * 0.90;

                                    density = max(0.0, (structure * 1.18 + iscoPeak) * innerRamp * outerFade) * uDiskDensity;
                                }

                                if (density > 0.003) {
                                    float gFactor = 1.0;

                                    if (uDoppler == 1) {
                                        float betaOrb = min(0.66, sqrt(0.5 * RS / max(0.55, hitR - 0.5 * RS)));
                                        vec3 tangentDir = normalize(vec3(-hitP.z, 0.0, hitP.x));
                                        vec3 radialInDir = normalize(vec3(-hitP.x, 0.0, -hitP.z));
                                        float plungeFrac = inPlunge ? 0.30 : 0.02;
                                        vec3 betaVec = betaOrb * normalize(tangentDir + radialInDir * plungeFrac);

                                        float beta = length(betaVec);
                                        float cosTheta = dot(betaVec / beta, -v);
                                        float gamma = inversesqrt(1.0 - beta * beta);
                                        float gravShift = sqrt(max(0.20, 1.0 - 0.85 * horizonR / hitR));
                                        gFactor = gravShift / (gamma * (1.0 - beta * cosTheta));
                                    }

                                    vec3 emitCol;
                                    if (uOrderVis == 1) {
                                        if (totalDeflectionAngle < PI) {
                                            emitCol = vec3(1.0, 0.55, 0.15);
                                        } else if (totalDeflectionAngle < 2.0 * PI) {
                                            emitCol = vec3(0.15, 0.85, 1.0);
                                        } else {
                                            emitCol = vec3(1.0, 0.18, 0.72);
                                        }
                                    } else {
                                        float effectiveR = max(iscoR * 0.94, hitR);
                                        float baseTemp = (12800.0 * uDiskTempScale) * pow(iscoR / effectiveR, 0.68) * (0.72 + 0.48 * structure);
                                        float obsTemp = baseTemp * pow(gFactor, 0.82);
                                        emitCol = blackbody(obsTemp);
                                    }

                                    float boost = (uOrderVis == 1) ? 1.0 : pow(gFactor, 2.65);
                                    float emission = density * boost * stepWeight * 1.42;
                                    float dustOpacity = clamp(density * stepWeight * (0.62 + 0.92 * uNorm), 0.0, 0.96);

                                    accumColor += transmittance * emission * emitCol;
                                    transmittance *= (1.0 - dustOpacity);
                                }
                            }
                        }
                    }

                }

                if (!captured && transmittance > 0.005) {
                    accumColor += transmittance * sampleSkyWithOptionalGrid(normalize(v));

                    if (uLensing == 1) {
                        float ringDist = max(0.0, minPhotonApproach - photonSphereR);
                        float photonCorona = exp(-ringDist * 11.0) * 0.22;
                        accumColor += vec3(1.0, 0.78, 0.48) * photonCorona;
                    }
                }

                highCurvatureZone = touchedDisk || captured || (minPhotonApproach < 3.4 * RS);
                return accumColor;
            }

            void main() {
                bool highCurvature = false;
                vec3 c0 = traceRay(gl_FragCoord.xy, highCurvature);

                if (uAdaptiveAa == 0 || !highCurvature) {
                    FragColor = vec4(c0, 1.0);
                } else {
                    vec2 offsets[3] = vec2[](
                        vec2( 0.510,  0.138),
                        vec2(-0.735,  0.707),
                        vec2( 0.020, -0.724)
                    );
                    vec3 sum = c0;
                    for (int s = 0; s < 3; s++) {
                        bool dummy;
                        sum += traceRay(gl_FragCoord.xy + offsets[s] * 0.5, dummy);
                    }
                    FragColor = vec4(sum * 0.25, 1.0);
                }
            }
            """;

    // Pass 2A: 13-Tap Karis-Averaged Downsample
    private static final String DOWNSAMPLE_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 FragColor;

            uniform sampler2D uSource;
            uniform int uIsFirstPass;

            vec3 karisAverage(vec3 c) {
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                return c / (1.0 + luma);
            }

            void main() {
                vec2 texel = 1.0 / vec2(textureSize(uSource, 0));
                float x = texel.x;
                float y = texel.y;

                vec3 a = texture(uSource, vec2(vUv.x - 2.0*x, vUv.y + 2.0*y)).rgb;
                vec3 b = texture(uSource, vec2(vUv.x,         vUv.y + 2.0*y)).rgb;
                vec3 c = texture(uSource, vec2(vUv.x + 2.0*x, vUv.y + 2.0*y)).rgb;
                vec3 d = texture(uSource, vec2(vUv.x - 2.0*x, vUv.y)).rgb;
                vec3 e = texture(uSource, vec2(vUv.x,         vUv.y)).rgb;
                vec3 f = texture(uSource, vec2(vUv.x + 2.0*x, vUv.y)).rgb;
                vec3 g = texture(uSource, vec2(vUv.x - 2.0*x, vUv.y - 2.0*y)).rgb;
                vec3 h = texture(uSource, vec2(vUv.x,         vUv.y - 2.0*y)).rgb;
                vec3 i = texture(uSource, vec2(vUv.x + 2.0*x, vUv.y - 2.0*y)).rgb;

                vec3 j = texture(uSource, vec2(vUv.x - x, vUv.y + y)).rgb;
                vec3 k = texture(uSource, vec2(vUv.x + x, vUv.y + y)).rgb;
                vec3 l = texture(uSource, vec2(vUv.x - x, vUv.y - y)).rgb;
                vec3 m = texture(uSource, vec2(vUv.x + x, vUv.y - y)).rgb;

                vec3 downsample;
                if (uIsFirstPass == 1) {
                    vec3 g0 = karisAverage((a + b + d + e) * 0.25);
                    vec3 g1 = karisAverage((b + c + e + f) * 0.25);
                    vec3 g2 = karisAverage((d + e + g + h) * 0.25);
                    vec3 g3 = karisAverage((e + f + h + i) * 0.25);
                    vec3 g4 = karisAverage((j + k + l + m) * 0.25);
                    downsample = g0 * 0.125 + g1 * 0.125 + g2 * 0.125 + g3 * 0.125 + g4 * 0.5;
                    float lum = dot(downsample, vec3(0.2126, 0.7152, 0.0722));
                    downsample *= smoothstep(0.55, 1.45, lum);
                } else {
                    downsample = e * 0.125;
                    downsample += (a + c + g + i) * 0.03125;
                    downsample += (b + d + f + h) * 0.0625;
                    downsample += (j + k + l + m) * 0.125;
                }

                FragColor = vec4(max(vec3(0.0), downsample), 1.0);
            }
            """;

    // Pass 2B: 9-Tap Tent Filter Upsample
    private static final String UPSAMPLE_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 FragColor;

            uniform sampler2D uSource;
            uniform float uFilterRadius;

            void main() {
                vec2 texel = 1.0 / vec2(textureSize(uSource, 0));
                float x = texel.x * uFilterRadius;
                float y = texel.y * uFilterRadius;

                vec3 a = texture(uSource, vec2(vUv.x - x, vUv.y + y)).rgb;
                vec3 b = texture(uSource, vec2(vUv.x,     vUv.y + y)).rgb;
                vec3 c = texture(uSource, vec2(vUv.x + x, vUv.y + y)).rgb;

                vec3 d = texture(uSource, vec2(vUv.x - x, vUv.y)).rgb;
                vec3 e = texture(uSource, vec2(vUv.x,     vUv.y)).rgb;
                vec3 f = texture(uSource, vec2(vUv.x + x, vUv.y)).rgb;

                vec3 g = texture(uSource, vec2(vUv.x - x, vUv.y - y)).rgb;
                vec3 h = texture(uSource, vec2(vUv.x,     vUv.y - y)).rgb;
                vec3 i = texture(uSource, vec2(vUv.x + x, vUv.y - y)).rgb;

                vec3 upsample = e * 4.0;
                upsample += (b + d + f + h) * 2.0;
                upsample += (a + c + g + i);
                upsample *= (1.0 / 16.0);

                FragColor = vec4(upsample, 1.0);
            }
            """;

    // Pass 3: Final Composite, Exposure, Lens Dispersion & ACES Tone Mapping
    private static final String COMPOSITE_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 FragColor;

            uniform sampler2D uHdrScene;
            uniform sampler2D uBloomTex;
            uniform float uBloomStrength;
            uniform float uExposure;

            vec3 toneMapACES(vec3 x) {
                x = max(vec3(0.0), x);
                vec3 mapped = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
                return pow(clamp(mapped, 0.0, 1.0), vec3(1.0 / 2.2));
            }

            void main() {
                vec2 fromCenter = vUv - 0.5;
                float r2 = dot(fromCenter, fromCenter);
                vec2 caOffset = fromCenter * r2 * 0.0035;

                float r = texture(uHdrScene, vUv + caOffset).r;
                float g = texture(uHdrScene, vUv).g;
                float b = texture(uHdrScene, vUv - caOffset).b;
                vec3 hdr = vec3(r, g, b);

                vec3 bloom = texture(uBloomTex, vUv).rgb;
                vec3 combined = (hdr + bloom * uBloomStrength) * uExposure;

                float vignette = 1.0 - r2 * 0.28;
                FragColor = vec4(toneMapACES(combined * vignette), 1.0);
            }
            """;

    // Pass 4: Hardware-Accelerated SDF Rounded Glass UI & Crisp Font Shader
    private static final String UI_VERT = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;
            layout(location = 2) in vec4 aColor;
            layout(location = 3) in vec4 aRect;
            layout(location = 4) in vec2 aParams;

            uniform vec2 uResolution;

            out vec2 vPos;
            out vec2 vUv;
            out vec4 vColor;
            out vec4 vRect;
            out vec2 vParams;

            void main() {
                vPos = aPos;
                vUv = aUv;
                vColor = aColor;
                vRect = aRect;
                vParams = aParams;

                vec2 ndc = vec2((aPos.x / uResolution.x) * 2.0 - 1.0,
                                1.0 - (aPos.y / uResolution.y) * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
            """;

    private static final String UI_FRAG = """
            #version 330 core
            in vec2 vPos;
            in vec2 vUv;
            in vec4 vColor;
            in vec4 vRect;
            in vec2 vParams;

            uniform sampler2D uFontTex;
            out vec4 FragColor;

            float sdRoundedBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            void main() {
                float mode = vParams.y;
                if (mode > 1.5) {
                    float glyphAlpha = texture(uFontTex, vUv).r;
                    if (glyphAlpha < 0.1) discard;
                    FragColor = vec4(vColor.rgb, vColor.a * glyphAlpha);
                } else {
                    float d = sdRoundedBox(vPos - vRect.xy, vRect.zw, vParams.x);
                    if (mode < 0.5) {
                        float alpha = 1.0 - smoothstep(-0.75, 0.75, d);
                        if (alpha < 0.004) discard;
                        FragColor = vec4(vColor.rgb, vColor.a * alpha);
                    } else {
                        float outer = 1.0 - smoothstep(-0.75, 0.75, d);
                        float inner = smoothstep(-2.0, -0.6, d);
                        float alpha = outer * inner;
                        if (alpha < 0.004) discard;
                        FragColor = vec4(vColor.rgb, vColor.a * alpha);
                    }
                }
            }
            """;

    // =========================================================================
    // MAIN APPLICATION LOOP
    // =========================================================================
    public void run() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        long window = glfwCreateWindow(windowW, windowH, "Relativistic Kerr Black Hole — GPU Telemetry & Interactive Console", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW OpenGL 3.3 window");
        }

        setupCallbacks(window);

        glfwMakeContextCurrent(window);
        glfwSwapInterval(vsync ? 1 : 0);
        glfwShowWindow(window);

        GL.createCapabilities();
        glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);

        telemetry.initOpenGlInfo(glGetString(GL_RENDERER), glGetString(GL_VERSION));
        telemetry.startBackgroundSampler();

        int noiseProg = createShaderProgram(FULLSCREEN_VERT, NOISE3D_BAKE_FRAG);
        int skyProg = createShaderProgram(FULLSCREEN_VERT, CUBEMAP_SKY_FRAG);
        int rayProg = createShaderProgram(FULLSCREEN_VERT, BLACKHOLE_FRAG);
        int downProg = createShaderProgram(FULLSCREEN_VERT, DOWNSAMPLE_FRAG);
        int upProg = createShaderProgram(FULLSCREEN_VERT, UPSAMPLE_FRAG);
        int compProg = createShaderProgram(FULLSCREEN_VERT, COMPOSITE_FRAG);
        int uiProg = createShaderProgram(UI_VERT, UI_FRAG);

        int quadVao = glGenVertexArrays();
        glBindVertexArray(quadVao);

        // Bake 128^3 3D Noise Volume & 1024^2 HDR Skybox Cubemap on GPU at Startup
        noise3dTex = bake3dNoiseVolume(noiseProg, NOISE_VOL_SIZE);
        glDeleteProgram(noiseProg);

        skyboxCubemap = buildHdrSkyboxCubemap(skyProg, noise3dTex, 1024);
        glDeleteProgram(skyProg);

        allocateFramebuffers(windowW, windowH);

        // Create Ping-Pong Hardware GPU Timer Queries
        queryRay[0] = glGenQueries();
        queryRay[1] = glGenQueries();
        queryBloom[0] = glGenQueries();
        queryBloom[1] = glGenQueries();

        ui = new UiRenderer(uiProg);

        // Cache Uniform Locations
        int uResolution = glGetUniformLocation(rayProg, "uResolution");
        int uTime = glGetUniformLocation(rayProg, "uTime");
        int uCamPos = glGetUniformLocation(rayProg, "uCamPos");
        int uCamForward = glGetUniformLocation(rayProg, "uCamForward");
        int uCamRight = glGetUniformLocation(rayProg, "uCamRight");
        int uCamUp = glGetUniformLocation(rayProg, "uCamUp");
        int uSkybox = glGetUniformLocation(rayProg, "uSkybox");
        int uNoiseVol = glGetUniformLocation(rayProg, "uNoiseVol");
        int uSpinA = glGetUniformLocation(rayProg, "uSpinA");
        int uDiskTempScale = glGetUniformLocation(rayProg, "uDiskTempScale");
        int uDiskDensity = glGetUniformLocation(rayProg, "uDiskDensity");
        int uTanHalfFov = glGetUniformLocation(rayProg, "uTanHalfFov");
        int uDoppler = glGetUniformLocation(rayProg, "uDoppler");
        int uLensing = glGetUniformLocation(rayProg, "uLensing");
        int uShowDisk = glGetUniformLocation(rayProg, "uShowDisk");
        int uShowGrid = glGetUniformLocation(rayProg, "uShowGrid");
        int uOrderVis = glGetUniformLocation(rayProg, "uOrderVis");
        int uAdaptiveAa = glGetUniformLocation(rayProg, "uAdaptiveAa");

        int uDownSource = glGetUniformLocation(downProg, "uSource");
        int uIsFirstPass = glGetUniformLocation(downProg, "uIsFirstPass");

        int uUpSource = glGetUniformLocation(upProg, "uSource");
        int uFilterRadius = glGetUniformLocation(upProg, "uFilterRadius");

        int uCompHdr = glGetUniformLocation(compProg, "uHdrScene");
        int uCompBloom = glGetUniformLocation(compProg, "uBloomTex");
        int uCompStrength = glGetUniformLocation(compProg, "uBloomStrength");
        int uCompExposure = glGetUniformLocation(compProg, "uExposure");

        long lastFrameNano = System.nanoTime();
        long fpsTimer = lastFrameNano;
        int frameCounter = 0;

        while (!glfwWindowShouldClose(window)) {
            long frameStartNano = System.nanoTime();

            if (framebufferResized) {
                allocateFramebuffers(windowW, windowH);
                framebufferResized = false;
            }

            double dt = Math.min((frameStartNano - lastFrameNano) / 1_000_000_000.0, 0.05);
            wallFrameMs = (float) ((frameStartNano - lastFrameNano) / 1_000_000.0);
            lastFrameNano = frameStartNano;

            frameCounter++;
            if (frameStartNano - fpsTimer >= 250_000_000L) {
                currentFps = (float) (frameCounter * 1_000_000_000.0 / (frameStartNano - fpsTimer));
                frameCounter = 0;
                fpsTimer = frameStartNano;
                telemetry.pollOpenGlVram();
            }

            // Read previous frame's asynchronous GPU timer queries without pipeline stall
            int readIdx = 1 - queryIndex;
            if (queriesPrimed) {
                long rayNs = glGetQueryObjectui64(queryRay[readIdx], GL_QUERY_RESULT);
                long bloomNs = glGetQueryObjectui64(queryBloom[readIdx], GL_QUERY_RESULT);
                gpuRayMs = gpuRayMs * 0.85f + (rayNs / 1_000_000.0f) * 0.15f;
                gpuBloomMs = gpuBloomMs * 0.85f + (bloomNs / 1_000_000.0f) * 0.15f;
                gpuTotalMs = gpuRayMs + gpuBloomMs;
            }

            if (!paused) {
                simTime += dt;
                if (autoOrbit) {
                    targetYaw += dt * 0.14;
                    targetPitch = 0.13 + Math.sin(simTime * 0.20) * 0.25;
                }
            }

            double camLerp = 1.0 - Math.exp(-dt * 11.0);
            camDist += (targetDist - camDist) * camLerp;
            camYaw += (targetYaw - camYaw) * camLerp;
            camPitch += (targetPitch - camPitch) * camLerp;
            camRoll += (targetRoll - camRoll) * camLerp;

            double cosP = Math.cos(camPitch), sinP = Math.sin(camPitch);
            double cosY = Math.cos(camYaw), sinY = Math.sin(camYaw);

            double cx = camDist * cosP * sinY;
            double cy = camDist * sinP;
            double cz = camDist * cosP * cosY;

            double invFLen = 1.0 / Math.sqrt(cx * cx + cy * cy + cz * cz);
            double fx = -cx * invFLen, fy = -cy * invFLen, fz = -cz * invFLen;

            double rx = fz, rz = -fx;
            double rLen = Math.sqrt(rx * rx + rz * rz);
            if (rLen < 1e-6) { rx = 1.0; rz = 0.0; rLen = 1.0; }
            rx /= rLen; rz /= rLen;

            double ux = -rz * fy;
            double uy = rz * fx - rx * fz;
            double uz = rx * fy;

            double cosR = Math.cos(camRoll), sinR = Math.sin(camRoll);
            double rightX = rx * cosR + ux * sinR;
            double rightY = uy * sinR;
            double rightZ = rz * cosR + uz * sinR;

            double upX = ux * cosR - rx * sinR;
            double upY = uy * cosR;
            double upZ = uz * cosR - rz * sinR;

            glBindVertexArray(quadVao);

            // PASS 1: Geodesic Ray-Tracer (Timed by GPU Query)
            glBeginQuery(GL_TIME_ELAPSED, queryRay[queryIndex]);
            glBindFramebuffer(GL_FRAMEBUFFER, hdrFbo);
            glViewport(0, 0, windowW, windowH);
            glUseProgram(rayProg);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_CUBE_MAP, skyboxCubemap);
            glUniform1i(uSkybox, 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, noise3dTex);
            glUniform1i(uNoiseVol, 1);

            glUniform2f(uResolution, windowW, windowH);
            glUniform1f(uTime, (float) simTime);
            glUniform3f(uCamPos, (float) cx, (float) cy, (float) cz);
            glUniform3f(uCamForward, (float) fx, (float) fy, (float) fz);
            glUniform3f(uCamRight, (float) rightX, (float) rightY, (float) rightZ);
            glUniform3f(uCamUp, (float) upX, (float) upY, (float) upZ);
            glUniform1f(uSpinA, kerrSpinEnabled ? spinParam : 0.0f);
            glUniform1f(uDiskTempScale, diskTempScale);
            glUniform1f(uDiskDensity, diskDensity);
            glUniform1f(uTanHalfFov, (float) Math.tan(Math.toRadians(fovDeg)));
            glUniform1i(uDoppler, dopplerEnabled ? 1 : 0);
            glUniform1i(uLensing, lensingEnabled ? 1 : 0);
            glUniform1i(uShowDisk, showDisk ? 1 : 0);
            glUniform1i(uShowGrid, showGrid ? 1 : 0);
            glUniform1i(uOrderVis, orderVisualizer ? 1 : 0);
            glUniform1i(uAdaptiveAa, adaptiveAa4x ? 1 : 0);

            glDrawArrays(GL_TRIANGLES, 0, 3);
            glEndQuery(GL_TIME_ELAPSED);

            // PASS 2: 5-Stage Karis Bloom Pyramid (Timed by GPU Query)
            glBeginQuery(GL_TIME_ELAPSED, queryBloom[queryIndex]);
            glUseProgram(downProg);
            glUniform1i(uDownSource, 0);
            glActiveTexture(GL_TEXTURE0);

            for (int i = 0; i < BLOOM_LEVELS; i++) {
                glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo[i]);
                glViewport(0, 0, bloomW[i], bloomH[i]);
                glBindTexture(GL_TEXTURE_2D, (i == 0) ? hdrColorTex : bloomTex[i - 1]);
                glUniform1i(uIsFirstPass, (i == 0) ? 1 : 0);
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }

            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE);
            glUseProgram(upProg);
            glUniform1i(uUpSource, 0);
            glUniform1f(uFilterRadius, 1.15f);

            for (int i = BLOOM_LEVELS - 2; i >= 0; i--) {
                glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo[i]);
                glViewport(0, 0, bloomW[i], bloomH[i]);
                glBindTexture(GL_TEXTURE_2D, bloomTex[i + 1]);
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }
            glDisable(GL_BLEND);
            glEndQuery(GL_TIME_ELAPSED);

            // PASS 3: Final Composite + Lens Dispersion + ACES Tone Mapping
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, windowW, windowH);
            glUseProgram(compProg);
            glUniform1i(uCompHdr, 0);
            glUniform1i(uCompBloom, 1);
            glUniform1f(uCompStrength, bloomStrength);
            glUniform1f(uCompExposure, exposure);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, hdrColorTex);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, bloomTex[0]);
            glDrawArrays(GL_TRIANGLES, 0, 3);

            // PASS 4: Interactive Glassmorphic Telemetry & Controls UI Overlay
            if (showUi) {
                renderOverlayUi();
            } else {
                hitBoxes.clear();
            }

            // Record CPU host frame submission time prior to swap
            long cpuEndNano = System.nanoTime();
            float rawCpuMs = (cpuEndNano - frameStartNano) / 1_000_000.0f;
            cpuSubmitMs = cpuSubmitMs * 0.85f + rawCpuMs * 0.15f;

            gpuHistory[historyCursor] = gpuTotalMs;
            cpuHistory[historyCursor] = cpuSubmitMs;
            historyCursor = (historyCursor + 1) % HISTORY_LEN;

            if (queryIndex == 1) queriesPrimed = true;
            queryIndex = 1 - queryIndex;

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        telemetry.stop();
        ui.cleanup();
        cleanupFramebuffers();
        glDeleteQueries(queryRay[0]);
        glDeleteQueries(queryRay[1]);
        glDeleteQueries(queryBloom[0]);
        glDeleteQueries(queryBloom[1]);
        glDeleteTextures(skyboxCubemap);
        glDeleteTextures(noise3dTex);
        glDeleteVertexArrays(quadVao);
        glDeleteProgram(rayProg);
        glDeleteProgram(downProg);
        glDeleteProgram(upProg);
        glDeleteProgram(compProg);
        glDeleteProgram(uiProg);
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    // =========================================================================
    // GLASSMORPHIC UI & HARDWARE TELEMETRY OVERLAY
    // =========================================================================
    private void renderOverlayUi() {
        hitBoxes.clear();
        ui.begin(windowW, windowH);

        // ---------------------------------------------------------------------
        // LEFT PANEL: REAL-TIME GPU & CPU PERFORMANCE TELEMETRY
        // ---------------------------------------------------------------------
        float lx = 16f, ly = 16f, lw = 342f, lh = 344f;
        ui.drawGlassPanel(lx, ly, lw, lh, 10f, 0.08f, 0.10f, 0.15f, 0.82f, 0.45f, 0.65f, 0.95f, 0.35f);

        float cx = lx + 14f;
        float cy = ly + 13f;

        ui.drawText("RELATIVISTIC TELEMETRY & PROFILER", cx, cy, 1.35f, 0.95f, 0.78f, 0.48f, 1.0f);
        cy += 20f;
        ui.drawRect(cx, cy, lw - 28f, 1f, 0f, 1f, 1f, 1f, 0.12f);
        cy += 10f;

        // Frame Rate & Frame Time Summary
        float fpsColorR = currentFps >= 58f ? 0.35f : 0.98f;
        float fpsColorG = currentFps >= 58f ? 0.95f : 0.45f;
        ui.drawText(String.format(Locale.US, "FPS: %.1f   FRAME: %.2f ms (%dx%d)",
                currentFps, wallFrameMs, windowW, windowH), cx, cy, 1.25f, fpsColorR, fpsColorG, 0.55f, 1.0f);
        cy += 22f;

        // GPU Hardware Section
        ui.drawText("GPU: " + telemetry.gpuShortName, cx, cy, 1.2f, 0.55f, 0.85f, 1.0f, 1.0f);
        cy += 16f;

        float gpuLoadPct = telemetry.getGpuLoadPercent(gpuTotalMs, wallFrameMs, vsync);
        ui.drawText(String.format(Locale.US, "GPU LOAD: %5.1f%%   SHADER: %.2f ms", gpuLoadPct, gpuTotalMs),
                cx, cy, 1.2f, 0.90f, 0.94f, 1.0f, 1.0f);
        cy += 14f;
        ui.drawProgressBar(cx, cy, lw - 28f, 7f, gpuLoadPct / 100f, 0.25f, 0.82f, 1.0f);
        cy += 13f;

        ui.drawText(String.format(Locale.US, "PASSES  Raytrace: %.2f ms | Bloom: %.2f ms", gpuRayMs, gpuBloomMs),
                cx, cy, 1.15f, 0.72f, 0.78f, 0.88f, 0.95f);
        cy += 15f;

        if (telemetry.vramTotalMb > 0) {
            float vramPct = Math.min(1f, (float) telemetry.vramUsedMb / telemetry.vramTotalMb);
            String extra = telemetry.gpuTempC > 0
                    ? String.format(Locale.US, " | %d°C %dMHz", telemetry.gpuTempC, telemetry.gpuClockMhz)
                    : "";
            ui.drawText(String.format(Locale.US, "VRAM: %d / %d MB%s",
                    telemetry.vramUsedMb, telemetry.vramTotalMb, extra),
                    cx, cy, 1.15f, 0.72f, 0.78f, 0.88f, 0.95f);
            cy += 13f;
            ui.drawProgressBar(cx, cy, lw - 28f, 6f, vramPct, 0.65f, 0.55f, 1.0f);
            cy += 15f;
        } else {
            cy += 6f;
        }

        // CPU & JVM Hardware Section
        ui.drawText(String.format(Locale.US, "CPU (%d CORES)  SUBMIT: %.2f ms",
                telemetry.cpuCores, cpuSubmitMs), cx, cy, 1.2f, 0.45f, 0.95f, 0.72f, 1.0f);
        cy += 15f;

        ui.drawText(String.format(Locale.US, "JVM CPU: %5.1f%%   SYS CPU: %5.1f%%",
                telemetry.processCpuPct, telemetry.systemCpuPct), cx, cy, 1.2f, 0.90f, 0.94f, 1.0f, 1.0f);
        cy += 14f;
        ui.drawProgressBar(cx, cy, lw - 28f, 7f, telemetry.processCpuPct / 100f, 0.32f, 0.92f, 0.65f);
        cy += 13f;

        ui.drawText(String.format(Locale.US, "JVM HEAP: %d / %d MB",
                telemetry.heapUsedMb, telemetry.heapMaxMb), cx, cy, 1.15f, 0.72f, 0.78f, 0.88f, 0.95f);
        cy += 18f;

        // Live 120-Frame Oscilloscope Sparkline
        float gx = cx, gy = cy, gw = lw - 28f, gh = 68f;
        ui.drawGlassPanel(gx, gy, gw, gh, 5f, 0.03f, 0.04f, 0.07f, 0.85f, 1f, 1f, 1f, 0.14f);
        ui.drawText("FRAME TIME GRAPH (GPU: CYAN | CPU: GREEN)", gx + 6f, gy + 5f, 1.05f, 0.65f, 0.72f, 0.82f, 0.85f);

        float maxScaleMs = 20.0f;
        for (int i = 0; i < HISTORY_LEN; i++) {
            int idx = (historyCursor + i) % HISTORY_LEN;
            maxScaleMs = Math.max(maxScaleMs, Math.max(gpuHistory[idx], cpuHistory[idx]) * 1.15f);
        }

        // 16.6ms (60 FPS) reference line
        float line60Y = gy + gh - 4f - (16.67f / maxScaleMs) * (gh - 20f);
        if (line60Y > gy + 16f && line60Y < gy + gh - 4f) {
            ui.drawRect(gx + 4f, line60Y, gw - 8f, 1f, 0f, 1f, 0.65f, 0.25f, 0.35f);
        }

        float stepX = (gw - 8f) / (HISTORY_LEN - 1);
        for (int i = 0; i < HISTORY_LEN - 1; i++) {
            int i0 = (historyCursor + i) % HISTORY_LEN;
            int i1 = (historyCursor + i + 1) % HISTORY_LEN;

            float x0 = gx + 4f + i * stepX;
            float x1 = gx + 4f + (i + 1) * stepX;

            float gY0 = gy + gh - 4f - Math.min(1f, gpuHistory[i0] / maxScaleMs) * (gh - 20f);
            float gY1 = gy + gh - 4f - Math.min(1f, gpuHistory[i1] / maxScaleMs) * (gh - 20f);
            ui.drawLine(x0, gY0, x1, gY1, 1.7f, 0.25f, 0.85f, 1.0f, 0.95f);

            float cY0 = gy + gh - 4f - Math.min(1f, cpuHistory[i0] / maxScaleMs) * (gh - 20f);
            float cY1 = gy + gh - 4f - Math.min(1f, cpuHistory[i1] / maxScaleMs) * (gh - 20f);
            ui.drawLine(x0, cY0, x1, cY1, 1.5f, 0.32f, 0.95f, 0.65f, 0.90f);
        }

        // ---------------------------------------------------------------------
        // RIGHT PANEL: INTERACTIVE SPACETIME & RENDERING CONTROLS
        // ---------------------------------------------------------------------
        float rw = 318f;
        float rx = windowW - rw - 16f;
        float ry = 16f;
        float rh = controlsExpanded ? 516f : 42f;

        ui.drawGlassPanel(rx, ry, rw, rh, 10f, 0.08f, 0.10f, 0.15f, 0.82f, 0.95f, 0.72f, 0.42f, 0.35f);

        float px = rx + 14f;
        float py = ry + 13f;
        ui.drawText("SPACETIME & OPTICS CONTROLS", px, py, 1.35f, 0.95f, 0.78f, 0.48f, 1.0f);

        // Collapse/Expand Button
        float btnW = 24f, btnH = 20f;
        float btnX = rx + rw - btnW - 10f, btnY = ry + 10f;
        ui.drawGlassPanel(btnX, btnY, btnW, btnH, 4f, 1f, 1f, 1f, 0.10f, 1f, 1f, 1f, 0.25f);
        ui.drawText(controlsExpanded ? "-" : "+", btnX + 8f, btnY + 5f, 1.3f, 1f, 1f, 1f, 0.9f);
        hitBoxes.add(new UiHitBox(btnX, btnY, btnW, btnH, 100, 0f, 0f));

        if (controlsExpanded) {
            py += 22f;
            ui.drawRect(px, py, rw - 28f, 1f, 0f, 1f, 1f, 1f, 0.12f);
            py += 10f;

            // 2-Column Interactive Toggle Grid
            float colW = (rw - 34f) * 0.5f;
            float rowH = 25f;

            py = drawToggleRow(px, py, colW, rowH, "[L] LENSING", lensingEnabled, 1,
                    px + colW + 6f, "[D] DISK", showDisk, 2);
            py = drawToggleRow(px, py, colW, rowH, "[B] DOPPLER", dopplerEnabled, 3,
                    px + colW + 6f, "[K] KERR SPIN", kerrSpinEnabled, 4);
            py = drawToggleRow(px, py, colW, rowH, "[M] 4X ADPT AA", adaptiveAa4x, 5,
                    px + colW + 6f, "[C] SKY GRID", showGrid, 6);
            py = drawToggleRow(px, py, colW, rowH, "[I] ORDER VIS", orderVisualizer, 7,
                    px + colW + 6f, "[O] AUTO ORBIT", autoOrbit, 8);
            py = drawToggleRow(px, py, colW, rowH, "[V] V-SYNC", vsync, 9,
                    px + colW + 6f, "[SPC] PAUSE", paused, 10);

            py += 6f;
            ui.drawRect(px, py, rw - 28f, 1f, 0f, 1f, 1f, 1f, 0.12f);
            py += 10f;

            // Interactive Physics & Optics Sliders
            float sliderW = rw - 28f;
            py = drawSlider(px, py, sliderW, "KERR SPIN (a*)", spinParam, 0.00f, 0.99f,
                    String.format(Locale.US, "%.2f", spinParam), 201);
            py = drawSlider(px, py, sliderW, "DISK TEMP (K)", diskTempScale, 0.55f, 1.65f,
                    String.format(Locale.US, "%d K", Math.round(12800f * diskTempScale)), 202);
            py = drawSlider(px, py, sliderW, "PLASMA DENSITY", diskDensity, 0.25f, 2.00f,
                    String.format(Locale.US, "%.2fx", diskDensity), 203);
            py = drawSlider(px, py, sliderW, "BLOOM GLOW", bloomStrength, 0.00f, 0.80f,
                    String.format(Locale.US, "%.2f", bloomStrength), 204);
            py = drawSlider(px, py, sliderW, "HDR EXPOSURE", exposure, 0.40f, 2.40f,
                    String.format(Locale.US, "%.2f", exposure), 205);
            py = drawSlider(px, py, sliderW, "CAMERA FOV", fovDeg, 14.0f, 40.0f,
                    String.format(Locale.US, "%.0f°", fovDeg * 2f), 206);

            py += 4f;
            ui.drawRect(px, py, rw - 28f, 1f, 0f, 1f, 1f, 1f, 0.12f);
            py += 10f;

            // Camera Preset Buttons
            ui.drawText("CAMERA PRESETS:", px, py, 1.15f, 0.72f, 0.78f, 0.88f, 0.95f);
            py += 16f;
            float pBtnW = (rw - 28f - 18f) * 0.25f;
            drawPresetButton(px, py, pBtnW, 24f, "[1] EDGE", 301);
            drawPresetButton(px + (pBtnW + 6f), py, pBtnW, 24f, "[2] POLE", 302);
            drawPresetButton(px + (pBtnW + 6f) * 2f, py, pBtnW, 24f, "[3] RING", 303);
            drawPresetButton(px + (pBtnW + 6f) * 3f, py, pBtnW, 24f, "[R] RST", 304);
        }

        // Bottom Hint Strip
        String hint = "[H] TOGGLE HUD   |   DRAG: ORBIT   |   SCROLL: ZOOM   |   [ESC] QUIT";
        float hintW = hint.length() * 8f * 1.1f + 24f;
        float hx = (windowW - hintW) * 0.5f;
        float hy = windowH - 34f;
        ui.drawGlassPanel(hx, hy, hintW, 22f, 6f, 0.06f, 0.08f, 0.12f, 0.72f, 1f, 1f, 1f, 0.15f);
        ui.drawText(hint, hx + 12f, hy + 6f, 1.1f, 0.75f, 0.80f, 0.90f, 0.85f);

        ui.end();
    }

    private float drawToggleRow(float x1, float y, float w, float h, String label1, boolean state1, int id1,
                                float x2, String label2, boolean state2, int id2) {
        drawTogglePill(x1, y, w, h, label1, state1, id1);
        drawTogglePill(x2, y, w, h, label2, state2, id2);
        return y + h + 6f;
    }

    private void drawTogglePill(float x, float y, float w, float h, String label, boolean active, int id) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        float bgR = active ? 0.16f : 0.12f;
        float bgG = active ? 0.34f : 0.14f;
        float bgB = active ? 0.48f : 0.19f;
        float bgA = hovered ? 0.92f : 0.75f;

        float bdR = active ? 0.35f : 0.55f;
        float bdG = active ? 0.82f : 0.60f;
        float bdB = active ? 1.00f : 0.70f;
        float bdA = active ? 0.75f : 0.22f;

        ui.drawGlassPanel(x, y, w, h, 5f, bgR, bgG, bgB, bgA, bdR, bdG, bdB, bdA);
        ui.drawText(label, x + 8f, y + 8f, 1.1f,
                active ? 0.95f : 0.68f,
                active ? 0.98f : 0.72f,
                active ? 1.00f : 0.80f, 1.0f);
        hitBoxes.add(new UiHitBox(x, y, w, h, id, 0f, 0f));
    }

    private float drawSlider(float x, float y, float w, String label, float val, float min, float max, String valText, int id) {
        ui.drawText(label, x, y, 1.15f, 0.78f, 0.84f, 0.92f, 0.95f);
        float valW = valText.length() * 8f * 1.15f;
        ui.drawText(valText, x + w - valW, y, 1.15f, 0.95f, 0.78f, 0.48f, 1.0f);

        float trackY = y + 15f;
        float trackH = 6f;
        float norm = Math.max(0f, Math.min(1f, (val - min) / (max - min)));

        ui.drawRect(x, trackY, w, trackH, 3f, 1f, 1f, 1f, 0.14f);
        ui.drawRect(x, trackY, Math.max(4f, w * norm), trackH, 3f, 0.95f, 0.68f, 0.32f, 0.92f);

        float thumbX = x + w * norm - 5f;
        ui.drawRect(thumbX, trackY - 3f, 10f, 12f, 4f, 1f, 1f, 1f, 0.98f);

        hitBoxes.add(new UiHitBox(x - 4f, y, w + 8f, 26f, id, min, max));
        return y + 31f;
    }

    private void drawPresetButton(float x, float y, float w, float h, String label, int id) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        ui.drawGlassPanel(x, y, w, h, 5f,
                hovered ? 0.24f : 0.14f,
                hovered ? 0.28f : 0.17f,
                hovered ? 0.38f : 0.24f,
                0.85f, 0.95f, 0.75f, 0.45f, hovered ? 0.75f : 0.30f);
        float textW = label.length() * 8f * 1.05f;
        ui.drawText(label, x + (w - textW) * 0.5f, y + 7f, 1.05f, 0.95f, 0.95f, 0.98f, 1.0f);
        hitBoxes.add(new UiHitBox(x, y, w, h, id, 0f, 0f));
    }

    private boolean handleUiClick(double mx, double my) {
        if (!showUi) return false;
        for (UiHitBox box : hitBoxes) {
            if (mx >= box.x && mx <= box.x + box.w && my >= box.y && my <= box.y + box.h) {
                if (box.id >= 200 && box.id < 300) {
                    activeSliderId = box.id;
                    updateSliderFromMouse(box, mx);
                } else {
                    triggerUiAction(box.id);
                }
                return true;
            }
        }
        return false;
    }

    private void updateSliderFromMouse(UiHitBox box, double mx) {
        float t = (float) Math.max(0.0, Math.min(1.0, (mx - (box.x + 4f)) / (box.w - 8f)));
        float v = box.min + t * (box.max - box.min);
        if (box.id == 201) {
            spinParam = v;
            kerrSpinEnabled = v > 0.01f;
        } else if (box.id == 202) {
            diskTempScale = v;
        } else if (box.id == 203) {
            diskDensity = v;
        } else if (box.id == 204) {
            bloomStrength = v;
        } else if (box.id == 205) {
            exposure = v;
        } else if (box.id == 206) {
            fovDeg = v;
        }
    }

    private void triggerUiAction(int id) {
        switch (id) {
            case 1 -> lensingEnabled = !lensingEnabled;
            case 2 -> showDisk = !showDisk;
            case 3 -> dopplerEnabled = !dopplerEnabled;
            case 4 -> kerrSpinEnabled = !kerrSpinEnabled;
            case 5 -> adaptiveAa4x = !adaptiveAa4x;
            case 6 -> showGrid = !showGrid;
            case 7 -> orderVisualizer = !orderVisualizer;
            case 8 -> autoOrbit = !autoOrbit;
            case 9 -> {
                vsync = !vsync;
                glfwSwapInterval(vsync ? 1 : 0);
            }
            case 10 -> paused = !paused;
            case 100 -> controlsExpanded = !controlsExpanded;
            case 301 -> {
                autoOrbit = false;
                targetDist = 17.5; targetYaw = 0.26; targetPitch = 0.135; targetRoll = -0.17;
            }
            case 302 -> {
                autoOrbit = false;
                targetDist = 19.0; targetYaw = 0.0; targetPitch = 1.25; targetRoll = 0.0;
            }
            case 303 -> {
                autoOrbit = false;
                targetDist = 6.2; targetYaw = 0.42; targetPitch = 0.07; targetRoll = -0.12;
            }
            case 304 -> resetAll();
        }
    }

    private void resetAll() {
        autoOrbit = false;
        lensingEnabled = true;
        showDisk = true;
        showGrid = false;
        orderVisualizer = false;
        dopplerEnabled = true;
        kerrSpinEnabled = true;
        adaptiveAa4x = true;
        spinParam = 0.94f;
        diskTempScale = 1.00f;
        diskDensity = 1.00f;
        exposure = 1.12f;
        bloomStrength = 0.32f;
        fovDeg = 22.5f;
        targetDist = 17.5; targetYaw = 0.26; targetPitch = 0.135; targetRoll = -0.17;
    }

    // =========================================================================
    // GPU PRECOMPUTATION & FRAMEBUFFER SETUP
    // =========================================================================
    private static int bake3dNoiseVolume(int noiseProg, int size) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, tex);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R16F, size, size, size, 0, GL_RED, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT);

        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, size, size);
        glUseProgram(noiseProg);
        glUniform1f(glGetUniformLocation(noiseProg, "uSize"), (float) size);
        int uSliceZ = glGetUniformLocation(noiseProg, "uSliceZ");

        for (int z = 0; z < size; z++) {
            glFramebufferTexture3D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_3D, tex, 0, z);
            glUniform1f(uSliceZ, (z + 0.5f) / size);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(fbo);
        return tex;
    }

    private static int buildHdrSkyboxCubemap(int skyProg, int noiseVolTex, int faceSize) {
        int cubemap = glGenTextures();
        glBindTexture(GL_TEXTURE_CUBE_MAP, cubemap);
        for (int face = 0; face < 6; face++) {
            glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL_RGBA16F,
                    faceSize, faceSize, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        }
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, faceSize, faceSize);
        glUseProgram(skyProg);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_3D, noiseVolTex);
        glUniform1i(glGetUniformLocation(skyProg, "uNoiseVol"), 0);
        glUniform1f(glGetUniformLocation(skyProg, "uFaceSize"), (float) faceSize);
        int uFace = glGetUniformLocation(skyProg, "uFace");

        for (int face = 0; face < 6; face++) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, cubemap, 0);
            glUniform1i(uFace, face);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(fbo);
        return cubemap;
    }

    private void allocateFramebuffers(int w, int h) {
        cleanupFramebuffers();

        hdrFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, hdrFbo);
        hdrColorTex = createHdrTexture(w, h);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, hdrColorTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("HDR Framebuffer incomplete");
        }

        int bw = w;
        int bh = h;
        for (int i = 0; i < BLOOM_LEVELS; i++) {
            bw = Math.max(1, bw / 2);
            bh = Math.max(1, bh / 2);
            bloomW[i] = bw;
            bloomH[i] = bh;

            bloomFbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo[i]);
            bloomTex[i] = createHdrTexture(bw, bh);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, bloomTex[i], 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Bloom mip framebuffer " + i + " incomplete");
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void cleanupFramebuffers() {
        if (hdrFbo != 0) glDeleteFramebuffers(hdrFbo);
        if (hdrColorTex != 0) glDeleteTextures(hdrColorTex);
        for (int i = 0; i < BLOOM_LEVELS; i++) {
            if (bloomFbo[i] != 0) glDeleteFramebuffers(bloomFbo[i]);
            if (bloomTex[i] != 0) glDeleteTextures(bloomTex[i]);
        }
    }

    private static int createHdrTexture(int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return tex;
    }

    // =========================================================================
    // INPUT CALLBACKS
    // =========================================================================
    private void setupCallbacks(long window) {
        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            if (w > 0 && h > 0) {
                windowW = w;
                windowH = h;
                framebufferResized = true;
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                double[] px = new double[1], py = new double[1];
                glfwGetCursorPos(win, px, py);
                mouseX = px[0];
                mouseY = py[0];

                if (action == GLFW_PRESS) {
                    mouseDown = true;
                    if (!handleUiClick(mouseX, mouseY)) {
                        draggingCamera = true;
                        autoOrbit = false;
                        anchorX = mouseX;
                        anchorY = mouseY;
                    }
                } else if (action == GLFW_RELEASE) {
                    mouseDown = false;
                    draggingCamera = false;
                    activeSliderId = -1;
                }
            }
        });

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            mouseX = xpos;
            mouseY = ypos;

            if (mouseDown && activeSliderId != -1) {
                for (UiHitBox box : hitBoxes) {
                    if (box.id == activeSliderId) {
                        updateSliderFromMouse(box, xpos);
                        break;
                    }
                }
                return;
            }

            if (draggingCamera) {
                double dx = xpos - anchorX;
                double dy = ypos - anchorY;
                targetYaw -= dx * 0.0055;
                targetPitch = Math.max(-1.48, Math.min(1.48, targetPitch + dy * 0.0055));
                anchorX = xpos;
                anchorY = ypos;
            }
        });

        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            targetDist = Math.max(2.6, Math.min(34.0, targetDist - yoffset * 0.90));
        });

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (action != GLFW_PRESS) return;
            if (key == GLFW_KEY_ESCAPE) {
                glfwSetWindowShouldClose(win, true);
            } else if (key == GLFW_KEY_H) {
                showUi = !showUi;
            } else if (key == GLFW_KEY_1) {
                triggerUiAction(301);
            } else if (key == GLFW_KEY_2) {
                triggerUiAction(302);
            } else if (key == GLFW_KEY_3) {
                triggerUiAction(303);
            } else if (key == GLFW_KEY_4 || key == GLFW_KEY_O) {
                autoOrbit = !autoOrbit;
            } else if (key == GLFW_KEY_L) {
                lensingEnabled = !lensingEnabled;
            } else if (key == GLFW_KEY_D) {
                showDisk = !showDisk;
            } else if (key == GLFW_KEY_C) {
                showGrid = !showGrid;
            } else if (key == GLFW_KEY_I) {
                orderVisualizer = !orderVisualizer;
            } else if (key == GLFW_KEY_M) {
                adaptiveAa4x = !adaptiveAa4x;
            } else if (key == GLFW_KEY_B) {
                dopplerEnabled = !dopplerEnabled;
            } else if (key == GLFW_KEY_K) {
                kerrSpinEnabled = !kerrSpinEnabled;
            } else if (key == GLFW_KEY_G) {
                bloomStrength = (bloomStrength > 0.45f) ? 0.0f : (bloomStrength < 0.1f ? 0.32f : 0.62f);
            } else if (key == GLFW_KEY_LEFT_BRACKET) {
                exposure = Math.max(0.4f, exposure - 0.15f);
            } else if (key == GLFW_KEY_RIGHT_BRACKET) {
                exposure = Math.min(2.4f, exposure + 0.15f);
            } else if (key == GLFW_KEY_T) {
                targetRoll = (Math.abs(targetRoll) > 0.01) ? 0.0 : -0.17;
            } else if (key == GLFW_KEY_V) {
                vsync = !vsync;
                glfwSwapInterval(vsync ? 1 : 0);
            } else if (key == GLFW_KEY_R) {
                resetAll();
            } else if (key == GLFW_KEY_SPACE) {
                paused = !paused;
            }
        });
    }

    private static int createShaderProgram(String vertSrc, String fragSrc) {
        int vs = compileShader(GL_VERTEX_SHADER, vertSrc);
        int fs = compileShader(GL_FRAGMENT_SHADER, fragSrc);

        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader link error:\n" + glGetProgramInfoLog(program));
        }

        glDeleteShader(vs);
        glDeleteShader(fs);
        return program;
    }

    private static int compileShader(int type, String src) {
        int shader = glCreateShader(type);
        glShaderSource(shader, src);
        glCompileShader(shader);

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader compile error:\n" + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    // =========================================================================
    // REAL-TIME CPU & GPU HARDWARE TELEMETRY MONITOR
    // =========================================================================
    private static class HardwareTelemetry {
        @SuppressWarnings("unused")
        String gpuFullName = "GPU";
        String gpuShortName = "GPU";
        final int cpuCores = Runtime.getRuntime().availableProcessors();

        volatile float processCpuPct = 0f;
        volatile float systemCpuPct = 0f;
        volatile long heapUsedMb = 0;
        volatile long heapMaxMb = 0;

        volatile int hardwareGpuUtilPct = -1;
        volatile int vramUsedMb = -1;
        volatile int vramTotalMb = -1;
        volatile int gpuTempC = -1;
        volatile int gpuClockMhz = -1;

        private volatile boolean running = true;
        private Thread samplerThread;

        void initOpenGlInfo(String renderer, String version) {
            if (renderer != null) {
                this.gpuFullName = renderer;
                String clean = renderer.replace("NVIDIA GeForce ", "")
                                       .replace("AMD Radeon ", "")
                                       .replace("/PCIe/SSE2", "")
                                       .trim();
                this.gpuShortName = clean.length() > 24 ? clean.substring(0, 24) : clean;
            }
            pollOpenGlVram();
        }

        void pollOpenGlVram() {
            // NVIDIA GL_NVX_gpu_memory_info extension constants
            int[] totalKb = new int[1];
            int[] availKb = new int[1];
            glGetIntegerv(0x9048, totalKb);
            glGetIntegerv(0x9049, availKb);
            if (glGetError() == GL_NO_ERROR && totalKb[0] > 0) {
                vramTotalMb = totalKb[0] / 1024;
                vramUsedMb = Math.max(0, (totalKb[0] - availKb[0]) / 1024);
            }
        }

        float getGpuLoadPercent(float gpuFrameMs, float wallFrameMs, boolean vsync) {
            if (hardwareGpuUtilPct >= 0) {
                return hardwareGpuUtilPct;
            }
            float budget = vsync ? Math.max(6.94f, wallFrameMs) : Math.max(0.5f, wallFrameMs);
            return Math.min(100f, Math.max(1f, (gpuFrameMs / budget) * 100f));
        }

        void startBackgroundSampler() {
            samplerThread = new Thread(() -> {
                OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
                com.sun.management.OperatingSystemMXBean sunOsBean =
                        (osBean instanceof com.sun.management.OperatingSystemMXBean s) ? s : null;

                boolean nvidiaSmiAvailable = true;
                int smiDivider = 0;

                while (running) {
                    try {
                        Runtime rt = Runtime.getRuntime();
                        heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                        heapMaxMb = rt.maxMemory() / (1024 * 1024);

                        if (sunOsBean != null) {
                            double proc = sunOsBean.getProcessCpuLoad();
                            if (proc >= 0.0) processCpuPct = (float) (proc * 100.0);

                            double sys = sunOsBean.getCpuLoad();
                            if (sys >= 0.0) systemCpuPct = (float) (sys * 100.0);
                        }

                        if (nvidiaSmiAvailable && (smiDivider++ % 2 == 0)) {
                            Process p = new ProcessBuilder(
                                    "nvidia-smi",
                                    "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu,clocks.current.graphics",
                                    "--format=csv,noheader,nounits"
                            ).redirectErrorStream(true).start();

                            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                                String line = br.readLine();
                                if (line != null && line.contains(",")) {
                                    String[] parts = line.split(",");
                                    if (parts.length >= 5) {
                                        hardwareGpuUtilPct = Integer.parseInt(parts[0].trim());
                                        vramUsedMb = Integer.parseInt(parts[1].trim());
                                        vramTotalMb = Integer.parseInt(parts[2].trim());
                                        gpuTempC = Integer.parseInt(parts[3].trim());
                                        gpuClockMhz = Integer.parseInt(parts[4].trim());
                                    }
                                } else {
                                    nvidiaSmiAvailable = false;
                                }
                            }
                            p.waitFor();
                        }

                        Thread.sleep(350);
                    } catch (Exception ignored) {
                        nvidiaSmiAvailable = false;
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
            }, "Hardware-Telemetry-Sampler");
            samplerThread.setDaemon(true);
            samplerThread.start();
        }

        void stop() {
            running = false;
            if (samplerThread != null) samplerThread.interrupt();
        }
    }

    private record UiHitBox(float x, float y, float w, float h, int id, float min, float max) {}

    // =========================================================================
    // SINGLE-BATCH SDF ROUNDED UI & BITMAP FONT RENDERER
    // =========================================================================
    private static class UiRenderer {
        private static final int MAX_QUADS = 4096;
        private static final int FLOATS_PER_VERT = 14; // pos(2) + uv(2) + color(4) + rect(4) + params(2)

        private final int program;
        private final int uResolutionLoc;
        private final int uFontTexLoc;
        private final int vao;
        private final int vbo;
        private final int fontTex;
        private final FloatBuffer vertexBuffer;
        private int quadCount = 0;

        UiRenderer(int program) {
            this.program = program;
            this.uResolutionLoc = glGetUniformLocation(program, "uResolution");
            this.uFontTexLoc = glGetUniformLocation(program, "uFontTex");

            this.vao = glGenVertexArrays();
            this.vbo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, (long) MAX_QUADS * 6 * FLOATS_PER_VERT * Float.BYTES, GL_DYNAMIC_DRAW);

            int stride = FLOATS_PER_VERT * Float.BYTES;
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
            glEnableVertexAttribArray(3);
            glVertexAttribPointer(3, 4, GL_FLOAT, false, stride, 8L * Float.BYTES);
            glEnableVertexAttribArray(4);
            glVertexAttribPointer(4, 2, GL_FLOAT, false, stride, 12L * Float.BYTES);

            glBindVertexArray(0);

            this.vertexBuffer = MemoryUtil.memAllocFloat(MAX_QUADS * 6 * FLOATS_PER_VERT);
            this.fontTex = createBitmapFontTexture();
        }

        void begin(int width, int height) {
            vertexBuffer.clear();
            quadCount = 0;
            glUseProgram(program);
            glUniform2f(uResolutionLoc, width, height);
            glUniform1i(uFontTexLoc, 0);
        }

        void end() {
            if (quadCount == 0) return;
            vertexBuffer.flip();

            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, fontTex);

            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);
            glDrawArrays(GL_TRIANGLES, 0, quadCount * 6);

            glBindVertexArray(0);
            glDisable(GL_BLEND);
        }

        void drawGlassPanel(float x, float y, float w, float h, float radius,
                            float bgR, float bgG, float bgB, float bgA,
                            float bdR, float bdG, float bdB, float bdA) {
            pushRoundedQuad(x, y, w, h, radius, 0f, bgR, bgG, bgB, bgA);
            pushRoundedQuad(x, y, w, h, radius, 1f, bdR, bdG, bdB, bdA);
        }

        void drawRect(float x, float y, float w, float h, float radius, float r, float g, float b, float a) {
            pushRoundedQuad(x, y, w, h, radius, 0f, r, g, b, a);
        }

        void drawProgressBar(float x, float y, float w, float h, float pct, float r, float g, float b) {
            float clamped = Math.max(0f, Math.min(1f, pct));
            pushRoundedQuad(x, y, w, h, h * 0.5f, 0f, 1f, 1f, 1f, 0.12f);
            if (clamped > 0.005f) {
                pushRoundedQuad(x, y, Math.max(h, w * clamped), h, h * 0.5f, 0f, r, g, b, 0.92f);
            }
        }

        void drawLine(float x0, float y0, float x1, float y1, float thickness, float r, float g, float b, float a) {
            if (quadCount >= MAX_QUADS) return;
            float dx = x1 - x0;
            float dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.01f) return;
            float nx = (-dy / len) * (thickness * 0.5f);
            float ny = (dx / len) * (thickness * 0.5f);

            putVert(x0 + nx, y0 + ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);
            putVert(x0 - nx, y0 - ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);
            putVert(x1 - nx, y1 - ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);

            putVert(x0 + nx, y0 + ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);
            putVert(x1 - nx, y1 - ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);
            putVert(x1 + nx, y1 + ny, 0f, 0f, r, g, b, a, 0f, 0f, 1000f, 1000f, 0f, 0f);
            quadCount++;
        }

        void drawText(String text, float x, float y, float scale, float r, float g, float b, float a) {
            float cursorX = x;
            float glyphW = 8f * scale;
            float glyphH = 8f * scale;

            for (int i = 0; i < text.length(); i++) {
                if (quadCount >= MAX_QUADS) break;
                char ch = text.charAt(i);
                int idx = (ch == '°') ? 95 : (ch >= 32 && ch <= 126 ? ch - 32 : 0);

                if (idx != 0) {
                    float u0 = idx / 96.0f;
                    float u1 = (idx + 1) / 96.0f;
                    float x0 = cursorX, y0 = y;
                    float x1 = cursorX + glyphW, y1 = y + glyphH;

                    putVert(x0, y0, u0, 0f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                    putVert(x0, y1, u0, 1f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                    putVert(x1, y1, u1, 1f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);

                    putVert(x0, y0, u0, 0f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                    putVert(x1, y1, u1, 1f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                    putVert(x1, y0, u1, 0f, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 2f);
                    quadCount++;
                }
                cursorX += glyphW;
            }
        }

        private void pushRoundedQuad(float x, float y, float w, float h, float radius, float mode,
                                     float r, float g, float b, float a) {
            if (quadCount >= MAX_QUADS) return;
            float pad = 1.5f;
            float x0 = x - pad, y0 = y - pad;
            float x1 = x + w + pad, y1 = y + h + pad;
            float cx = x + w * 0.5f;
            float cy = y + h * 0.5f;
            float hw = w * 0.5f;
            float hh = h * 0.5f;

            putVert(x0, y0, 0f, 0f, r, g, b, a, cx, cy, hw, hh, radius, mode);
            putVert(x0, y1, 0f, 1f, r, g, b, a, cx, cy, hw, hh, radius, mode);
            putVert(x1, y1, 1f, 1f, r, g, b, a, cx, cy, hw, hh, radius, mode);

            putVert(x0, y0, 0f, 0f, r, g, b, a, cx, cy, hw, hh, radius, mode);
            putVert(x1, y1, 1f, 1f, r, g, b, a, cx, cy, hw, hh, radius, mode);
            putVert(x1, y0, 1f, 0f, r, g, b, a, cx, cy, hw, hh, radius, mode);
            quadCount++;
        }

        private void putVert(float px, float py, float u, float v,
                             float r, float g, float b, float a,
                             float cx, float cy, float hw, float hh,
                             float radius, float mode) {
            vertexBuffer.put(px).put(py)
                        .put(u).put(v)
                        .put(r).put(g).put(b).put(a)
                        .put(cx).put(cy).put(hw).put(hh)
                        .put(radius).put(mode);
        }

        void cleanup() {
            MemoryUtil.memFree(vertexBuffer);
            glDeleteTextures(fontTex);
            glDeleteBuffers(vbo);
            glDeleteVertexArrays(vao);
        }

        // Crisp 8x8 ASCII Bitmap Font Atlas (96 Glyphs: ASCII 32..126 + Degree Symbol)
        private static int createBitmapFontTexture() {
            String[] glyphs = new String[96];
            for (int i = 0; i < 96; i++) glyphs[i] = "......../......../......../......../......../......../......../........";

            glyphs['!' - 32] = "...##.../..####../..####../...##.../...##.../......../...##.../........";
            glyphs['%' - 32] = "##...##./##..##../....##../...##.../..##..../.##..##./##...##./........";
            glyphs['(' - 32] = "....##../...##.../..##..../..##..../..##..../...##.../....##../........";
            glyphs[')' - 32] = "..##..../...##.../....##../....##../....##../...##.../..##..../........";
            glyphs['*' - 32] = "......../..#..#../...##.../########/...##.../..#..#../......../........";
            glyphs['+' - 32] = "......../...##.../..######/..######/...##.../......../......../........";
            glyphs[',' - 32] = "......../......../......../......../......../...##.../..##..../........";
            glyphs['-' - 32] = "......../......../......../.######./.######./......../......../........";
            glyphs['.' - 32] = "......../......../......../......../......../..###.../..###.../........";
            glyphs['/' - 32] = "......##/.....##./....##../...##.../..##..../.##...../##....../........";

            glyphs['0' - 32] = ".######./##...###/##..####/##.##.##/####..##/###...##/.######./........";
            glyphs['1' - 32] = "...##.../..###.../.####.../...##.../...##.../...##.../.######./........";
            glyphs['2' - 32] = ".######./##....##/.....##./..####../.##...../##....../########/........";
            glyphs['3' - 32] = ".######./##....##/.....##./..#####./.....##./##....##/.######./........";
            glyphs['4' - 32] = "....###./...####./..##.##./.##..##./########/.....##./.....##./........";
            glyphs['5' - 32] = "########/##....../#######./.....##./.....##./##....##/.######./........";
            glyphs['6' - 32] = "..#####./.##...../##....../#######./##....##/##....##/.######./........";
            glyphs['7' - 32] = "########/##....##/....##../...##.../..##..../..##..../..##..../........";
            glyphs['8' - 32] = ".######./##....##/##....##/.######./##....##/##....##/.######./........";
            glyphs['9' - 32] = ".######./##....##/##....##/.#######/.....##./....##../.#####../........";

            glyphs[':' - 32] = "......../..###.../..###.../......../..###.../..###.../......../........";
            glyphs['=' - 32] = "......../......../.######./......../.######./......../......../........";
            glyphs['[' - 32] = "..####../..##..../..##..../..##..../..##..../..##..../..####../........";
            glyphs[']' - 32] = "..####../....##../....##../....##../....##../....##../..####../........";
            glyphs['|' - 32] = "...##.../...##.../...##.../...##.../...##.../...##.../...##.../........";
            glyphs[95]       = "..###.../##...##./##...##./..###.../......../......../......../........"; // Degree symbol

            glyphs['A' - 32] = "..####../.##..##./##....##/########/##....##/##....##/##....##/........";
            glyphs['B' - 32] = "#######./##....##/##....##/#######./##....##/##....##/#######./........";
            glyphs['C' - 32] = ".######./##....##/##....../##....../##....../##....##/.######./........";
            glyphs['D' - 32] = "######../##...##./##....##/##....##/##....##/##...##./######../........";
            glyphs['E' - 32] = "########/##....../##....../######../##....../##....../########/........";
            glyphs['F' - 32] = "########/##....../##....../######../##....../##....../##....../........";
            glyphs['G' - 32] = ".######./##....##/##....../##..####/##....##/##....##/.######./........";
            glyphs['H' - 32] = "##....##/##....##/##....##/########/##....##/##....##/##....##/........";
            glyphs['I' - 32] = ".######./...##.../...##.../...##.../...##.../...##.../.######./........";
            glyphs['J' - 32] = "..######/.....##./.....##./.....##./##...##./##...##./.#####../........";
            glyphs['K' - 32] = "##...##./##..##../##.##.../####..../##.##.../##..##../##...##./........";
            glyphs['L' - 32] = "##....../##....../##....../##....../##....../##....../########/........";
            glyphs['M' - 32] = "##....##/###..###/########/##.##.##/##....##/##....##/##....##/........";
            glyphs['N' - 32] = "##....##/###...##/####..##/##.##.##/##..####/##...###/##....##/........";
            glyphs['O' - 32] = ".######./##....##/##....##/##....##/##....##/##....##/.######./........";
            glyphs['P' - 32] = "#######./##....##/##....##/#######./##....../##....../##....../........";
            glyphs['Q' - 32] = ".######./##....##/##....##/##....##/##.##.##/##..###./.#####.#/........";
            glyphs['R' - 32] = "#######./##....##/##....##/#######./##..##../##...##./##....##/........";
            glyphs['S' - 32] = ".######./##....##/##....../.######./.....##./##....##/.######./........";
            glyphs['T' - 32] = "########/...##.../...##.../...##.../...##.../...##.../...##.../........";
            glyphs['U' - 32] = "##....##/##....##/##....##/##....##/##....##/##....##/.######./........";
            glyphs['V' - 32] = "##....##/##....##/##....##/##....##/.##..##./..####../...##.../........";
            glyphs['W' - 32] = "##....##/##....##/##....##/##.##.##/########/###..###/##....##/........";
            glyphs['X' - 32] = "##....##/.##..##./..####../...##.../..####../.##..##./##....##/........";
            glyphs['Y' - 32] = "##....##/.##..##./..####../...##.../...##.../...##.../...##.../........";
            glyphs['Z' - 32] = "########/.....##./....##../...##.../..##..../.##...../########/........";

            glyphs['a' - 32] = "......../......../.######./.....##./.#######/##....##/.#######/........";
            glyphs['c' - 32] = "......../......../.######./##....../##....../##....##/.######./........";
            glyphs['d' - 32] = ".....##./.....##./.#######/##....##/##....##/##....##/.#######/........";
            glyphs['e' - 32] = "......../......../.######./##....##/########/##....../.######./........";
            glyphs['g' - 32] = "......../......../.#######/##....##/.#######/.....##./.######./........";
            glyphs['i' - 32] = "...##.../......../..###.../...##.../...##.../...##.../.######./........";
            glyphs['k' - 32] = "##....../##....../##..##../##.##.../####..../##.##.../##..##../........";
            glyphs['l' - 32] = "..###.../...##.../...##.../...##.../...##.../...##.../.######./........";
            glyphs['m' - 32] = "......../......../###.###./##.##.##/##.##.##/##....##/##....##/........";
            glyphs['n' - 32] = "......../......../#######./##....##/##....##/##....##/##....##/........";
            glyphs['o' - 32] = "......../......../.######./##....##/##....##/##....##/.######./........";
            glyphs['p' - 32] = "......../......../#######./##....##/#######./##....../##....../........";
            glyphs['r' - 32] = "......../......../##.###../###..##./##....../##....../##....../........";
            glyphs['s' - 32] = "......../......../.######./##....../.######./.....##./.######./........";
            glyphs['t' - 32] = "..##..../..##..../.######./..##..../..##..../..##..##/..####../........";
            glyphs['u' - 32] = "......../......../##....##/##....##/##....##/##...###/.#####.#/........";
            glyphs['x' - 32] = "......../......../##....##/.##..##./..####../.##..##./##....##/........";
            glyphs['y' - 32] = "......../......../##....##/##....##/.#######/.....##./.######./........";
            glyphs['z' - 32] = "......../......../########/....##../..##..../.##...../########/........";

            int atlasW = 96 * 8;
            int atlasH = 8;
            ByteBuffer pixels = MemoryUtil.memAlloc(atlasW * atlasH);

            for (int g = 0; g < 96; g++) {
                String[] rows = glyphs[g].split("/");
                for (int y = 0; y < 8; y++) {
                    String row = y < rows.length ? rows[y] : "........";
                    for (int x = 0; x < 8; x++) {
                        boolean on = x < row.length() && row.charAt(x) == '#';
                        pixels.put(y * atlasW + g * 8 + x, (byte) (on ? 255 : 0));
                    }
                }
            }

            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, atlasW, atlasH, 0, GL_RED, GL_UNSIGNED_BYTE, pixels);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            MemoryUtil.memFree(pixels);
            return tex;
        }
    }

    public static void main(String[] args) {
        new App().run();
    }
}