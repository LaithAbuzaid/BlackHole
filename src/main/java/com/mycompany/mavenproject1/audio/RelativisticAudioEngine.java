package com.mycompany.mavenproject1.audio;

import com.mycompany.mavenproject1.core.CameraController;
import com.mycompany.mavenproject1.core.SimulationState;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Zero-asset procedural relativistic soundscape streamed via OpenAL Soft.
 *
 * <p>Synthesizes real-time 44.1 kHz 16-bit stereo PCM audio on a dedicated daemon
 * thread driven by lock-free simulation physics snapshots:
 * <ul>
 *   <li>Gravitational redshift sub-bass drone tracking {@code sqrt(1 - r_+ / r_cam)}</li>
 *   <li>Doppler-panned filtered stereo accretion disk plasma roar</li>
 *   <li>High-energy crackling plasma swell during active TDE tidal shredding</li>
 *   <li>Ethereal harmonic wormhole chord sweeping in pitch and stereo width across {@code l = 0}</li>
 * </ul>
 * Falls back gracefully to a silent no-op if no OpenAL audio device is available.</p>
 */
public final class RelativisticAudioEngine implements AutoCloseable {

    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_COUNT = 4;
    private static final int FRAMES_PER_BUFFER = 2048;
    private static final double[] WH_RATIOS = {1.0, 1.5, 2.004, 2.25};

    private long device = NULL;
    private long context = NULL;
    private int source = 0;
    private final int[] buffers = new int[BUFFER_COUNT];
    private boolean available = false;

    private volatile boolean running = false;
    private Thread synthThread;

    // Lock-free physics parameter snapshot updated from the render loop (zero allocations)
    private volatile boolean enabled = true;
    private volatile float masterVolume = 0.60f;
    private volatile boolean wormholeMode = false;
    private volatile boolean paused = false;
    private volatile float gravRedshift = 0.95f;
    private volatile float diskEnergy = 1.0f;
    private volatile float dopplerPan = -0.35f; // -1 left (approaching side), +1 right
    private volatile float tdeShearEnergy = 0.0f;
    private volatile float wormholeL = 13.5f;

    public void start() {
        try {
            device = alcOpenDevice((ByteBuffer) null);
            if (device == NULL) {
                System.out.println("[Audio] No OpenAL audio device found; running silently.");
                return;
            }
            ALCCapabilities deviceCaps = ALC.createCapabilities(device);
            context = alcCreateContext(device, (IntBuffer) null);
            if (context == NULL || !alcMakeContextCurrent(context)) {
                System.out.println("[Audio] Could not initialize OpenAL context; running silently.");
                cleanupOpenAl();
                return;
            }
            AL.createCapabilities(deviceCaps);

            source = alGenSources();
            alSourcef(source, AL_GAIN, 1.0f);
            alGenBuffers(buffers);
            available = true;
            running = true;

            synthThread = new Thread(this::streamLoop, "Relativistic-Audio-Synth");
            synthThread.setDaemon(true);
            synthThread.start();
            System.out.println("[Audio] OpenAL Soft procedural relativistic synth active (44.1 kHz stereo).");
        } catch (Throwable t) {
            System.out.println("[Audio] OpenAL unavailable (" + t.getMessage() + "); running silently.");
            cleanupOpenAl();
        }
    }

    /** Zero-allocation render-loop physics update. */
    public void updatePhysics(SimulationState state, CameraController camera, float tdeShear) {
        if (!available) {
            return;
        }
        this.enabled = state.audioEnabled;
        this.masterVolume = state.audioVolume;
        this.wormholeMode = state.wormholeMode;
        this.paused = state.paused;
        this.tdeShearEnergy = tdeShear;
        this.wormholeL = (float) camera.currentWormholeL();

        double rCam = Math.max(0.25, camera.currentKerrRadius());
        double rPlus = state.outerHorizonRadius();
        double lapse = Math.sqrt(Math.max(0.08, 1.0 - Math.min(0.92, rPlus / Math.max(rPlus * 1.04, rCam))));
        this.gravRedshift = (float) lapse;

        if (state.showDisk) {
            float proximity = (float) Math.min(1.8, 22.0 / Math.max(5.0, rCam));
            this.diskEnergy = state.diskDensity * state.diskTempScale * proximity;
        } else {
            this.diskEnergy = 0.0f;
        }

        // Approaching Doppler side lies to the left (-X in screen space) when Kerr prograde disk is viewed edge-on
        this.dopplerPan = state.dopplerEnabled ? -0.45f * (0.4f + 0.6f * state.effectiveSpin()) : 0.0f;
    }

    public boolean isAvailable() {
        return available;
    }

