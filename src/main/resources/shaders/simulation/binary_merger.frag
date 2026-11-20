#version 430 core
layout(location = 0) out vec4 FragColor;
layout(location = 1) out vec2 FragVelocity;

uniform vec2 uResolution;
uniform float uTime;
uniform float uDt;
uniform vec3 uCamPos;
uniform vec3 uCamForward;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform float uTanHalfFov;

// Previous camera pose for exact object + camera MRT motion vectors
uniform vec3 uPrevCamPos;
uniform vec3 uPrevCamForward;
uniform vec3 uPrevCamRight;
uniform vec3 uPrevCamUp;
uniform float uPrevTanHalfFov;

uniform samplerCube uSkybox;
uniform sampler3D uNoiseVol;
uniform sampler2D uBlackbodyLut;

// Binary black hole state from BinaryMergerPhysics (G = c = M_tot = 1)
uniform vec3 uPos1;
uniform vec3 uPos2;
uniform vec3 uVel1;
uniform vec3 uVel2;
uniform float uMass1;
uniform float uMass2;
uniform float uRemnantMass;
uniform float uRemnantSpin;
uniform float uMergeBlend;     // 0 during inspiral/plunge, 0..1 coalescence, 1 ringdown
uniform float uSeparation;
uniform float uOrbitalPhase;
uniform float uGwPhase;
uniform float uGwFreqHz;
uniform float uStrainAmp;
uniform float uRingdownEnv;
uniform float uMergerPulse;

uniform float uDiskTempScale;
uniform float uDiskDensity;
uniform int uDoppler;
uniform int uLensing;
uniform int uShowDisk;
uniform int uShowGrid;
uniform int uGwOverlay;
uniform vec2 uJitter;
uniform int uFrameIndex;

const float PI = 3.14159265359;
const float R_DISK_OUT = 28.0;
const float R_SHELL = 30.0;
const int MAX_STEPS = 148;

vec3 safeNormalize(vec3 v, vec3 fallback) {
    float len2 = dot(v, v);
    return (len2 > 1e-12) ? (v * inversesqrt(len2)) : fallback;
}

float safeAtan2(float y, float x) {
    return (abs(x) + abs(y) > 1e-6) ? atan(y, x) : 0.0;
}

vec3 sanitizeHdr(vec3 c) {
    bvec3 bad = bvec3(isnan(c.x) || isinf(c.x),
                      isnan(c.y) || isinf(c.y),
                      isnan(c.z) || isinf(c.z));
    return clamp(mix(c, vec3(0.0), bad), vec3(0.0), vec3(48.0));
}

vec3 sampleBlackbody(float tempKelvin) {
    float norm = clamp((tempKelvin - 900.0) * (1.0 / 37100.0), 0.0, 1.0);
    return texture(uBlackbodyLut, vec2(sqrt(norm), 0.5)).rgb;
}

