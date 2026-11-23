#version 430 core
// Note: BlackHoleRenderer injects '#define USE_DOPRI54 0' or '1' after #version for specialized RK4 / DOPRI5(4) programs.
#ifndef USE_DOPRI54
#define USE_DOPRI54 0
#endif

layout(location = 0) out vec4 FragColor;
layout(location = 1) out vec2 FragVelocity;

uniform vec2 uResolution;
uniform float uTime;
uniform float uGeomDt;
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

// Binary black hole EOB state from BinaryMergerPhysics (G = c = M_tot = 1)
uniform vec3 uPos1;
uniform vec3 uPos2;
uniform vec3 uVel1;
uniform vec3 uVel2;
uniform float uMass1;
uniform float uMass2;
uniform float uChi1;
uniform float uChi2;
uniform float uRemnantMass;
uniform float uRemnantSpin;      // Signed dimensionless chi_f; physical Kerr spin a_f = M_f * chi_f
uniform float uMergeBlend;       // 0 during inspiral/plunge, 0..1 coalescence, 1 ringdown
uniform float uSeparation;
uniform float uOrbitalPhase;
uniform float uGwPhase;
uniform float uGwOmegaGeom;      // Dimensionless M_tot * omega_22
uniform float uStrainAmp;        // Normalized visualization strain amplitude [0..1]
uniform float uRingdownEnv;
uniform float uMergerPulse;

// Orthonormal 3D orbital plane basis (e1, lHat, e3)
uniform vec3 uOrbE1;
uniform vec3 uOrbLHat;
uniform vec3 uOrbE3;

uniform float uDiskTempScale;
uniform float uDiskDensity;
uniform int uDoppler;
uniform int uLensing;
uniform int uShowDisk;
uniform int uShowGrid;
uniform int uGwOverlay;
uniform int uDiagMode;
#if USE_DOPRI54
uniform float uIntegTol;
#endif
uniform vec2 uJitter;
uniform int uFrameIndex;

const float PI = 3.14159265359;
const float R_DISK_OUT = 28.0;
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

vec3 turboColormap(float t) {
    float x = clamp(t, 0.0, 1.0);
    vec3 c0 = vec3(0.11, 0.08, 0.34);
    vec3 c1 = vec3(0.09, 0.56, 0.78);
    vec3 c2 = vec3(0.55, 0.88, 0.24);
    vec3 c3 = vec3(0.98, 0.55, 0.09);
    vec3 c4 = vec3(0.84, 0.12, 0.14);
    if (x < 0.25) return mix(c0, c1, x * 4.0);
    if (x < 0.50) return mix(c1, c2, (x - 0.25) * 4.0);
    if (x < 0.75) return mix(c2, c3, (x - 0.50) * 4.0);
    return mix(c3, c4, (x - 0.75) * 4.0);
}

vec3 sampleBlackbody(float tempKelvin) {
    float norm = clamp((tempKelvin - 900.0) * (1.0 / 37100.0), 0.0, 1.0);
    float u = (0.5 + sqrt(norm) * 511.0) * (1.0 / 512.0);
    vec4 bb = texture(uBlackbodyLut, vec2(u, 0.5));
    return bb.rgb * bb.a;
}