    private void streamLoop() {
        ShortBuffer pcmBuf = MemoryUtil.memAllocShort(FRAMES_PER_BUFFER * 2);
        int[] unqueued = new int[1];

        // Continuous oscillator & filter state
        double subPhase = 0.0;
        double subHarmPhase = 0.0;
        double lfoPhase = 0.0;
        double[] whPhases = new double[4];

        // Stereo pink/bandpass noise filter states
        double lpL = 0.0, lpR = 0.0;
        double bpL = 0.0, bpR = 0.0;
        double crackleEnv = 0.0;
        long rng = 0x9E3779B97F4A7C15L;

        // Smoothed audio parameters to guarantee click-free transitions
        double smGain = 0.0;
        double smWhMix = 0.0;
        double smRedshift = 0.95;
        double smDisk = 1.0;
        double smPan = -0.35;
        double smTde = 0.0;
        double smWhL = 13.5;

        try {
            // Prime initial queue
            for (int i = 0; i < BUFFER_COUNT; i++) {
                fillBuffer(pcmBuf, whPhases,
                        subPhase, subHarmPhase, lfoPhase,
                        lpL, lpR, bpL, bpR, crackleEnv, rng,
                        smGain, smWhMix, smRedshift, smDisk, smPan, smTde, smWhL);
                alBufferData(buffers[i], AL_FORMAT_STEREO16, pcmBuf, SAMPLE_RATE);
                alSourceQueueBuffers(source, buffers[i]);
            }
            alSourcePlay(source);

            final double invSr = 1.0 / SAMPLE_RATE;
            final double twoPi = 2.0 * Math.PI;

            while (running) {
                int processed = alGetSourcei(source, AL_BUFFERS_PROCESSED);
                if (processed <= 0) {
                    Thread.sleep(10);
                    continue;
                }

                double targetGain = (enabled && !paused) ? masterVolume : 0.0;
                double targetWhMix = wormholeMode ? 1.0 : 0.0;
                double targetRedshift = gravRedshift;
                double targetDisk = diskEnergy;
                double targetPan = dopplerPan;
                double targetTde = tdeShearEnergy;
                double targetWhL = wormholeL;

                while (processed-- > 0 && running) {
                    alSourceUnqueueBuffers(source, unqueued);
                    int bufId = unqueued[0];
                    pcmBuf.clear();

                    for (int f = 0; f < FRAMES_PER_BUFFER; f++) {
                        // Exponential per-sample smoothing (~25ms time constant)
                        smGain += (targetGain - smGain) * 0.0009;
                        smWhMix += (targetWhMix - smWhMix) * 0.0007;
                        smRedshift += (targetRedshift - smRedshift) * 0.0006;
                        smDisk += (targetDisk - smDisk) * 0.0006;
                        smPan += (targetPan - smPan) * 0.0006;
                        smTde += (targetTde - smTde) * 0.0012;
                        smWhL += (targetWhL - smWhL) * 0.0008;

                        // Fast xorshift64 white noise [-1, +1]
                        rng ^= (rng << 13);
                        rng ^= (rng >>> 7);
                        rng ^= (rng << 17);
                        double whiteL = ((rng & 0xFFFFL) / 32768.0) - 1.0;
                        double whiteR = (((rng >>> 16) & 0xFFFFL) / 32768.0) - 1.0;

                        // --- LAYER 1: BLACK HOLE GRAVITATIONAL SUB-BASS DRONE ---
                        lfoPhase += twoPi * 0.14 * invSr;
                        if (lfoPhase > twoPi) lfoPhase -= twoPi;
                        double lfo = 0.85 + 0.15 * Math.sin(lfoPhase);

                        double droneHz = 28.0 + 36.0 * smRedshift; // Drops smoothly toward 28 Hz near r_+
                        subPhase += twoPi * droneHz * invSr;
                        if (subPhase > twoPi) subPhase -= twoPi;
                        subHarmPhase += twoPi * (droneHz * 1.498) * invSr;
                        if (subHarmPhase > twoPi) subHarmPhase -= twoPi;

                        double drone = (Math.sin(subPhase) * 0.65
                                + Math.sin(subPhase * 2.0) * 0.22
                                + Math.sin(subHarmPhase) * 0.14) * lfo * (0.48 + 0.35 * (1.0 - smRedshift));

                        // --- LAYER 2: ACCRETION DISK DOPPLER-PANNED STEREO ROAR ---
                        double cutoff = 0.018 + 0.025 * Math.min(1.5, smDisk);
                        lpL += cutoff * (whiteL - lpL);
                        lpR += cutoff * (whiteR - lpR);
                        bpL += 0.006 * (lpL - bpL);
                        bpR += 0.006 * (lpR - bpR);
                        double roarRawL = (lpL - bpL * 0.65) * 1.45;
                        double roarRawR = (lpR - bpR * 0.65) * 1.45;

                        double panL = Math.sqrt(Math.max(0.0, 0.5 * (1.0 - smPan)));
                        double panR = Math.sqrt(Math.max(0.0, 0.5 * (1.0 + smPan)));
                        double diskScale = Math.min(0.65, smDisk * 0.24);
                        double diskL = roarRawL * diskScale * panL;
                        double diskR = roarRawR * diskScale * panR;

                        // --- LAYER 3: TDE CRACKLING HIGH-ENERGY PLASMA SWELL ---
                        double sparkTrig = (((rng >>> 32) & 0xFFFFL) / 65535.0);
                        if (sparkTrig > (0.9965 - 0.012 * smTde)) {
                            crackleEnv = Math.min(1.0, crackleEnv + 0.55 * smTde);
                        }
                        crackleEnv *= 0.9945;
                        double crackleL = (whiteL * crackleEnv * 0.48 + Math.sin(subHarmPhase * 3.0) * smTde * 0.18);
                        double crackleR = (whiteR * crackleEnv * 0.48 + Math.cos(subHarmPhase * 3.01) * smTde * 0.18);

                        double bhL = drone + diskL + crackleL;
                        double bhR = drone + diskR + crackleR;

                        // --- LAYER 4: WORMHOLE ETHEREAL HARMONIC THROAT CHORD ---
                        double throatProx = Math.exp(-(smWhL * smWhL) / 28.0);
                        double universeShift = 1.0 + 0.12 * Math.tanh(-smWhL * 0.15);
                        double baseWhHz = (130.81 + 48.0 * throatProx) * universeShift; // C3 sweeping through throat

                        double whL = 0.0;
                        double whR = 0.0;
                        for (int k = 0; k < 4; k++) {
                            double freq = baseWhHz * WH_RATIOS[k];
                            whPhases[k] += twoPi * freq * invSr;
                            if (whPhases[k] > twoPi) whPhases[k] -= twoPi;

                            double amp = (k == 0 ? 0.34 : (k == 1 ? 0.24 : (k == 2 ? 0.18 : 0.14)))
                                    * (0.55 + 0.55 * throatProx);
                            double spread = (k % 2 == 0 ? 1.0 : -1.0) * (0.35 + 0.60 * throatProx);
                            double sig = Math.sin(whPhases[k] + spread * 0.45 * Math.sin(lfoPhase * (k + 1)));
                            whL += sig * amp * (1.0 - 0.35 * spread);
                            whR += sig * amp * (1.0 + 0.35 * spread);
                        }
                        // Shimmering throat air halo
                        whL += (lpL * 0.28) * (0.25 + 0.75 * throatProx);
                        whR += (lpR * 0.28) * (0.25 + 0.75 * throatProx);

                        // Crossfade Black Hole <-> Wormhole + soft limiter
                        double outL = (bhL * (1.0 - smWhMix) + whL * smWhMix) * smGain;
                        double outR = (bhR * (1.0 - smWhMix) + whR * smWhMix) * smGain;

                        outL = Math.tanh(outL);
                        outR = Math.tanh(outR);

                        pcmBuf.put((short) (outL * 32000.0));
                        pcmBuf.put((short) (outR * 32000.0));
                    }

                    pcmBuf.flip();
                    alBufferData(bufId, AL_FORMAT_STEREO16, pcmBuf, SAMPLE_RATE);
                    alSourceQueueBuffers(source, bufId);
                }

                if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
                    alSourcePlay(source);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            MemoryUtil.memFree(pcmBuf);
        }
    }

    private static void fillBuffer(ShortBuffer pcmBuf, double[] whPhases,
                                   double subPhase, double subHarmPhase, double lfoPhase,
                                   double lpL, double lpR, double bpL, double bpR, double crackleEnv, long rng,
                                   double smGain, double smWhMix, double smRedshift, double smDisk,
                                   double smPan, double smTde, double smWhL) {
        pcmBuf.clear();
        for (int i = 0; i < FRAMES_PER_BUFFER * 2; i++) {
            pcmBuf.put((short) 0);
        }
        pcmBuf.flip();
    }

    @Override
    public void close() {
        running = false;
        if (synthThread != null) {
            synthThread.interrupt();
            try {
                synthThread.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        cleanupOpenAl();
    }

    private void cleanupOpenAl() {
        if (available) {
            alSourceStop(source);
            alDeleteSources(source);
            alDeleteBuffers(buffers);
            available = false;
        }
        if (context != NULL) {
            alcMakeContextCurrent(NULL);
            alcDestroyContext(context);
            context = NULL;
        }
        if (device != NULL) {
            alcCloseDevice(device);
            device = NULL;
        }
    }
}