float interleavedGradientNoise(vec2 px) {
    return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

// ============================================================================
// SUPERPOSED BOOSTED KERR-SCHILD + SPINNING KERR REMNANT HAMILTONIAN
// ============================================================================

float kerrRadius(vec3 x, float a2) {
    float R2 = dot(x, x);
    float b = R2 - a2;
    float disc = max(0.0, b * b + 4.0 * a2 * x.y * x.y);
    return sqrt(max(1e-4, 0.5 * (b + sqrt(disc))));
}

// Superposed Kerr-Schild + 1PN gravitomagnetic orbital frame-dragging Hamiltonian:
//   H_ham = 0.5 * (-pt^2 + |p|^2 - 2 sum_i H_i (-pt + n_i.p)^2 - 2 sum_i H_i pt (v_i.p))
// Exact canonical derivatives guarantee d(H_ham)/d(lambda) == 0 identically, exact
// horizon capture at r_i = 2 M_i (independent of velocity v_i, zero static-snapshot
// ergosphere inflation), and authentic orbital velocity frame-dragging.
void boostedBodyContrib(vec3 x, vec3 p, float pt,
                        vec3 bodyPos, vec3 bodyVel, float mass, float weight,
                        inout vec3 dxAcc, inout vec3 dpAcc, out float rBody, out float hVal) {
    vec3 d = x - bodyPos;
    float d2 = dot(d, d);
    rBody = sqrt(max(1e-5, d2));
    float invR = 1.0 / rBody;
    vec3 nHat = d * invR;

    hVal = (mass * weight) * invR;
    float np = dot(nHat, p);
    float q = -pt + np;

    // Canonical dx/dlambda = +dH_ham/dp
    dxAcc -= (2.0 * hVal * q) * nHat + (hVal * pt) * bodyVel;

    // Canonical dp/dlambda = -dH_ham/dx
    vec3 gradH = (-hVal * invR) * nHat;
    vec3 gradNP = (p - np * nHat) * invR;
    float vp = dot(bodyVel, p);

    dpAcc += (q * q + pt * vp) * gradH + (2.0 * hVal * q) * gradNP;
}

// Exact spinning Kerr-Schild remnant contribution (active during coalescence & QNM ringdown)
void remnantKerrContrib(vec3 x, vec3 p, float pt, float mass, float a, float a2, float weight,
                        inout vec3 dxAcc, inout vec3 dpAcc, out float rRem, out float hRem) {
    rRem = kerrRadius(x, a2);
    float r2 = rRem * rRem;
    float D = max(1e-5, r2 * r2 + a2 * x.y * x.y);
    float invD = 1.0 / D;

    // Subtle (l=2, m=2) QNM horizon ringdown quadrupole oscillation
    float qnmMod = 1.0;
    if (uRingdownEnv > 0.002) {
        float rho2 = dot(x.xz, x.xz);
        float sin2Th = rho2 / max(1e-4, rho2 + x.y * x.y);
        float cos2Phi = (rho2 > 1e-5) ? ((x.x * x.x - x.z * x.z) / rho2) : 1.0;
        float sin2Phi = (rho2 > 1e-5) ? ((2.0 * x.x * x.z) / rho2) : 0.0;
        float cosPhase = cos(uGwPhase) * cos2Phi + sin(uGwPhase) * sin2Phi;
        qnmMod = 1.0 + 0.065 * uRingdownEnv * sin2Th * cosPhase * exp(-max(0.0, rRem - 1.5) * 0.45);
    }

    hRem = (mass * weight * qnmMod * r2 * rRem) * invD;
    float S = max(1e-4, r2 + a2);
    float invS = 1.0 / S;

    vec3 lVec = vec3(
        (rRem * x.x + a * x.z) * invS,
        x.y / rRem,
        (rRem * x.z - a * x.x) * invS
    );

    float q = -pt + dot(lVec, p);
    dxAcc -= (2.0 * hRem * q) * lVec;

    vec3 gradR = (rRem * invD) * vec3(r2 * x.x, S * x.y, r2 * x.z);
    vec3 gradH = (mass * weight * qnmMod) * (r2 * (3.0 * a2 * x.y * x.y - r2 * r2) * gradR
                - vec3(0.0, 2.0 * a2 * r2 * rRem * x.y, 0.0)) * (invD * invD);

    float xpZp = x.x * p.x + x.z * p.z;
    float zpxXpz = x.z * p.x - x.x * p.z;
    vec3 directLP = vec3(
        (rRem * p.x - a * p.z) * invS,
        p.y / rRem,
        (rRem * p.z + a * p.x) * invS
    );
    float dlpDr = ((a2 - r2) * xpZp - 2.0 * a * rRem * zpxXpz) * (invS * invS)
                - (x.y * p.y) / r2;
    vec3 gradLP = directLP + dlpDr * gradR;

    dpAcc += (q * q) * gradH + (2.0 * hRem * q) * gradLP;
}

void binaryHamiltonDeriv(vec3 x, vec3 p, float pt,
                         float aRem, float aRem2,
                         out vec3 dx, out vec3 dp,
                         out float r1, out float r2, out float rRem, out float hSum) {
    dx = p;
    dp = vec3(0.0);
    r1 = 1e4;
    r2 = 1e4;
    rRem = 1e4;
    hSum = 0.0;

    float wBin = 1.0 - uMergeBlend;
    if (wBin > 0.001) {
        float h1, h2;
        boostedBodyContrib(x, p, pt, uPos1, uVel1, uMass1, wBin, dx, dp, r1, h1);
        boostedBodyContrib(x, p, pt, uPos2, uVel2, uMass2, wBin, dx, dp, r2, h2);
        hSum += h1 + h2;
    }
    if (uMergeBlend > 0.001) {
        float hR;
        remnantKerrContrib(x, p, pt, uRemnantMass, aRem, aRem2, uMergeBlend, dx, dp, rRem, hR);
        hSum += hR;
    }
}

void binaryGeodesicStep(inout vec3 x, inout vec3 p, float h, float pt,
                        float aRem, float aRem2, float rNear,
                        inout vec3 dxNow, inout vec3 dpNow,
                        out float r1, out float r2, out float rRem, out float hSum) {
    vec3 k1x = dxNow;
    vec3 k1p = dpNow;
    vec3 k2x, k2p;
    float t1, t2, tR, tH;
    binaryHamiltonDeriv(x + (0.5 * h) * k1x, p + (0.5 * h) * k1p, pt,
                        aRem, aRem2, k2x, k2p, t1, t2, tR, tH);

    if (rNear > 4.6) {
        // Weak-field RK2 midpoint
        x += h * k2x;
        p += h * k2p;
        binaryHamiltonDeriv(x, p, pt, aRem, aRem2, dxNow, dpNow, r1, r2, rRem, hSum);
    } else {
        // Strong-field FSAL RK4 preserves crisp mutual secondary eyebrow lensing arcs
        vec3 k3x, k3p, k4x, k4p;
        binaryHamiltonDeriv(x + (0.5 * h) * k2x, p + (0.5 * h) * k2p, pt,
                            aRem, aRem2, k3x, k3p, t1, t2, tR, tH);
        binaryHamiltonDeriv(x + h * k3x, p + h * k3p, pt,
                            aRem, aRem2, k4x, k4p, t1, t2, tR, tH);

        float h6 = h * (1.0 / 6.0);
        x += h6 * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
        p += h6 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);
        binaryHamiltonDeriv(x, p, pt, aRem, aRem2, dxNow, dpNow, r1, r2, rRem, hSum);
    }
}

vec3 intersectEquatorialHermite(vec3 x0, vec3 dx0, vec3 x1, vec3 dx1, float h, out float tHit) {
    float y0 = x0.y;
    float y1 = x1.y;
    float m0 = dx0.y * h;
    float m1 = dx1.y * h;

    float t = clamp(abs(y0) / (abs(y0) + abs(y1) + 1e-8), 0.0, 1.0);
    for (int iter = 0; iter < 3; iter++) {
        float t2 = t * t;
        float t3 = t2 * t;
        float f = (2.0 * t3 - 3.0 * t2 + 1.0) * y0
                + (t3 - 2.0 * t2 + t) * m0
                + (-2.0 * t3 + 3.0 * t2) * y1
                + (t3 - t2) * m1;
        float df = (6.0 * t2 - 6.0 * t) * y0
                 + (3.0 * t2 - 4.0 * t + 1.0) * m0
                 + (-6.0 * t2 + 6.0 * t) * y1
                 + (3.0 * t2 - 2.0 * t) * m1;
        if (abs(df) > 1e-7) {
            t = clamp(t - f / df, 0.0, 1.0);
        }
    }
    tHit = t;
    float t2 = t * t;
    float t3 = t2 * t;
    vec3 hit = (2.0 * t3 - 3.0 * t2 + 1.0) * x0
             + (t3 - 2.0 * t2 + t) * (dx0 * h)
             + (-2.0 * t3 + 3.0 * t2) * x1
             + (t3 - t2) * (dx1 * h);
    hit.y = 0.0;
    return hit;
}