float interleavedGradientNoise(vec2 px) {
    return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

vec3 worldToOrb(vec3 v) {
    return vec3(dot(v, uOrbE1), dot(v, uOrbLHat), dot(v, uOrbE3));
}

vec3 orbToWorld(vec3 v) {
    return v.x * uOrbE1 + v.y * uOrbLHat + v.z * uOrbE3;
}

// ============================================================================
// ANALYTICAL EFFECTIVE BINARY SPACETIME (INTEGRATED IN ORBITAL FRAME FOR ZERO
// PER-STEP BASIS ROTATIONS):
// SUPERPOSED SPINNING KERR-SCHILD + 1PN GRAVITOMAGNETIC ORBITAL SHIFT +
// SPINNING KERR-SCHILD REMNANT WITH EXACT (l=2, m=2) QNM GRADIENT
//
// Explicit validity boundary:
//  - Exact spinning Kerr-Schild solution in the isolated single-body limit (m2=0, v1=0).
//  - Exact spinning Kerr-Schild remnant (a_f = M_f * chi_f) post-merger (mergeBlend == 1).
//  - Linear 1PN gravitomagnetic shift avoids artificial frozen-slice Killing-horizon walls
//    while preserving exact Kerr component horizons r_{+,i} = m_i (1 + sqrt(1 - chi_i^2)).
//  - Analytical effective superposition (NOT a 3+1 Numerical Relativity spacetime).
//  - Exact canonical derivatives guarantee d(H_bin)/d(lambda) == 0 identically.
// ============================================================================

float kerrRadius(vec3 loc, float a2) {
    float R2 = dot(loc, loc);
    if (a2 <= 1e-7) {
        return sqrt(max(1e-5, R2));
    }
    float b = R2 - a2;
    float disc = max(0.0, b * b + 4.0 * a2 * loc.y * loc.y);
    return sqrt(max(1e-4, 0.5 * (b + sqrt(disc))));
}

float evalRemnantQnmModScalar(vec3 loc, float rRem) {
    if (uRingdownEnv <= 0.002) {
        return 1.0;
    }
    float rho2 = dot(loc.xz, loc.xz);
    float R2 = max(1e-4, rho2 + loc.y * loc.y);
    float sin2Th = rho2 / R2;
    float invRho2 = 1.0 / max(1e-5, rho2);
    float cos2Phi = (rho2 > 1e-5) ? ((loc.x * loc.x - loc.z * loc.z) * invRho2) : 1.0;
    float sin2Phi = (rho2 > 1e-5) ? ((2.0 * loc.x * loc.z) * invRho2) : 0.0;
    float cosPhase = cos(uGwPhase) * cos2Phi + sin(uGwPhase) * sin2Phi;
    float radialDecay = exp(-max(0.0, rRem - 1.5) * 0.45);
    return 1.0 + 0.065 * uRingdownEnv * radialDecay * sin2Th * cosPhase;
}

// Spinning Kerr-Schild + 1PN gravitomagnetic orbital shift in orbital coordinates (zero mat3 multiplies!)
void spinningBodyContribOrb(vec3 x, vec3 p, float pt,
                            vec3 bodyPosOrb, vec3 bodyVelOrb, float mw, float a, float a2,
                            inout vec3 dxAcc, inout vec3 dpAcc, out float rBody, out float hVal) {
    vec3 loc = x - bodyPosOrb;
    float vp = dot(bodyVelOrb, p);

    if (a2 <= 1e-7) {
        // Exact algebraically identical fast Schwarzschild-KS path when |chi_i| ~ 0
        float d2 = dot(loc, loc);
        rBody = sqrt(max(1e-5, d2));
        float invR = 1.0 / rBody;
        vec3 nHat = loc * invR;
        hVal = mw * invR;
        float np = dot(nHat, p);
        float q = -pt + np;

        dxAcc -= (2.0 * hVal * q) * nHat + (2.0 * hVal * pt) * bodyVelOrb;

        vec3 gradH = (-hVal * invR) * nHat;
        vec3 gradNP = (p - np * nHat) * invR;
        dpAcc += (q * q + 2.0 * pt * vp) * gradH + (2.0 * hVal * q) * gradNP;
        return;
    }

    rBody = kerrRadius(loc, a2);
    float r2 = rBody * rBody;
    float invR = 1.0 / rBody;
    float D = max(1e-5, r2 * r2 + a2 * loc.y * loc.y);
    float invD = 1.0 / D;
    float S = max(1e-4, r2 + a2);
    float invS = 1.0 / S;

    hVal = (mw * r2 * rBody) * invD;
    vec3 gradR = (rBody * invD) * vec3(r2 * loc.x, S * loc.y, r2 * loc.z);
    vec3 gradH = mw * (r2 * (3.0 * a2 * loc.y * loc.y - r2 * r2) * gradR
               - vec3(0.0, 2.0 * a2 * r2 * rBody * loc.y, 0.0)) * (invD * invD);

    vec3 lVec = vec3(
        (rBody * loc.x + a * loc.z) * invS,
        loc.y * invR,
        (rBody * loc.z - a * loc.x) * invS
    );

    float q = -pt + dot(lVec, p);
    dxAcc -= (2.0 * hVal * q) * lVec + (2.0 * hVal * pt) * bodyVelOrb;

    float xpZp = loc.x * p.x + loc.z * p.z;
    float zpxXpz = loc.z * p.x - loc.x * p.z;
    vec3 directLP = vec3(
        (rBody * p.x - a * p.z) * invS,
        p.y * invR,
        (rBody * p.z + a * p.x) * invS
    );
    float dlpDr = ((a2 - r2) * xpZp - 2.0 * a * rBody * zpxXpz) * (invS * invS)
                - (loc.y * p.y) * (invR * invR);
    vec3 gradLP = directLP + dlpDr * gradR;

    dpAcc += (q * q + 2.0 * pt * vp) * gradH + (2.0 * hVal * q) * gradLP;
}

// Exact spinning Kerr-Schild remnant contribution in orbital coordinates (physical spin a_f = M_f * chi_f)
void remnantKerrContribOrb(vec3 loc, vec3 p, float pt, float mwRem, float aPhys, float aPhys2,
                           inout vec3 dxAcc, inout vec3 dpAcc, out float rRem, out float hRem) {
    rRem = kerrRadius(loc, aPhys2);
    float invR = 1.0 / rRem;
    float r2 = rRem * rRem;
    float D = max(1e-5, r2 * r2 + aPhys2 * loc.y * loc.y);
    float invD = 1.0 / D;
    float S = max(1e-4, r2 + aPhys2);
    float invS = 1.0 / S;

    float hBase = (mwRem * r2 * rRem) * invD;
    vec3 gradR = (rRem * invD) * vec3(r2 * loc.x, S * loc.y, r2 * loc.z);
    vec3 gradHBase = mwRem * (r2 * (3.0 * aPhys2 * loc.y * loc.y - r2 * r2) * gradR
                   - vec3(0.0, 2.0 * aPhys2 * r2 * rRem * loc.y, 0.0)) * (invD * invD);

    float qnmMod = 1.0;
    vec3 gradQnmMod = vec3(0.0);
    if (uRingdownEnv > 0.002) {
        float rho2 = dot(loc.xz, loc.xz);
        float R2 = max(1e-4, rho2 + loc.y * loc.y);
        float invR2 = 1.0 / R2;
        float sin2Th = rho2 * invR2;
        float invRho2 = 1.0 / max(1e-5, rho2);
        float cos2Phi = (rho2 > 1e-5) ? ((loc.x * loc.x - loc.z * loc.z) * invRho2) : 1.0;
        float sin2Phi = (rho2 > 1e-5) ? ((2.0 * loc.x * loc.z) * invRho2) : 0.0;
        float cGw = cos(uGwPhase);
        float sGw = sin(uGwPhase);
        float cosPhase = cGw * cos2Phi + sGw * sin2Phi;
        float sinPhase = sGw * cos2Phi - cGw * sin2Phi;
        float radialDecay = exp(-max(0.0, rRem - 1.5) * 0.45);
        float amp = 0.065 * uRingdownEnv * radialDecay;
        qnmMod = 1.0 + amp * sin2Th * cosPhase;

        vec3 gradSin2Th = (2.0 * invR2) * vec3(loc.x * (1.0 - sin2Th), -loc.y * sin2Th, loc.z * (1.0 - sin2Th));
        vec3 gradPhi = (rho2 > 1e-5) ? (vec3(-loc.z, 0.0, loc.x) * invRho2) : vec3(0.0);
        // sinPhase = sin(uGwPhase - 2*phi) = -sin(2*phi - uGwPhase), so d/dphi cos(2*phi - uGwPhase) = +2*sinPhase
        vec3 gradCosPhase = (2.0 * sinPhase) * gradPhi;
        float dDecayDr = (rRem > 1.5) ? (-0.45 * radialDecay) : 0.0;
        gradQnmMod = (amp * cosPhase) * gradSin2Th
                   + (amp * sin2Th) * gradCosPhase
                   + (0.065 * uRingdownEnv * sin2Th * cosPhase * dDecayDr) * gradR;
    }

    hRem = hBase * qnmMod;
    vec3 gradH = gradHBase * qnmMod + hBase * gradQnmMod;

    vec3 lVec = vec3(
        (rRem * loc.x + aPhys * loc.z) * invS,
        loc.y * invR,
        (rRem * loc.z - aPhys * loc.x) * invS
    );

    float q = -pt + dot(lVec, p);
    dxAcc -= (2.0 * hRem * q) * lVec;

    float xpZp = loc.x * p.x + loc.z * p.z;
    float zpxXpz = loc.z * p.x - loc.x * p.z;
    vec3 directLP = vec3(
        (rRem * p.x - aPhys * p.z) * invS,
        p.y * invR,
        (rRem * p.z + aPhys * p.x) * invS
    );
    float dlpDr = ((aPhys2 - r2) * xpZp - 2.0 * aPhys * rRem * zpxXpz) * (invS * invS)
                - (loc.y * p.y) * (invR * invR);
    vec3 gradLP = directLP + dlpDr * gradR;

    dpAcc += (q * q) * gradH + (2.0 * hRem * q) * gradLP;
}

void binaryHamiltonDerivOrb(vec3 x, vec3 p, float pt,
                            vec3 p1Orb, vec3 p2Orb, vec3 v1Orb, vec3 v2Orb,
                            float mw1, float mw2, float a1, float a1Sq, float a2, float a2Sq,
                            float mwRem, float aRemPhys, float aRemPhys2,
                            out vec3 dx, out vec3 dp,
                            out float r1, out float r2, out float rRem, out float hSum) {
    dx = p;
    dp = vec3(0.0);
    r1 = 1e4;
    r2 = 1e4;
    rRem = 1e4;
    hSum = 0.0;

    if (mw1 + mw2 > 1e-4) {
        float h1, h2;
        spinningBodyContribOrb(x, p, pt, p1Orb, v1Orb, mw1, a1, a1Sq, dx, dp, r1, h1);
        spinningBodyContribOrb(x, p, pt, p2Orb, v2Orb, mw2, a2, a2Sq, dx, dp, r2, h2);
        hSum += h1 + h2;
    }
    if (mwRem > 1e-4) {
        float hR;
        remnantKerrContribOrb(x, p, pt, mwRem, aRemPhys, aRemPhys2, dx, dp, rRem, hR);
        hSum += hR;
    }
}

float binaryHamiltonianValOrb(vec3 x, vec3 p, float pt,
                              vec3 p1Orb, vec3 p2Orb, vec3 v1Orb, vec3 v2Orb,
                              float mw1, float mw2, float a1, float a1Sq, float a2, float a2Sq,
                              float mwRem, float aRemPhys, float aRemPhys2) {
    float val = -pt * pt + dot(p, p);
    if (mw1 + mw2 > 1e-4) {
        vec3 d1 = x - p1Orb;
        float r1 = kerrRadius(d1, a1Sq);
        float r1Sq = r1 * r1;
        float h1 = (mw1 * r1Sq * r1) / max(1e-5, r1Sq * r1Sq + a1Sq * d1.y * d1.y);
        float invS1 = 1.0 / max(1e-4, r1Sq + a1Sq);
        vec3 l1 = vec3((r1 * d1.x + a1 * d1.z) * invS1, d1.y / r1, (r1 * d1.z - a1 * d1.x) * invS1);
        float q1 = -pt + dot(l1, p);
        val -= 2.0 * h1 * (q1 * q1 + 2.0 * pt * dot(v1Orb, p));

        vec3 d2 = x - p2Orb;
        float r2 = kerrRadius(d2, a2Sq);
        float r2Sq = r2 * r2;
        float h2 = (mw2 * r2Sq * r2) / max(1e-5, r2Sq * r2Sq + a2Sq * d2.y * d2.y);
        float invS2 = 1.0 / max(1e-4, r2Sq + a2Sq);
        vec3 l2 = vec3((r2 * d2.x + a2 * d2.z) * invS2, d2.y / r2, (r2 * d2.z - a2 * d2.x) * invS2);
        float q2 = -pt + dot(l2, p);
        val -= 2.0 * h2 * (q2 * q2 + 2.0 * pt * dot(v2Orb, p));
    }
    if (mwRem > 1e-4) {
        float rRem = kerrRadius(x, aRemPhys2);
        float r2 = rRem * rRem;
        float D = max(1e-5, r2 * r2 + aRemPhys2 * x.y * x.y);
        float hR = ((mwRem * r2 * rRem) / D) * evalRemnantQnmModScalar(x, rRem);
        float invS = 1.0 / max(1e-4, r2 + aRemPhys2);
        vec3 lR = vec3((rRem * x.x + aRemPhys * x.z) * invS, x.y / rRem, (rRem * x.z - aRemPhys * x.x) * invS);
        float qR = -pt + dot(lR, p);
        val -= 2.0 * hR * qR * qR;
    }
    return 0.5 * val;
}

#if USE_DOPRI54
bool binaryDopriStepOrb(inout vec3 x, inout vec3 p, inout float h, float hMin, float hMax, float pt,
                        vec3 p1Orb, vec3 p2Orb, vec3 v1Orb, vec3 v2Orb,
                        float mw1, float mw2, float a1, float a1Sq, float a2, float a2Sq,
                        float mwRem, float aRemPhys, float aRemPhys2,
                        inout vec3 k1x, inout vec3 k1p,
                        out float r1, out float r2, out float rRem, out float hSum) {
    vec3 k2x, k2p, k3x, k3p, k4x, k4p, k5x, k5p, k6x, k6p, k7x, k7p;
    float t1, t2, tR, tH;

    binaryHamiltonDerivOrb(x + h * (0.2 * k1x), p + h * (0.2 * k1p), pt,
                           p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                           k2x, k2p, t1, t2, tR, tH);
    binaryHamiltonDerivOrb(x + h * (0.075 * k1x + 0.225 * k2x), p + h * (0.075 * k1p + 0.225 * k2p), pt,
                           p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                           k3x, k3p, t1, t2, tR, tH);
    binaryHamiltonDerivOrb(x + h * ((44.0/45.0)*k1x - (56.0/15.0)*k2x + (32.0/9.0)*k3x),
                           p + h * ((44.0/45.0)*k1p - (56.0/15.0)*k2p + (32.0/9.0)*k3p), pt,
                           p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                           k4x, k4p, t1, t2, tR, tH);
    binaryHamiltonDerivOrb(x + h * ((19372.0/6561.0)*k1x - (25360.0/2187.0)*k2x + (64448.0/6561.0)*k3x - (212.0/729.0)*k4x),
                           p + h * ((19372.0/6561.0)*k1p - (25360.0/2187.0)*k2p + (64448.0/6561.0)*k3p - (212.0/729.0)*k4p), pt,
                           p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                           k5x, k5p, t1, t2, tR, tH);
    binaryHamiltonDerivOrb(x + h * ((9017.0/3168.0)*k1x - (355.0/33.0)*k2x + (46732.0/5247.0)*k3x + (49.0/176.0)*k4x - (5103.0/18656.0)*k5x),
                           p + h * ((9017.0/3168.0)*k1p - (355.0/33.0)*k2p + (46732.0/5247.0)*k3p + (49.0/176.0)*k4p - (5103.0/18656.0)*k5p), pt,
                           p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                           k6x, k6p, t1, t2, tR, tH);

    vec3 xNext = x + h * ((35.0/384.0)*k1x + (500.0/1113.0)*k3x + (125.0/192.0)*k4x - (2187.0/6784.0)*k5x + (11.0/84.0)*k6x);
    vec3 pNext = p + h * ((35.0/384.0)*k1p + (500.0/1113.0)*k3p + (125.0/192.0)*k4p - (2187.0/6784.0)*k5p + (11.0/84.0)*k6p);

    binaryHamiltonDerivOrb(xNext, pNext, pt,
                           p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                           k7x, k7p, r1, r2, rRem, hSum);

    vec3 errX = h * ((71.0/57600.0)*k1x - (71.0/16695.0)*k3x + (71.0/1920.0)*k4x - (17253.0/339200.0)*k5x + (22.0/525.0)*k6x - 0.025*k7x);
    vec3 errP = h * ((71.0/57600.0)*k1p - (71.0/16695.0)*k3p + (71.0/1920.0)*k4p - (17253.0/339200.0)*k5p + (22.0/525.0)*k6p - 0.025*k7p);

    float tol = max(1e-5, uIntegTol);
    float scX = tol * max(1.0, max(length(x), length(xNext)));
    float scP = tol * max(1.0, max(length(p), length(pNext)));
    float errNorm = max(length(errX) / scX, length(errP) / scP);

    if (errNorm <= 1.0 || h <= hMin * 1.05) {
        x = xNext;
        p = pNext;
        k1x = k7x;
        k1p = k7p;
        float grow = clamp(0.9 * pow(max(errNorm, 1e-6), -0.2), 0.35, 2.2);
        h = clamp(h * grow, hMin, hMax);
        return true;
    } else {
        float shrink = clamp(0.85 * pow(errNorm, -0.2), 0.2, 0.75);
        h = max(hMin, h * shrink);
        return false;
    }
}
#else
void binaryGeodesicStepOrb(inout vec3 x, inout vec3 p, float h, float pt,
                           vec3 p1Orb, vec3 p2Orb, vec3 v1Orb, vec3 v2Orb,
                           float mw1, float mw2, float a1, float a1Sq, float a2, float a2Sq,
                           float mwRem, float aRemPhys, float aRemPhys2,
                           inout vec3 dxNow, inout vec3 dpNow,
                           out float r1, out float r2, out float rRem, out float hSum) {
    vec3 accX = dxNow;
    vec3 accP = dpNow;
    vec3 kx, kp;
    float t1, t2, tR, tH;

    binaryHamiltonDerivOrb(x + (0.5 * h) * dxNow, p + (0.5 * h) * dpNow, pt,
                           p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                           kx, kp, t1, t2, tR, tH);
    accX += 2.0 * kx;
    accP += 2.0 * kp;

    binaryHamiltonDerivOrb(x + (0.5 * h) * kx, p + (0.5 * h) * kp, pt,
                           p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                           kx, kp, t1, t2, tR, tH);
    accX += 2.0 * kx;
    accP += 2.0 * kp;

    binaryHamiltonDerivOrb(x + h * kx, p + h * kp, pt,
                           p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                           kx, kp, t1, t2, tR, tH);
    accX += kx;
    accP += kp;

    float h6 = h * (1.0 / 6.0);
    x += h6 * accX;
    p += h6 * accP;
    binaryHamiltonDerivOrb(x, p, pt,
                           p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                           dxNow, dpNow, r1, r2, rRem, hSum);
}
#endif

// Cubic Hermite root-finding for orbital plane y == 0 (already in orbital coordinates!)
vec3 intersectOrbitalPlaneHermite(vec3 x0, vec3 dx0, vec3 x1, vec3 dx1, float h, out float tHit) {
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

vec3 applyQuadrupoleGwDeflection(vec3 dirWorld, float camDist) {
    if (uStrainAmp < 0.002) {
        return dirWorld;
    }
    vec3 orbDir = worldToOrb(dirWorld);
    float cosTh = clamp(orbDir.y, -1.0, 1.0);
    float fPlus = 0.5 * (1.0 + cosTh * cosTh);
    float fCross = cosTh;

    float phi = safeAtan2(orbDir.z, orbDir.x);
    float kGw = clamp(uGwOmegaGeom * 1.45, 0.14, 0.92);
    float retPhase = uGwPhase - camDist * kGw * 0.45 - 2.0 * phi + 3.2 * (1.0 - abs(cosTh));

    float hPlus = uStrainAmp * fPlus * cos(retPhase);
    float hCross = uStrainAmp * fCross * sin(retPhase);

    vec3 ePhiOrb = safeNormalize(vec3(-orbDir.z, 0.0, orbDir.x), vec3(1.0, 0.0, 0.0));
    vec3 eThetaOrb = cross(ePhiOrb, orbDir);
    vec3 ePhi = orbToWorld(ePhiOrb);
    vec3 eTheta = orbToWorld(eThetaOrb);

    return safeNormalize(dirWorld + (hPlus * 0.0115) * eTheta + (hCross * 0.0115) * ePhi, dirWorld);
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

float computePlasmaDopplerG(vec3 pCovOrb, float pt, float obsFreq, vec3 vPlasmaOrb, float hSum) {
    if (uDoppler == 0) {
        return 1.0;
    }
    float v2 = min(0.72, dot(vPlasmaOrb, vPlasmaOrb));
    float lapse = max(0.14, 1.0 - min(0.84, 2.0 * hSum));
    float ut = inversesqrt(max(0.08, lapse - v2));
    float emitFreq = ut * (pt + dot(pCovOrb, vPlasmaOrb));
    return clamp(obsFreq / max(0.10, emitFreq), 0.22, 2.75);
}

void evalBinaryPlasmaOrb(vec3 hitOrb, vec3 pos1Orb, vec3 pos2Orb, vec3 vel1Orb, vec3 vel2Orb,
                         float rPlus1, float rPlus2, float rPlusRem, float mipLod,
                         out float outDensity, out float outTempK, out vec3 outVelOrb,
                         out float outDust, out float outShockBoost) {
    outDensity = 0.0;
    outTempK = 6000.0;
    outVelOrb = vec3(0.0);
    outDust = 0.0;
    outShockBoost = 1.0;

    float r = length(hitOrb.xz);
    float phi = safeAtan2(hitOrb.z, hitOrb.x);
    float wBin = 1.0 - uMergeBlend;

    float rCav = mix(max(3.6, 1.85 * uSeparation), max(2.05, 1.45 * rPlusRem), uMergeBlend);
    if (r > rCav * 0.86 && r <= R_DISK_OUT) {
        float uNorm = clamp((r - rCav) / max(1.0, R_DISK_OUT - rCav), 0.0, 1.0);
        float relPhi = phi - uOrbitalPhase;
        float logSpiral = relPhi * 2.0 + 5.8 * log(max(1.0, r / max(1.5, rCav)));

        float rWrap = 0.24 + uNorm * 0.58;
        vec3 volCoord = vec3(
            cos(logSpiral * 0.5) * rWrap + uNorm * 0.42,
            hitOrb.y * 0.48 - uTime * 0.018,
            sin(logSpiral * 0.5) * rWrap + uNorm * 0.78
        );
        float turbMacro = textureLod(uNoiseVol, volCoord * 0.75, mipLod).r;
        float turbMid = textureLod(uNoiseVol, volCoord * 1.85 + vec3(0.27, -0.15, 0.31), mipLod).r;

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
            outVelOrb = omegaK * vec3(-hitOrb.z, 0.0, hitOrb.x);
            outDust = smoothstep(0.22, 0.80, uNorm) * smoothstep(0.42, 0.74, turbMid);
            outTempK = (6800.0 * uDiskTempScale) * pow(max(2.0, rCav) / max(2.0, r), 0.72)
                     * (0.62 + 0.56 * structure + 0.38 * rimGlow) * (1.0 - 0.50 * outDust);
            outDensity = cbDens;
        }
    }

    if (wBin > 0.01) {
        vec2 d1 = hitOrb.xz - pos1Orb.xz;
        float r1 = length(d1);
        float rMiniIn1 = rPlus1 * 1.12;
        float rMiniOut1 = max(rPlus1 * 2.15, 0.48 * uSeparation * pow(uMass1, 0.33));
        if (r1 > rMiniIn1 && r1 < rMiniOut1) {
            float u1 = (r1 - rMiniIn1) / max(0.15, rMiniOut1 - rMiniIn1);
            float phi1 = safeAtan2(d1.y, d1.x);
            float sp1 = phi1 - uTime * 3.8 + 4.5 * log(max(0.15, r1 / rMiniIn1));
            float t1 = textureLod(uNoiseVol, vec3(cos(sp1) * 0.32, hitOrb.y * 0.8, sin(sp1) * 0.32), mipLod).r;
            float env1 = smoothstep(0.0, 0.14, u1) * pow(max(0.0, 1.0 - u1), 1.15);
            float md1 = env1 * (0.55 + 0.72 * t1) * wBin * 1.15 * uDiskDensity;
            if (md1 > 0.002) {
                float vK1 = min(0.48, sqrt(uMass1 / max(0.35, r1)));
                vec3 v1Tot = vel1Orb + vK1 * vec3(-d1.y / r1, 0.0, d1.x / r1);
                float temp1 = (10800.0 * uDiskTempScale) * (1.24 - 0.48 * u1) * (0.82 + 0.36 * t1);
                float blend1 = md1 / max(1e-4, outDensity + md1);
                outVelOrb = mix(outVelOrb, v1Tot, blend1);
                outTempK = mix(outTempK, temp1, blend1);
                outDust *= (1.0 - blend1);
                outDensity += md1;
            }
        }

        vec2 d2 = hitOrb.xz - pos2Orb.xz;
        float r2 = length(d2);
        float rMiniIn2 = rPlus2 * 1.12;
        float rMiniOut2 = max(rPlus2 * 2.15, 0.48 * uSeparation * pow(uMass2, 0.33));
        if (r2 > rMiniIn2 && r2 < rMiniOut2) {
            float u2 = (r2 - rMiniIn2) / max(0.15, rMiniOut2 - rMiniIn2);
            float phi2 = safeAtan2(d2.y, d2.x);
            float sp2 = phi2 - uTime * 4.4 + 4.5 * log(max(0.15, r2 / rMiniIn2));
            float t2 = textureLod(uNoiseVol, vec3(cos(sp2) * 0.32, hitOrb.y * 0.8, sin(sp2) * 0.32), mipLod).r;
            float env2 = smoothstep(0.0, 0.14, u2) * pow(max(0.0, 1.0 - u2), 1.15);
            float md2 = env2 * (0.55 + 0.72 * t2) * wBin * 1.18 * uDiskDensity;
            if (md2 > 0.002) {
                float vK2 = min(0.48, sqrt(uMass2 / max(0.30, r2)));
                vec3 v2Tot = vel2Orb + vK2 * vec3(-d2.y / r2, 0.0, d2.x / r2);
                float temp2 = (11400.0 * uDiskTempScale) * (1.26 - 0.48 * u2) * (0.82 + 0.36 * t2);
                float blend2 = md2 / max(1e-4, outDensity + md2);
                outVelOrb = mix(outVelOrb, v2Tot, blend2);
                outTempK = mix(outTempK, temp2, blend2);
                outDust *= (1.0 - blend2);
                outDensity += md2;
            }
        }

        vec2 seg = pos2Orb.xz - pos1Orb.xz;
        float segLen2 = dot(seg, seg);
        if (segLen2 > 0.25 && r1 > rPlus1 * 1.04 && r2 > rPlus2 * 1.04) {
            float tSeg = clamp(dot(hitOrb.xz - pos1Orb.xz, seg) / segLen2, 0.0, 1.0);
            vec2 closest = pos1Orb.xz + seg * tSeg;
            float distBridge = length(hitOrb.xz - closest);
            float bridgeW = 0.34 + 0.16 * uSeparation * 0.18 + 0.24 * sin(tSeg * PI);
            if (distBridge < bridgeW * 2.5) {
                float bNoise = textureLod(uNoiseVol, vec3(hitOrb.x * 0.28, hitOrb.y * 0.65, hitOrb.z * 0.28 - uTime * 0.18), mipLod).r;
                float sCurve = 0.24 * sin(tSeg * 2.0 * PI);
                vec2 perpDir = normalize(vec2(-seg.y, seg.x));
                float curvedDist = abs(dot(hitOrb.xz - closest, perpDir) - sCurve * bridgeW);
                float profile = exp(-(curvedDist * curvedDist) / max(0.025, bridgeW * bridgeW * 0.60));
                float ends = smoothstep(0.03, 0.18, tSeg) * smoothstep(0.97, 0.82, tSeg);
                float shockIntensity = 1.25 + 1.25 * smoothstep(6.5, 2.2, uSeparation) + 1.40 * uMergerPulse;
                float bDens = profile * ends * (0.60 + 0.85 * bNoise) * wBin * 1.38 * uDiskDensity;
                if (bDens > 0.002) {
                    vec3 vBridge = mix(vel1Orb, vel2Orb, tSeg);
                    float tempBridge = (11600.0 * uDiskTempScale) * (0.88 + 0.46 * bNoise) * (1.0 + 0.32 * uMergerPulse);
                    float blendB = bDens / max(1e-4, outDensity + bDens);
                    outVelOrb = mix(outVelOrb, vBridge, blendB);
                    outTempK = mix(outTempK, tempBridge, blendB);
                    outDust *= (1.0 - blendB);
                    outShockBoost = max(outShockBoost, shockIntensity);
                    outDensity += bDens;
                }
            }
        }

        if (r > uSeparation * 0.45 && r < rCav * 1.08) {
            float uStream = clamp((r - uSeparation * 0.45) / max(0.5, rCav - uSeparation * 0.45), 0.0, 1.0);
            float streamAngle = 2.0 * (phi - uOrbitalPhase + 1.35 * uStream);
            float ridge = pow(max(0.0, cos(streamAngle)), 8.0);
            if (ridge > 0.04) {
                float sTurb = textureLod(uNoiseVol, vec3(hitOrb.x * 0.16, hitOrb.y * 0.5, hitOrb.z * 0.16), mipLod).r;
                float sDens = ridge * (0.45 + 0.75 * sTurb) * smoothstep(0.0, 0.25, uStream)
                            * smoothstep(1.08, 0.75, uStream) * wBin * 0.72 * uDiskDensity;
                if (sDens > 0.002 && r1 > rPlus1 * 1.1 && r2 > rPlus2 * 1.1) {
                    float omegaS = 1.0 / (r * sqrt(max(1.0, r)));
                    vec3 vS = omegaS * vec3(-hitOrb.z, 0.0, hitOrb.x)
                            - 0.18 * normalize(vec3(hitOrb.x, 0.0, hitOrb.z));
                    float tempS = (12200.0 * uDiskTempScale) * (1.15 - 0.35 * uStream);
                    float blendS = sDens / max(1e-4, outDensity + sDens);
                    outVelOrb = mix(outVelOrb, vS, blendS);
                    outTempK = mix(outTempK, tempS, blendS);
                    outDensity += sDens;
                }
            }
        }
    }

    if (uMergeBlend > 0.05 && r > rPlusRem * 1.12 && r < 6.5) {
        float flashR = mix(2.3, 4.6, 1.0 - uRingdownEnv);
        float ringEnv = exp(-pow((r - flashR) / 1.15, 2.0));
        float fTurb = textureLod(uNoiseVol, vec3(hitOrb.x * 0.22, uTime * 0.12, hitOrb.z * 0.22), mipLod).r;
        float fDens = ringEnv * (0.45 + 0.75 * fTurb) * uMergeBlend * (0.35 + 0.95 * uRingdownEnv) * uDiskDensity;
        if (fDens > 0.002) {
            float omegaR = 1.0 / (r * sqrt(max(1.0, r)) + uRemnantMass * uRemnantSpin);
            vec3 vR = omegaR * vec3(-hitOrb.z, 0.0, hitOrb.x);
            float tempR = (14500.0 * uDiskTempScale) * (0.85 + 0.55 * uRingdownEnv);
            float blendF = fDens / max(1e-4, outDensity + fDens);
            outVelOrb = mix(outVelOrb, vR, blendF);
            outTempK = mix(outTempK, tempR, blendF);
            outShockBoost = max(outShockBoost, 1.0 + 0.85 * uMergerPulse);
            outDensity += fDens;
        }
    }
}

vec3 evalGwPlaneOverlayOrb(vec3 hitOrb) {
    float r = length(hitOrb.xz);
    float rMin = max(2.6, 1.05 * uSeparation);
    if (r < rMin || r > 38.0) {
        return vec3(0.0);
    }
    float phi = safeAtan2(hitOrb.z, hitOrb.x);
    float kGw = clamp(uGwOmegaGeom * 1.55, 0.16, 0.96);
    float wavePhase = uGwPhase - r * kGw - 2.0 * phi;
    float wave = cos(wavePhase);

    float fadeIn = smoothstep(rMin, rMin + 2.8, r);
    float fadeOut = smoothstep(38.0, 18.0, r) / sqrt(max(2.5, r * 0.30));
    float amp = max(0.28, uStrainAmp) * fadeIn * fadeOut;

    float posCrest = smoothstep(0.76, 0.99, wave);
    float negCrest = smoothstep(0.76, 0.99, -wave);
    float isoLine = 1.0 - smoothstep(0.0, 0.075, abs(abs(wave) - 0.86));

    vec3 posCol = vec3(0.14, 0.82, 1.00) * (posCrest * 0.24 + isoLine * max(0.0, wave) * 0.52);
    vec3 negCol = vec3(0.64, 0.26, 1.00) * (negCrest * 0.20 + isoLine * max(0.0, -wave) * 0.44);
    return (posCol + negCol) * amp * 0.40;
}

void main() {
    vec2 curUv = gl_FragCoord.xy / uResolution;
    vec2 jitteredPx = gl_FragCoord.xy + uJitter;
    vec2 ndc = (jitteredPx / uResolution) * 2.0 - 1.0;
    float aspect = uResolution.x / uResolution.y;

    vec3 vLocalWorld = safeNormalize(uCamForward
                                   + ndc.x * aspect * uTanHalfFov * uCamRight
                                   + ndc.y * uTanHalfFov * uCamUp, uCamForward);

    // Pre-transform all constant per-frame vectors into orbital frame ONCE (eliminates 32 mat3 multiplies/step!)
    vec3 camPosOrb = worldToOrb(uCamPos);
    vec3 vLocal = worldToOrb(vLocalWorld);
    vec3 p1Orb = worldToOrb(uPos1);
    vec3 p2Orb = worldToOrb(uPos2);
    vec3 v1Orb = worldToOrb(uVel1);
    vec3 v2Orb = worldToOrb(uVel2);

    float wBin = 1.0 - uMergeBlend;
    float mw1 = uMass1 * wBin;
    float mw2 = uMass2 * wBin;
    float a1 = uMass1 * clamp(uChi1, -0.98, 0.98);
    float a1Sq = a1 * a1;
    float a2 = uMass2 * clamp(uChi2, -0.98, 0.98);
    float a2Sq = a2 * a2;

    float chiRem = clamp(uRemnantSpin, -0.998, 0.998);
    float aRemPhys = uRemnantMass * chiRem;
    float aRemPhys2 = aRemPhys * aRemPhys;
    float mwRem = uRemnantMass * uMergeBlend;

    float rPlus1 = uMass1 * (1.0 + sqrt(max(0.0, 1.0 - uChi1 * uChi1)));
    float rPlus2 = uMass2 * (1.0 + sqrt(max(0.0, 1.0 - uChi2 * uChi2)));
    float rPlusRem = uRemnantMass * (1.0 + sqrt(max(0.0, 1.0 - chiRem * chiRem)));

    float rCam = length(camPosOrb);
    float rEscape = max(42.0, rCam + 6.0);

    // Exact static camera initialization solving H_binary(camPosOrb, pt=1, p) == 0 via 3 Newton steps
    float hCam = 1.0 / max(2.2, rCam);
    vec3 lCam = safeNormalize(camPosOrb, vec3(0.0, 0.0, 1.0));
    vec3 p;
    float pt = 1.0;
    float obsFreq = 1.0;
    if (uLensing == 1) {
        float alpha = sqrt(max(0.08, 1.0 - 2.0 * hCam));
        float dl = dot(vLocal, lCam);
        vec3 dPerp = vLocal - dl * lCam;
        p = dPerp / alpha + ((dl - 2.0 * hCam) / (alpha * alpha)) * lCam;
        obsFreq = 1.0 / alpha;
        for (int ni = 0; ni < 3; ni++) {
            float fH = binaryHamiltonianValOrb(camPosOrb, p, pt,
                    p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2);
            vec3 dHdp, tmpDp;
            float tr1, tr2, trR, thS;
            binaryHamiltonDerivOrb(camPosOrb, p, pt,
                    p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                    dHdp, tmpDp, tr1, tr2, trR, thS);
            float denom = dot(dHdp, p);
            if (abs(denom) > 1e-5) {
                p *= clamp(1.0 - fH / denom, 0.75, 1.25);
            }
        }
    } else {
        p = vLocal;
    }

    vec3 accumColor = vec3(0.0);
    float transmittance = 1.0;
    vec3 exitDirOrb = vLocal;
    bool escaped = false;
    bool captured = false;

    bool hasHit = false;
    vec3 dominantPosOrb = vec3(0.0);
    vec3 dominantVelOrb = vec3(0.0);
    float hitDist = 16384.0;
    float maxHamErr = 0.0;
    int stepsUsed = 0;

    vec3 x = camPosOrb;
    {
        float frameShift = float(uFrameIndex & 63) * 5.588238;
        float ign = interleavedGradientNoise(gl_FragCoord.xy + vec2(frameShift));

        vec3 dxNow = vLocal;
        vec3 dpNow = vec3(0.0);
        float r1 = length(x - p1Orb);
        float r2 = length(x - p2Orb);
        float rRem = rCam;
        float hSum = hCam;

        if (uLensing == 1) {
            binaryHamiltonDerivOrb(x, p, pt,
                    p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                    dxNow, dpNow, r1, r2, rRem, hSum);
        }

        int diskCrossings = 0;
        float rCenter = length(x);
#if USE_DOPRI54
        float hAdaptive = 0.45;
#endif

        for (int step = 0; step < MAX_STEPS; step++) {
            float speed = length(dxNow);
            float pNormSq = dot(p, p);
            float radialOut = dot(x, dxNow);

            if (rCenter > rEscape && radialOut > 0.0) {
                exitDirOrb = safeNormalize(dxNow, vLocal);
                escaped = true;
                break;
            }
            if (transmittance < 0.008) {
                exitDirOrb = safeNormalize(dxNow, vLocal);
                escaped = true;
                break;
            }

            if (pNormSq > 7225.0 || isnan(pNormSq) || isinf(pNormSq)) {
                captured = true;
                if (!hasHit) {
                    hasHit = true;
                    dominantPosOrb = (r1 < r2) ? p1Orb : p2Orb;
                    dominantVelOrb = (r1 < r2) ? v1Orb : v2Orb;
                    hitDist = length(x - camPosOrb);
                }
                break;
            }
            if (wBin > 0.04) {
                float cap1 = rPlus1 * (1.02 - 0.10 * uMergeBlend);
                float cap2 = rPlus2 * (1.02 - 0.10 * uMergeBlend);
                if (r1 <= cap1) {
                    captured = true;
                    if (!hasHit) {
                        hasHit = true;
                        dominantPosOrb = x;
                        dominantVelOrb = v1Orb;
                        hitDist = length(x - camPosOrb);
                    }
                    break;
                }
                if (r2 <= cap2) {
                    captured = true;
                    if (!hasHit) {
                        hasHit = true;
                        dominantPosOrb = x;
                        dominantVelOrb = v2Orb;
                        hitDist = length(x - camPosOrb);
                    }
                    break;
                }
                if ((uSeparation < (rPlus1 + rPlus2) * 1.45 || uMergeBlend > 0.01)
                        && (2.0 * hSum >= 1.04)
                        && min(r1, r2) < max(rPlus1, rPlus2) * 1.55) {
                    captured = true;
                    if (!hasHit) {
                        hasHit = true;
                        dominantPosOrb = x;
                        dominantVelOrb = mix(v1Orb, v2Orb, r1 / max(1e-4, r1 + r2));
                        hitDist = length(x - camPosOrb);
                    }
                    break;
                }
            }
            if (uMergeBlend > 0.30 && rRem <= rPlusRem * 1.025) {
                captured = true;
                if (!hasHit) {
                    hasHit = true;
                    dominantPosOrb = x;
                    dominantVelOrb = vec3(0.0);
                    hitDist = length(x - camPosOrb);
                }
                break;
            }

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
                float hGuide = dsCoord / max(1.0, max(speed, pNorm * 0.45));
#if USE_DOPRI54
                hAdaptive = clamp(min(hAdaptive, hGuide * 1.45), 0.008, hGuide * 1.45);
                hStep = hAdaptive;
                bool accepted = binaryDopriStepOrb(x, p, hAdaptive, 0.006, hGuide * 1.60, pt,
                        p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                        dxNow, dpNow, r1, r2, rRem, hSum);
                stepsUsed = step + 1;
                if (!accepted) {
                    continue;
                }
#else
                hStep = hGuide;
                binaryGeodesicStepOrb(x, p, hStep, pt,
                        p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2,
                        dxNow, dpNow, r1, r2, rRem, hSum);
                stepsUsed = step + 1;
#endif
                if (uDiagMode == 1) {
                    maxHamErr = max(maxHamErr, abs(binaryHamiltonianValOrb(x, p, pt,
                            p1Orb, p2Orb, v1Orb, v2Orb, mw1, mw2, a1, a1Sq, a2, a2Sq, mwRem, aRemPhys, aRemPhys2)));
                }
            } else {
                hStep = dsCoord;
                x += dxNow * hStep;
                r1 = length(x - p1Orb);
                r2 = length(x - p2Orb);
                rRem = length(x);
            }
            rCenter = length(x);

            bool crossed = (prevX.y > 0.0 && x.y <= 0.0) || (prevX.y < 0.0 && x.y >= 0.0);
            if ((uShowDisk == 1 || uGwOverlay == 1) && (crossed || abs(x.y) < 0.48) && rCenter <= 38.5) {
                float slabHalfH = 0.045 * sqrt(max(0.0, rCenter - 1.2)) + 0.028;
                bool inSlab = (!crossed) && (uShowDisk == 1) && (abs(x.y) < slabHalfH) && (rCenter <= R_DISK_OUT);

                if (crossed || inSlab) {
                    vec3 hitOrb;
                    vec3 hitPCovOrb;
                    float stepWeight;
                    if (crossed) {
                        float tHit;
                        hitOrb = intersectOrbitalPlaneHermite(prevX, prevDx, x, dxNow, hStep, tHit);
                        hitPCovOrb = mix(prevP, p, tHit);
                        stepWeight = 0.72;
                        diskCrossings++;
                    } else {
                        hitOrb = x;
                        hitPCovOrb = p;
                        float segLen = length(x - prevX);
                        float vProf = exp(-(hitOrb.y * hitOrb.y) / max(1e-5, slabHalfH * slabHalfH * 0.36));
                        stepWeight = min(0.28, segLen * 0.56) * vProf;
                    }

                    if (crossed && uGwOverlay == 1 && diskCrossings <= 2) {
                        accumColor += transmittance * evalGwPlaneOverlayOrb(hitOrb);
                    }

                    if (uShowDisk == 1 && length(hitOrb.xz) <= R_DISK_OUT) {
                        float mipLod = clamp(float(max(0, diskCrossings - 1)) * 0.75 + (rCenter / R_DISK_OUT) * 0.45, 0.0, 2.2);
                        float density, tempK, dust, shockBoost;
                        vec3 vPlasmaOrb;
                        evalBinaryPlasmaOrb(hitOrb, p1Orb, p2Orb, v1Orb, v2Orb, rPlus1, rPlus2, rPlusRem, mipLod,
                                            density, tempK, vPlasmaOrb, dust, shockBoost);

                        if (density > 0.0025) {
                            float gFactor = computePlasmaDopplerG(hitPCovOrb, pt, obsFreq, vPlasmaOrb, hSum);
                            vec3 emitCol = sampleBlackbody(tempK * gFactor);

                            float g3 = gFactor * gFactor * gFactor;
                            float intensityBoost = (g3 / (1.0 + 0.06 * g3)) * shockBoost;
                            float emitSuppression = 1.0 - 0.70 * dust;
                            float sigmaT = max(1e-4, density * (0.64 + 1.20 * dust));
                            float tau = sigmaT * stepWeight;
                            float stepTrans = exp(-tau);
                            float alphaStep = 1.0 - stepTrans;
                            float srcGain = (0.94 * intensityBoost * emitSuppression) / (0.64 + 1.20 * dust);

                            accumColor += transmittance * (srcGain * alphaStep) * emitCol;
                            transmittance *= stepTrans;

                            if (!hasHit && alphaStep > 0.05) {
                                hasHit = true;
                                dominantPosOrb = hitOrb;
                                dominantVelOrb = vPlasmaOrb;
                                hitDist = length(hitOrb - camPosOrb);
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

    vec3 exitDirWorld = orbToWorld(exitDirOrb);
    vec3 skyDir = applyQuadrupoleGwDeflection(exitDirWorld, rCam);

    vec3 rawDdx = dFdx(skyDir);
    vec3 rawDdy = dFdy(skyDir);
    float maxGrad = 0.14;
    vec3 ddx = rawDdx * min(1.0, maxGrad / max(1e-6, length(rawDdx)));
    vec3 ddy = rawDdy * min(1.0, maxGrad / max(1e-6, length(rawDdy)));

    vec3 finalCol;
    if (uDiagMode == 1) {
        float logErr = log(max(1e-7, maxHamErr)) / log(10.0);
        finalCol = turboColormap((logErr + 7.0) / 5.0);
    } else if (uDiagMode == 2) {
        finalCol = turboColormap(float(stepsUsed) / float(MAX_STEPS));
    } else {
        finalCol = accumColor;
        if (escaped && transmittance > 0.005) {
            finalCol += transmittance * sampleSkyWithGrid(skyDir, ddx, ddy);
        }
    }

    vec2 motionUv = vec2(0.0);
    if (hasHit) {
        vec3 dominantPos = orbToWorld(dominantPosOrb);
        vec3 dominantVel = orbToWorld(dominantVelOrb);
        vec3 curRel = dominantPos - uCamPos;
        float curZ = dot(curRel, uCamForward);
        vec3 prevWorldPos = dominantPos - dominantVel * uGeomDt;
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