// ============================================================================
// QUADRUPOLE GRAVITATIONAL WAVE SKYBOX DEFLECTION & CELESTIAL GRID
// ============================================================================

vec3 applyQuadrupoleGwDeflection(vec3 dir, float camDist) {
    if (uStrainAmp < 0.002) {
        return dir;
    }
    // Exact GR (l=2, m=2) quadrupole angular polarization pattern
    float cosTh = clamp(dir.y, -1.0, 1.0);
    float fPlus = 0.5 * (1.0 + cosTh * cosTh);
    float fCross = cosTh;

    float phi = safeAtan2(dir.z, dir.x);
    float kGw = mix(0.28, 0.78, clamp((uGwFreqHz - 42.0) / 300.0, 0.0, 1.0));
    // Retarded spiral phase across the celestial sphere
    float retPhase = uGwPhase - camDist * kGw * 0.45 - 2.0 * phi + 3.2 * (1.0 - abs(cosTh));

    float hPlus = uStrainAmp * fPlus * cos(retPhase);
    float hCross = uStrainAmp * fCross * sin(retPhase);

    // Orthonormal sky tangent basis (ePhi, eTheta)
    vec3 ePhi = safeNormalize(vec3(-dir.z, 0.0, dir.x), vec3(1.0, 0.0, 0.0));
    vec3 eTheta = cross(ePhi, dir);

    // Subtle physical transverse-traceless quadrupole sky distortion
    float amp = 0.0115;
    return safeNormalize(dir + (hPlus * amp) * eTheta + (hCross * amp) * ePhi, dir);
}

vec3 sampleSkyWithGrid(vec3 dir, vec3 ddx, vec3 ddy) {
    vec3 sky = textureGrad(uSkybox, dir, ddx, ddy).rgb;
    if (uShowGrid == 1) {
        float lon = safeAtan2(dir.z, dir.x);
        float lat = asin(clamp(dir.y, -1.0, 1.0));

        float gridSpacing = PI / 12.0;
        float cosLat = max(0.06, cos(lat));
        float dLon = abs(fract(lon / gridSpacing + 0.5) - 0.5) * gridSpacing * cosLat;
        float dLat = abs(fract(lat / gridSpacing + 0.5) - 0.5) * gridSpacing;

        float line = 1.0 - smoothstep(0.0022, 0.0062, min(dLon, dLat));
        float equator = 1.0 - smoothstep(0.0035, 0.0095, abs(lat));
        float prime = 1.0 - smoothstep(0.0035, 0.0095, abs(lon) * cosLat);

        sky += vec3(0.18, 0.56, 0.98) * line * 0.48;
        sky += vec3(1.00, 0.42, 0.16) * max(equator, prime) * 0.85;
    }
    return sky;
}

// ============================================================================
// RELATIVISTIC DOPPLER FACTOR FOR BINARY PLASMA (CIRCUMBINARY / MINI-DISKS)
// ============================================================================

float computePlasmaDopplerG(vec3 hitP, vec3 pCov, float pt, float obsFreq,
                            vec3 vPlasma, float hSum) {
    if (uDoppler == 0) {
        return 1.0;
    }
    float v2 = min(0.72, dot(vPlasma, vPlasma));
    float lapse = max(0.14, 1.0 - min(0.84, 2.0 * hSum));
    float ut = inversesqrt(max(0.08, lapse - v2));
    float emitFreq = ut * (pt + dot(pCov, vPlasma));
    return clamp(obsFreq / max(0.10, emitFreq), 0.22, 2.75);
}

// ============================================================================
// CIRCUMBINARY DISK + COMPANION MINI-DISKS + TIDAL STREAMS & SHOCK BRIDGE
// ============================================================================

void evalBinaryPlasma(vec3 hitP, float rPlus1, float rPlus2, float rPlusRem, float mipLod,
                      out float outDensity, out float outTempK, out vec3 outVel,
                      out float outDust, out float outShockBoost) {
    outDensity = 0.0;
    outTempK = 6000.0;
    outVel = vec3(0.0);
    outDust = 0.0;
    outShockBoost = 1.0;

    float r = length(hitP.xz);
    float phi = safeAtan2(hitP.z, hitP.x);
    float wBin = 1.0 - uMergeBlend;

    // 1. OUTER CIRCUMBINARY DISK (cleared inside r_cav = 1.85 * a with m=2 spiral arms)
    float rCav = mix(max(3.6, 1.85 * uSeparation), max(2.05, 1.45 * rPlusRem), uMergeBlend);
    if (r > rCav * 0.86 && r <= R_DISK_OUT) {
        float uNorm = clamp((r - rCav) / max(1.0, R_DISK_OUT - rCav), 0.0, 1.0);
        float relPhi = phi - uOrbitalPhase;
        float logSpiral = relPhi * 2.0 + 5.8 * log(max(1.0, r / max(1.5, rCav)));

        float rWrap = 0.24 + uNorm * 0.58;
        vec3 volCoord = vec3(
            cos(logSpiral * 0.5) * rWrap + uNorm * 0.42,
            hitP.y * 0.48 - uTime * 0.018,
            sin(logSpiral * 0.5) * rWrap + uNorm * 0.78
        );
        float turbMacro = textureLod(uNoiseVol, volCoord * 0.75, mipLod).r;
        float turbMid = textureLod(uNoiseVol, volCoord * 1.85 + vec3(0.27, -0.15, 0.31), mipLod).r;

        // m=2 tidal density waves excited by rotating quadrupole potential
        float m2Wave = 0.5 + 0.5 * cos(logSpiral + turbMacro * 4.2);
        float m2Sharpen = pow(m2Wave, 1.45);
        float fineShears = 0.5 + 0.5 * sin(uNorm * 28.0 - turbMid * 6.5);
        float structure = 0.46 * m2Sharpen + 0.30 * fineShears + 0.24 * turbMacro;

        float innerWall = smoothstep(rCav * 0.86, rCav * 1.06, r);
        float rimGlow = exp(-pow((r - rCav * 1.08) / max(0.65, rCav * 0.16), 2.0)) * 0.88;
        float outerFade = pow(max(0.0, 1.0 - uNorm), 1.50);

        float cbDens = (structure * 0.96 + rimGlow) * innerWall * outerFade * uDiskDensity;
        if (cbDens > 0.002) {
            float omegaK = 1.0 / (r * sqrt(max(1.0, r)));
            outVel = omegaK * vec3(-hitP.z, 0.0, hitP.x);
            outDust = smoothstep(0.22, 0.80, uNorm) * smoothstep(0.42, 0.74, turbMid);
            outTempK = (6800.0 * uDiskTempScale) * pow(max(2.0, rCav) / max(2.0, r), 0.72)
                     * (0.62 + 0.56 * structure + 0.38 * rimGlow) * (1.0 - 0.50 * outDust);
            outDensity = cbDens;
        }
    }

    // 2. COMPANION MINI-DISKS + S-SHAPED TIDAL STREAMS + SHOCK BRIDGE (during inspiral & coalescence)
    if (wBin > 0.01) {
        // Companion 1 Mini-Disk
        vec2 d1 = hitP.xz - uPos1.xz;
        float r1 = length(d1);
        float rMiniIn1 = rPlus1 * 1.10;
        float rMiniOut1 = max(rPlus1 * 2.15, 0.48 * uSeparation * pow(uMass1, 0.33));
        if (r1 > rMiniIn1 && r1 < rMiniOut1) {
            float u1 = (r1 - rMiniIn1) / max(0.15, rMiniOut1 - rMiniIn1);
            float phi1 = safeAtan2(d1.y, d1.x);
            float sp1 = phi1 - uTime * 3.8 + 4.5 * log(max(0.15, r1 / rMiniIn1));
            float t1 = textureLod(uNoiseVol, vec3(cos(sp1) * 0.32, hitP.y * 0.8, sin(sp1) * 0.32), mipLod).r;
            float env1 = smoothstep(0.0, 0.14, u1) * pow(max(0.0, 1.0 - u1), 1.15);
            float md1 = env1 * (0.55 + 0.72 * t1) * wBin * 1.15 * uDiskDensity;
            if (md1 > 0.002) {
                float vK1 = min(0.48, sqrt(uMass1 / max(0.35, r1)));
                vec3 v1Tot = uVel1 + vK1 * vec3(-d1.y / r1, 0.0, d1.x / r1);
                float temp1 = (10800.0 * uDiskTempScale) * (1.24 - 0.48 * u1) * (0.82 + 0.36 * t1);
                float blend1 = md1 / max(1e-4, outDensity + md1);
                outVel = mix(outVel, v1Tot, blend1);
                outTempK = mix(outTempK, temp1, blend1);
                outDust *= (1.0 - blend1);
                outDensity += md1;
            }
        }

        // Companion 2 Mini-Disk
        vec2 d2 = hitP.xz - uPos2.xz;
        float r2 = length(d2);
        float rMiniIn2 = rPlus2 * 1.10;
        float rMiniOut2 = max(rPlus2 * 2.15, 0.48 * uSeparation * pow(uMass2, 0.33));
        if (r2 > rMiniIn2 && r2 < rMiniOut2) {
            float u2 = (r2 - rMiniIn2) / max(0.15, rMiniOut2 - rMiniIn2);
            float phi2 = safeAtan2(d2.y, d2.x);
            float sp2 = phi2 - uTime * 4.4 + 4.5 * log(max(0.15, r2 / rMiniIn2));
            float t2 = textureLod(uNoiseVol, vec3(cos(sp2) * 0.32, hitP.y * 0.8, sin(sp2) * 0.32), mipLod).r;
            float env2 = smoothstep(0.0, 0.14, u2) * pow(max(0.0, 1.0 - u2), 1.15);
            float md2 = env2 * (0.55 + 0.72 * t2) * wBin * 1.18 * uDiskDensity;
            if (md2 > 0.002) {
                float vK2 = min(0.48, sqrt(uMass2 / max(0.30, r2)));
                vec3 v2Tot = uVel2 + vK2 * vec3(-d2.y / r2, 0.0, d2.x / r2);
                float temp2 = (11400.0 * uDiskTempScale) * (1.26 - 0.48 * u2) * (0.82 + 0.36 * t2);
                float blend2 = md2 / max(1e-4, outDensity + md2);
                outVel = mix(outVel, v2Tot, blend2);
                outTempK = mix(outTempK, temp2, blend2);
                outDust *= (1.0 - blend2);
                outDensity += md2;
            }
        }

        // 3. SHOCK-HEATED TIDAL GAS BRIDGE connecting M1 & M2 through L1 saddle
        vec2 seg = uPos2.xz - uPos1.xz;
        float segLen2 = dot(seg, seg);
        if (segLen2 > 0.25 && r1 > rPlus1 * 1.04 && r2 > rPlus2 * 1.04) {
            float tSeg = clamp(dot(hitP.xz - uPos1.xz, seg) / segLen2, 0.0, 1.0);
            vec2 closest = uPos1.xz + seg * tSeg;
            float distBridge = length(hitP.xz - closest);
            float bridgeW = 0.34 + 0.16 * uSeparation * 0.18 + 0.24 * sin(tSeg * PI);
            if (distBridge < bridgeW * 2.5) {
                float bNoise = textureLod(uNoiseVol, vec3(hitP.x * 0.28, hitP.y * 0.65, hitP.z * 0.28 - uTime * 0.18), mipLod).r;
                float sCurve = 0.24 * sin(tSeg * 2.0 * PI);
                vec2 perpDir = normalize(vec2(-seg.y, seg.x));
                float curvedDist = abs(dot(hitP.xz - closest, perpDir) - sCurve * bridgeW);
                float profile = exp(-(curvedDist * curvedDist) / max(0.025, bridgeW * bridgeW * 0.60));
                float ends = smoothstep(0.03, 0.18, tSeg) * smoothstep(0.97, 0.82, tSeg);
                float shockIntensity = 1.25 + 1.25 * smoothstep(6.5, 2.2, uSeparation) + 1.40 * uMergerPulse;
                float bDens = profile * ends * (0.60 + 0.85 * bNoise) * wBin * 1.38 * uDiskDensity;
                if (bDens > 0.002) {
                    vec3 vBridge = mix(uVel1, uVel2, tSeg);
                    float tempBridge = (11600.0 * uDiskTempScale) * (0.88 + 0.46 * bNoise) * (1.0 + 0.32 * uMergerPulse);
                    float blendB = bDens / max(1e-4, outDensity + bDens);
                    outVel = mix(outVel, vBridge, blendB);
                    outTempK = mix(outTempK, tempBridge, blendB);
                    outDust *= (1.0 - blendB);
                    outShockBoost = max(outShockBoost, shockIntensity);
                    outDensity += bDens;
                }
            }
        }

        // 4. S-SHAPED TIDAL ACCRETION STREAMS peeled from circumbinary wall into cavity
        if (r > uSeparation * 0.45 && r < rCav * 1.08) {
            float uStream = clamp((r - uSeparation * 0.45) / max(0.5, rCav - uSeparation * 0.45), 0.0, 1.0);
            float streamAngle = 2.0 * (phi - uOrbitalPhase + 1.35 * uStream);
            float ridge = pow(max(0.0, cos(streamAngle)), 8.0);
            if (ridge > 0.04) {
                float sTurb = textureLod(uNoiseVol, vec3(hitP.x * 0.16, hitP.y * 0.5, hitP.z * 0.16), mipLod).r;
                float sDens = ridge * (0.45 + 0.75 * sTurb) * smoothstep(0.0, 0.25, uStream)
                            * smoothstep(1.08, 0.75, uStream) * wBin * 0.72 * uDiskDensity;
                if (sDens > 0.002 && r1 > rPlus1 * 1.1 && r2 > rPlus2 * 1.1) {
                    float omegaS = 1.0 / (r * sqrt(max(1.0, r)));
                    vec3 vS = omegaS * vec3(-hitP.z, 0.0, hitP.x) - 0.18 * normalize(vec3(hitP.x, 0.0, hitP.z));
                    float tempS = (12200.0 * uDiskTempScale) * (1.15 - 0.35 * uStream);
                    float blendS = sDens / max(1e-4, outDensity + sDens);
                    outVel = mix(outVel, vS, blendS);
                    outTempK = mix(outTempK, tempS, blendS);
                    outDensity += sDens;
                }
            }
        }
    }

    // Post-merger hot recoil flash ring around the newly formed spinning Kerr remnant
    if (uMergeBlend > 0.05 && r > rPlusRem * 1.12 && r < 6.5) {
        float flashR = mix(2.3, 4.6, 1.0 - uRingdownEnv);
        float ringEnv = exp(-pow((r - flashR) / 1.15, 2.0));
        float fTurb = textureLod(uNoiseVol, vec3(hitP.x * 0.22, uTime * 0.12, hitP.z * 0.22), mipLod).r;
        float fDens = ringEnv * (0.45 + 0.75 * fTurb) * uMergeBlend * (0.35 + 0.95 * uRingdownEnv) * uDiskDensity;
        if (fDens > 0.002) {
            float omegaR = 1.0 / (r * sqrt(max(1.0, r)) + uRemnantSpin);
            vec3 vR = omegaR * vec3(-hitP.z, 0.0, hitP.x);
            float tempR = (14500.0 * uDiskTempScale) * (0.85 + 0.55 * uRingdownEnv);
            float blendF = fDens / max(1e-4, outDensity + fDens);
            outVel = mix(outVel, vR, blendF);
            outTempK = mix(outTempK, tempR, blendF);
            outShockBoost = max(outShockBoost, 1.0 + 0.85 * uMergerPulse);
            outDensity += fDens;
        }
    }
}

// Optional translucent orbital-plane quadrupole GW spiral contour overlay ([G])
vec3 evalGwPlaneOverlay(vec3 hitP) {
    float r = length(hitP.xz);
    float rMin = max(2.6, 1.05 * uSeparation);
    if (r < rMin || r > 38.0) {
        return vec3(0.0);
    }
    float phi = safeAtan2(hitP.z, hitP.x);
    float kGw = mix(0.32, 0.86, clamp((uGwFreqHz - 42.0) / 300.0, 0.0, 1.0));
    float wavePhase = uGwPhase - r * kGw - 2.0 * phi;
    float wave = cos(wavePhase);

    float fadeIn = smoothstep(rMin, rMin + 2.8, r);
    float fadeOut = smoothstep(38.0, 18.0, r) / sqrt(max(2.5, r * 0.30));
    float amp = max(0.28, uStrainAmp) * fadeIn * fadeOut;

    // Crisp, translucent positive (cyan) and negative (violet) spiral wave-crest contours
    float posCrest = smoothstep(0.76, 0.99, wave);
    float negCrest = smoothstep(0.76, 0.99, -wave);
    float isoLine = 1.0 - smoothstep(0.0, 0.075, abs(abs(wave) - 0.86));

    vec3 posCol = vec3(0.14, 0.82, 1.00) * (posCrest * 0.24 + isoLine * max(0.0, wave) * 0.52);
    vec3 negCol = vec3(0.64, 0.26, 1.00) * (negCrest * 0.20 + isoLine * max(0.0, -wave) * 0.44);
    return (posCol + negCol) * amp * 0.40;
}

// ============================================================================
// MAIN BINARY GEODESIC RAY TRACER + OBJECT/CAMERA VELOCITY MRT
// ============================================================================

void main() {
    vec2 curUv = gl_FragCoord.xy / uResolution;
    vec2 jitteredPx = gl_FragCoord.xy + uJitter;
    vec2 ndc = (jitteredPx / uResolution) * 2.0 - 1.0;
    float aspect = uResolution.x / uResolution.y;

    vec3 vLocal = safeNormalize(uCamForward
                              + ndc.x * aspect * uTanHalfFov * uCamRight
                              + ndc.y * uTanHalfFov * uCamUp, uCamForward);

    float aRem = clamp(uRemnantSpin, 0.0, 0.998);
    float aRem2 = aRem * aRem;

    float rPlus1 = 2.0 * uMass1;
    float rPlus2 = 2.0 * uMass2;
    float rPlusRem = uRemnantMass * (1.0 + sqrt(max(0.0, 1.0 - aRem2)));

    float rCam = length(uCamPos);
    float rEscape = max(42.0, rCam + 6.0);

    // Local static-shell camera tetrad at rCam (far exterior M_tot = 1)
    float hCam = 1.0 / max(2.2, rCam);
    vec3 lCam = safeNormalize(uCamPos, vec3(0.0, 0.0, 1.0));
    vec3 p;
    float pt = 1.0;
    float obsFreq = 1.0;
    if (uLensing == 1) {
        float alpha = sqrt(max(0.08, 1.0 - 2.0 * hCam));
        float dl = dot(vLocal, lCam);
        vec3 dPerp = vLocal - dl * lCam;
        p = dPerp / alpha + ((dl - 2.0 * hCam) / (alpha * alpha)) * lCam;
        obsFreq = 1.0 / alpha;
    } else {
        p = vLocal;
    }

    vec3 accumColor = vec3(0.0);
    float transmittance = 1.0;
    vec3 exitDir = vLocal;
    bool escaped = false;
    bool captured = false;

    bool hasHit = false;
    vec3 dominantPos = vec3(0.0);
    vec3 dominantVel = vec3(0.0);
    float hitDist = 1e5;

    // Fast analytic weak-field shortcut for far corner rays outside disk & binary lensing shell
    vec3 x = uCamPos;
    float bProj = dot(-x, vLocal);
    float bSq = max(0.0, dot(x, x) - bProj * bProj);
    if (uLensing == 1 && uGwOverlay == 0 && rCam > R_SHELL + 1.0 && (bProj <= 0.0 || bSq > (R_SHELL + 1.0) * (R_SHELL + 1.0))) {
        float b = sqrt(max(1.0, bSq));
        vec3 bVec = x + vLocal * max(0.0, bProj);
        float defl = (bProj > 0.0)
                ? (4.0 / b + 11.78097 / (b * b))
                : (2.0 * max(0.0, 1.0 - dot(lCam, vLocal)) / max(1.0, rCam));
        exitDir = safeNormalize(vLocal - safeNormalize(bVec, vec3(0.0, 1.0, 0.0)) * defl, vLocal);
        escaped = true;
    } else {
        float frameShift = float(uFrameIndex & 63) * 5.588238;
        float ign = interleavedGradientNoise(gl_FragCoord.xy + vec2(frameShift));

        vec3 dxNow = vLocal;
        vec3 dpNow = vec3(0.0);
        float r1 = length(x - uPos1);
        float r2 = length(x - uPos2);
        float rRem = rCam;
        float hSum = hCam;

        if (uLensing == 1) {
            binaryHamiltonDeriv(x, p, pt, aRem, aRem2, dxNow, dpNow, r1, r2, rRem, hSum);

            // Single analytic leapfrog jump across empty exterior space to R_SHELL
            if (rCam > R_SHELL + 1.5 && bProj > 0.0 && bSq < R_SHELL * R_SHELL) {
                float tSkip = max(0.0, bProj - sqrt(max(0.0, R_SHELL * R_SHELL - bSq))) * 0.90;
                if (tSkip > 1.0) {
                    float hSkip = tSkip / max(1.0, length(dxNow));
                    binaryGeodesicStep(x, p, hSkip, pt, aRem, aRem2, min(r1, r2),
                                       dxNow, dpNow, r1, r2, rRem, hSum);
                }
            }
        }

        int diskCrossings = 0;
        float rCenter = length(x);
        float wBin = 1.0 - uMergeBlend;

        for (int step = 0; step < MAX_STEPS; step++) {
            float speed = length(dxNow);
            float pNormSq = dot(p, p);
            float radialOut = dot(x, dxNow);

            if (rCenter > rEscape && radialOut > 0.0) {
                exitDir = safeNormalize(dxNow, vLocal);
                escaped = true;
                break;
            }
            if (transmittance < 0.008) {
                exitDir = safeNormalize(dxNow, vLocal);
                escaped = true;
                break;
            }

            // Capture check: Body 1 horizon, Body 2 horizon, common peanut neck, or merged Kerr remnant
            if (pNormSq > 7225.0 || isnan(pNormSq) || isinf(pNormSq)) {
                captured = true;
                if (!hasHit) {
                    hasHit = true;
                    dominantPos = (r1 < r2) ? uPos1 : uPos2;
                    dominantVel = (r1 < r2) ? uVel1 : uVel2;
                    hitDist = length(x - uCamPos);
                }
                break;
            }
            if (wBin > 0.04) {
                float cap1 = rPlus1 * (1.02 - 0.12 * uMergeBlend);
                float cap2 = rPlus2 * (1.02 - 0.12 * uMergeBlend);
                if (r1 <= cap1) {
                    captured = true;
                    if (!hasHit) {
                        hasHit = true;
                        dominantPos = x;
                        dominantVel = uVel1;
                        hitDist = length(x - uCamPos);
                    }
                    break;
                }
                if (r2 <= cap2) {
                    captured = true;
                    if (!hasHit) {
                        hasHit = true;
                        dominantPos = x;
                        dominantVel = uVel2;
                        hitDist = length(x - uCamPos);
                    }
                    break;
                }
                // Peanut-shaped common apparent horizon necking right at coalescence contact
                if ((uSeparation < 2.85 || uMergeBlend > 0.01) && (2.0 * hSum >= 1.04)
                        && min(r1, r2) < max(rPlus1, rPlus2) * 1.55) {
                    captured = true;
                    if (!hasHit) {
                        hasHit = true;
                        dominantPos = x;
                        dominantVel = mix(uVel1, uVel2, r1 / max(1e-4, r1 + r2));
                        hitDist = length(x - uCamPos);
                    }
                    break;
                }
            }
            if (uMergeBlend > 0.30 && rRem <= rPlusRem * 1.025) {
                captured = true;
                if (!hasHit) {
                    hasHit = true;
                    dominantPos = x;
                    dominantVel = vec3(0.0);
                    hitDist = length(x - uCamPos);
                }
                break;
            }

            // Adaptive step sizing guided by distance to nearest horizon & equatorial plane
            float dH1 = max(0.02, r1 - rPlus1);
            float dH2 = max(0.02, r2 - rPlus2);
            float dHRem = max(0.02, rRem - rPlusRem);
            float distNearest = (uMergeBlend > 0.95) ? dHRem : min(dH1, dH2);
            if (uMergeBlend > 0.05 && uMergeBlend <= 0.95) {
                distNearest = min(distNearest, dHRem);
            }
            float rNear = (uMergeBlend > 0.95) ? rRem : min(r1, r2);

            float dsCoord;
            if (rCenter > R_DISK_OUT + 2.0) {
                dsCoord = (radialOut > 0.0)
                        ? clamp(0.45 * rCenter, 2.00, 9.50)
                        : clamp(0.30 * distNearest, 0.90, 5.40);
            } else {
                float absY = abs(x.y);
                float diskGuide = (absY > 1.8) ? 3.80 : (0.58 + absY * 0.78);
                float fieldScale = (rNear > 4.6) ? (0.22 * distNearest + 0.055) : (0.115 * distNearest + 0.028);
                dsCoord = clamp(min(fieldScale, diskGuide), 0.026, 3.60);
            }

            if (step == 0) {
                dsCoord *= (0.60 + 0.40 * ign);
            }

            vec3 prevX = x;
            vec3 prevP = p;
            vec3 prevDx = dxNow;

            float hStep;
            if (uLensing == 1) {
                float pNorm = sqrt(pNormSq);
                hStep = dsCoord / max(1.0, max(speed, pNorm * 0.45));
                binaryGeodesicStep(x, p, hStep, pt, aRem, aRem2, rNear,
                                   dxNow, dpNow, r1, r2, rRem, hSum);
            } else {
                hStep = dsCoord;
                x += dxNow * hStep;
                r1 = length(x - uPos1);
                r2 = length(x - uPos2);
                rRem = length(x);
            }
            rCenter = length(x);

            // Equatorial plasma & optional GW contour crossings
            bool crossed = (prevX.y > 0.0 && x.y <= 0.0) || (prevX.y < 0.0 && x.y >= 0.0);
            if ((uShowDisk == 1 || uGwOverlay == 1) && (crossed || abs(x.y) < 0.48) && rCenter <= 38.5) {
                float slabHalfH = 0.045 * sqrt(max(0.0, rCenter - 1.2)) + 0.028;
                bool inSlab = (!crossed) && (uShowDisk == 1) && (abs(x.y) < slabHalfH) && (rCenter <= R_DISK_OUT);

                if (crossed || inSlab) {
                    vec3 hitP;
                    vec3 hitPCov;
                    float stepWeight;
                    if (crossed) {
                        float tHit;
                        hitP = intersectEquatorialHermite(prevX, prevDx, x, dxNow, hStep, tHit);
                        hitPCov = mix(prevP, p, tHit);
                        stepWeight = 0.72;
                        diskCrossings++;
                    } else {
                        hitP = x;
                        hitPCov = p;
                        float segLen = length(x - prevX);
                        float vProf = exp(-(hitP.y * hitP.y) / max(1e-5, slabHalfH * slabHalfH * 0.36));
                        stepWeight = min(0.28, segLen * 0.56) * vProf;
                    }

                    // Optional translucent orbital-plane quadrupole GW ripple overlay ([G])
                    if (crossed && uGwOverlay == 1 && diskCrossings <= 2) {
                        accumColor += transmittance * evalGwPlaneOverlay(hitP);
                    }

                    if (uShowDisk == 1 && length(hitP.xz) <= R_DISK_OUT) {
                        float mipLod = clamp(float(max(0, diskCrossings - 1)) * 0.75 + (rCenter / R_DISK_OUT) * 0.45, 0.0, 2.2);
                        float density, tempK, dust, shockBoost;
                        vec3 vPlasma;
                        evalBinaryPlasma(hitP, rPlus1, rPlus2, rPlusRem, mipLod,
                                         density, tempK, vPlasma, dust, shockBoost);

                        if (density > 0.0025) {
                            float gFactor = computePlasmaDopplerG(hitP, hitPCov, pt, obsFreq, vPlasma, hSum);
                            vec3 emitCol = sampleBlackbody(tempK * gFactor);

                            float g3 = gFactor * gFactor * gFactor;
                            float intensityBoost = (g3 / (1.0 + 0.06 * g3)) * shockBoost;
                            float emitSuppression = 1.0 - 0.70 * dust;
                            float emission = density * intensityBoost * emitSuppression * stepWeight * 0.78;
                            float opacity = clamp(density * stepWeight * (0.56 + 1.15 * dust), 0.0, 0.96);

                            accumColor += transmittance * emission * emitCol;
                            transmittance *= (1.0 - opacity);

                            if (!hasHit && opacity > 0.045) {
                                hasHit = true;
                                dominantPos = hitP;
                                dominantVel = vPlasma;
                                hitDist = length(hitP - uCamPos);
                            }
                        }
                    }
                }
            }
        }
    }

    if (captured) {
        escaped = false;
    }

    // Apply physical quadrupole gravitational-wave deflection to escaping skybox rays
    vec3 skyDir = applyQuadrupoleGwDeflection(exitDir, rCam);

    // Hardware 2x2 quad derivatives evaluated unconditionally in uniform control flow
    vec3 rawDdx = dFdx(skyDir);
    vec3 rawDdy = dFdy(skyDir);
    float maxGrad = 0.14;
    vec3 ddx = rawDdx * min(1.0, maxGrad / max(1e-6, length(rawDdx)));
    vec3 ddy = rawDdy * min(1.0, maxGrad / max(1e-6, length(rawDdy)));

    vec3 finalCol = accumColor;
    if (escaped && transmittance > 0.005) {
        finalCol += transmittance * sampleSkyWithGrid(skyDir, ddx, ddy);
    }

    // Compute exact differential screen-space TAA motion vector (FragVelocity = curProjUv - prevProjUv)
    // Using differential projection avoids lensing-deflection bias while tracking both camera and orbital motion.
    vec2 motionUv = vec2(0.0);
    if (hasHit) {
        vec3 curRel = dominantPos - uCamPos;
        float curZ = dot(curRel, uCamForward);
        // Scale geometric velocity to match visual dt step (GEOM_TIME_PER_SEC = 24.0)
        vec3 prevWorldPos = dominantPos - dominantVel * (uDt * 24.0);
        vec3 prevRel = prevWorldPos - uPrevCamPos;
        float prevZ = dot(prevRel, uPrevCamForward);
        if (curZ > 1e-4 && prevZ > 1e-4) {
            vec2 curProjUv = vec2(
                dot(curRel, uCamRight) / (curZ * aspect * uTanHalfFov),
                dot(curRel, uCamUp) / (curZ * uTanHalfFov)
            ) * 0.5 + 0.5;
            vec2 prevProjUv = vec2(
                dot(prevRel, uPrevCamRight) / (prevZ * aspect * uPrevTanHalfFov),
                dot(prevRel, uPrevCamUp) / (prevZ * uPrevTanHalfFov)
            ) * 0.5 + 0.5;
            motionUv = curProjUv - prevProjUv;
        }
    } else {
        // Infinity skybox reprojection using unjittered pixel direction
        vec2 unjNdc = curUv * 2.0 - 1.0;
        vec3 unjDir = safeNormalize(uCamForward
                                  + unjNdc.x * aspect * uTanHalfFov * uCamRight
                                  + unjNdc.y * uTanHalfFov * uCamUp, uCamForward);
        float prevZ = dot(unjDir, uPrevCamForward);
        if (prevZ > 1e-4) {
            vec2 prevUv = vec2(
                dot(unjDir, uPrevCamRight) / (prevZ * aspect * uPrevTanHalfFov),
                dot(unjDir, uPrevCamUp) / (prevZ * uPrevTanHalfFov)
            ) * 0.5 + 0.5;
            motionUv = curUv - prevUv;
        }
    }
    if (isnan(motionUv.x) || isinf(motionUv.x) || isnan(motionUv.y) || isinf(motionUv.y)) {
        motionUv = vec2(0.0);
    }

    FragColor = vec4(sanitizeHdr(finalCol), hitDist);
    FragVelocity = clamp(motionUv, vec2(-0.5), vec2(0.5));
}
