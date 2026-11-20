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
uniform sampler2D uBlackbodyLut;
uniform float uSpinA;
uniform float uHorizonR;
uniform float uInnerHorizonR;
uniform float uIscoR;
uniform float uDiskTempScale;
uniform float uDiskDensity;
uniform float uJetPower;
uniform float uTanHalfFov;
uniform float uCauchyFlash;
uniform float uSpaghettify;
uniform float uSingularityFade;
uniform int uDoppler;
uniform int uLensing;
uniform int uShowDisk;
uniform int uShowJets;
uniform int uFreeFall;
uniform int uShowGrid;
uniform int uOrderVis;
uniform vec2 uJitter;
uniform int uFrameIndex;

// Geometric units: G = c = M = 1, r_s = 2M = 2.0
const float PI = 3.14159265359;
const float M_BH = 1.0;
const float R_DISK_OUT = 24.0;
const float R_SHELL = 25.5;
const int MAX_STEPS = 180;

// ============================================================================
// SAFE MATH & PRECOMPUTED 512x1 BLACKBODY LUT
// ============================================================================

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
    c = mix(c, vec3(0.0), bad);
    return clamp(c, vec3(0.0), vec3(48.0));
}

// Single-cycle hardware lookup into precomputed 512x1 sqrt-spaced Planckian RGB16F LUT (900K..38000K)
vec3 sampleBlackbody(float tempKelvin) {
    float norm = clamp((tempKelvin - 900.0) * (1.0 / 37100.0), 0.0, 1.0);
    return texture(uBlackbodyLut, vec2(sqrt(norm), 0.5)).rgb;
}

// Interleaved Gradient Noise (Jimenez 2014) for artifact-free volumetric step dithering
float interleavedGradientNoise(vec2 px) {
    return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

// ============================================================================
// EXACT KERR-SCHILD METRIC & ANALYTIC HAMILTONIAN DERIVATIVES
// ============================================================================

// Exact spheroidal Kerr radius solving r^4 - (R^2 - a^2)r^2 - a^2 y^2 = 0
float kerrRadius(vec3 x, float a2) {
    float R2 = dot(x, x);
    float b = R2 - a2;
    float disc = max(0.0, b * b + 4.0 * a2 * x.y * x.y);
    float r2 = 0.5 * (b + sqrt(disc));
    return sqrt(max(1e-4, r2));
}

void kerrMetricHL(vec3 x, float a, float a2, out float r, out float H, out vec3 lVec) {
    r = kerrRadius(x, a2);
    float r2 = r * r;
    float D = max(1e-5, r2 * r2 + a2 * x.y * x.y);
    H = (M_BH * r2 * r) / D;
    float invS = 1.0 / max(1e-4, r2 + a2);
    lVec = vec3(
        (r * x.x + a * x.z) * invS,
        x.y / r,
        (r * x.z - a * x.x) * invS
    );
}

// Exact analytic Hamiltonian derivatives for H_ham = 0.5 * (-pt^2 + |p|^2 - 2 H (-pt + l.p)^2) = 0
// Guaranteed d(H_ham)/d(lambda) == 0 identically (zero radial repulsion cusps).
// Lean signature minimizes GPU register pressure inside the geodesic integration loop.
float hamiltonDeriv(vec3 x, vec3 p, float pt, float a, float a2,
                    out vec3 dx, out vec3 dp) {
    float r = kerrRadius(x, a2);
    float r2 = r * r;
    float D = max(1e-5, r2 * r2 + a2 * x.y * x.y);
    float invD = 1.0 / D;
    float H = (M_BH * r2 * r) * invD;
    float S = max(1e-4, r2 + a2);
    float invS = 1.0 / S;

    vec3 lVec = vec3(
        (r * x.x + a * x.z) * invS,
        x.y / r,
        (r * x.z - a * x.x) * invS
    );

    float lp = dot(lVec, p);
    float q = -pt + lp;

    dx = p - (2.0 * H * q) * lVec;

    vec3 gradR = (r * invD) * vec3(r2 * x.x, S * x.y, r2 * x.z);
    vec3 gradH = (r2 * (3.0 * a2 * x.y * x.y - r2 * r2) * gradR
                - vec3(0.0, 2.0 * a2 * r2 * r * x.y, 0.0)) * (invD * invD);

    float xpZp = x.x * p.x + x.z * p.z;
    float zpxXpz = x.z * p.x - x.x * p.z;
    vec3 directLP = vec3(
        (r * p.x - a * p.z) * invS,
        p.y / r,
        (r * p.z + a * p.x) * invS
    );
    float dlpDr = ((a2 - r2) * xpZp - 2.0 * a * r * zpxXpz) * (invS * invS)
                - (x.y * p.y) / r2;
    vec3 gradLP = directLP + dlpDr * gradR;

    dp = (q * q) * gradH + (2.0 * H * q) * gradLP;
    return r;
}

// FSAL-style RK4 in strong field (r <= 4.8M), RK2 midpoint in weak field (r > 4.8M)
float geodesicStep(inout vec3 x, inout vec3 p, float h, float pt, float a, float a2, float rNow,
                   inout vec3 dxNow, inout vec3 dpNow) {
    vec3 k1x = dxNow;
    vec3 k1p = dpNow;
    vec3 k2x, k2p;
    hamiltonDeriv(x + (0.5 * h) * k1x, p + (0.5 * h) * k1p, pt, a, a2, k2x, k2p);

    if (rNow > 4.8) {
        x += h * k2x;
        p += h * k2p;
        return hamiltonDeriv(x, p, pt, a, a2, dxNow, dpNow);
    } else {
        vec3 k3x, k3p, k4x, k4p;
        hamiltonDeriv(x + (0.5 * h) * k2x, p + (0.5 * h) * k2p, pt, a, a2, k3x, k3p);
        hamiltonDeriv(x + h * k3x, p + h * k3p, pt, a, a2, k4x, k4p);

        float h6 = h * (1.0 / 6.0);
        x += h6 * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
        p += h6 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);
        return hamiltonDeriv(x, p, pt, a, a2, dxNow, dpNow);
    }
}

// Newton-Raphson Cubic Hermite equatorial plane (y = 0) intersection
vec3 intersectEquatorialHermite(vec3 x0, vec3 dx0, vec3 x1, vec3 dx1, float h, out float tHit) {
    float y0 = x0.y;
    float y1 = x1.y;
    float m0 = dx0.y * h;
    float m1 = dx1.y * h;

    float t = clamp(abs(y0) / (abs(y0) + abs(y1) + 1e-8), 0.0, 1.0);
    for (int iter = 0; iter < 3; iter++) {
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
// SKYBOX SAMPLING WITH FREE-FALL RELATIVISTIC BLUESHIFT & OPTIONAL GRID
// ============================================================================

vec3 sampleSkyWithGrid(vec3 dir, vec3 ddx, vec3 ddy, float skyShiftG) {
    vec3 sky = textureGrad(uSkybox, dir, ddx, ddy).rgb;

    // Relativistic headlight frequency shift for plunging Doran free-fall observer
    if (uFreeFall == 1 && abs(skyShiftG - 1.0) > 0.02) {
        vec3 blueTint = vec3(0.62, 0.86, 1.48);
        vec3 redTint = vec3(1.32, 0.72, 0.44);
        vec3 shiftTint = (skyShiftG >= 1.0)
                ? mix(vec3(1.0), blueTint, clamp((skyShiftG - 1.0) * 0.55, 0.0, 1.0))
                : mix(vec3(1.0), redTint, clamp((1.0 - skyShiftG) * 1.15, 0.0, 1.0));
        float intensityScale = clamp(pow(skyShiftG, 1.65), 0.25, 3.6);
        sky *= shiftTint * intensityScale;
    }

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

        sky += vec3(0.18, 0.56, 0.98) * line * 0.45;
        sky += vec3(1.00, 0.42, 0.16) * max(equator, prime) * 0.85;
    }
    return sky;
}

// ============================================================================
// EXACT RELATIVISTIC DOPPLER FACTOR FOR DISK & PLUNGE STREAMS
// ============================================================================

float computeDiskDopplerG(vec3 hitP, vec3 pCov, float pt, float obsFreq,
                          float r, float H, vec3 lVec, bool inPlunge) {
    if (uDoppler == 0) return 1.0;

    float rEff = max(uIscoR, r);
    float omegaK = 1.0 / (rEff * sqrt(rEff) + uSpinA);
    vec3 vOrb = omegaK * vec3(-hitP.z, 0.0, hitP.x);

    if (inPlunge) {
        float plungeFrac = clamp((uIscoR - r) / max(0.05, uIscoR - uHorizonR), 0.0, 1.0);
        float rho = max(1e-4, length(hitP.xz));
        vec3 radIn = vec3(-hitP.x / rho, 0.0, -hitP.z / rho);
        float vInMag = 0.52 * sqrt(plungeFrac);
        vOrb = vOrb * (1.0 + 0.25 * plungeFrac) + radIn * vInMag;
    }

    float lv = dot(lVec, vOrb);
    float normSq = 1.0 - dot(vOrb, vOrb) - 2.0 * H * (1.0 + lv) * (1.0 + lv);
    float ut = inversesqrt(max(0.04, normSq));

    float emitFreq = ut * (pt + dot(pCov, vOrb));
    return clamp(obsFreq / max(0.08, emitFreq), 0.14, 3.10);
}

// ============================================================================
// PRIMARY & SUB-PIXEL GEODESIC RAY TRACER
// ============================================================================

struct RayResult {
    vec3 accumColor;
    float transmittance;
    vec3 exitDir;
    float skyShiftG;
    float rayPt;
    float hitDist;
    bool escaped;
};

RayResult traceGeodesic(vec2 pixelCoord, float a, float a2,
                        float rCam, float Hcam, vec3 lCam, float ign) {
    vec2 ndc = (pixelCoord / uResolution) * 2.0 - 1.0;
    float aspect = uResolution.x / uResolution.y;

    vec3 vLocal = safeNormalize(uCamForward
                              + ndc.x * aspect * uTanHalfFov * uCamRight
                              + ndc.y * uTanHalfFov * uCamUp, uCamForward);

    // Schwarzschild interior anisotropic tidal optical stretching (spaghettification as r -> 0)
    if (uSpaghettify > 0.001) {
        vec3 eRad = safeNormalize(uCamPos, vec3(0.0, 0.0, 1.0));
        float radComp = dot(vLocal, eRad);
        vec3 vRad = radComp * eRad;
        vec3 vTrans = vLocal - vRad;
        float stretch = 1.0 + 3.4 * uSpaghettify;
        float squeeze = 1.0 - 0.62 * uSpaghettify;
        vLocal = safeNormalize(vRad * stretch + vTrans * squeeze, vLocal);
    }

    float rPlus = uHorizonR;
    float iscoR = uIscoR;
    float rEscape = max(38.0, rCam + 6.0);

    vec3 p;
    float pt;
    float obsFreq = 1.0;

    if (uLensing == 0) {
        p = vLocal;
        pt = 1.0;
    } else if (uFreeFall == 1 || (2.0 * Hcam >= 0.92)) {
        // Exact Doran / Kerr-Schild horizon-penetrating Free-Fall Observer tetrad
        float s2H = sqrt(max(0.0, 2.0 * Hcam));
        float dl = dot(vLocal, lCam);
        vec3 dPerp = vLocal - dl * lCam;
        pt = 1.0 + dl * s2H;
        p = dPerp + (dl + (s2H + 2.0 * Hcam * dl) / (1.0 + s2H)) * lCam;
        obsFreq = 1.0;
    } else {
        // Exact Static Shell Observer local orthonormal tetrad outside r_+ & ergosphere
        float alpha = sqrt(max(0.04, 1.0 - 2.0 * Hcam));
        float dl = dot(vLocal, lCam);
        vec3 dPerp = vLocal - dl * lCam;
        pt = 1.0;
        p = dPerp / alpha + ((dl - 2.0 * Hcam) / (alpha * alpha)) * lCam;
        obsFreq = 1.0 / alpha;
    }

    bool startedInside = (rCam <= rPlus);

    RayResult res;
    res.accumColor = vec3(0.0);
    res.transmittance = 1.0;
    res.exitDir = vLocal;
    res.skyShiftG = (uFreeFall == 1) ? clamp(1.0 / max(0.14, pt), 0.38, 3.80) : 1.0;
    res.rayPt = pt;
    res.hitDist = 1e5;
    res.escaped = false;

    // Inside r_+, backward-traced rays with pt <= 0 can never escape to exterior infinity
    if (uLensing == 1 && startedInside && pt <= 0.0) {
        res.escaped = false;
        res.hitDist = max(1.0, rCam);
        return res;
    }

    // Analytic weak-field empty-space shortcut for far-field corner rays outside disk & gravitational shell
    vec3 x = uCamPos;
    float bProj = dot(-x, vLocal);
    float bSq = max(0.0, dot(x, x) - bProj * bProj);
    if (uLensing == 1 && rCam > R_SHELL + 1.0 && (bProj <= 0.0 || bSq > 26.5 * 26.5)) {
        float b = sqrt(max(1.0, bSq));
        vec3 bVec = x + vLocal * max(0.0, bProj);
        float defl = (bProj > 0.0)
                ? (4.0 / b + 11.78097 / (b * b))
                : (2.0 * max(0.0, 1.0 - dot(safeNormalize(x, vec3(0, 0, 1)), vLocal)) / max(1.0, rCam));
        res.exitDir = safeNormalize(vLocal - safeNormalize(bVec, vec3(0.0, 1.0, 0.0)) * defl, vLocal);
        res.escaped = true;
        return res;
    }

    bool captured = false;
    float totalDeflection = 0.0;
    int diskCrossings = 0;
    float firstDiskDist = -1.0;

    float r = rCam;
    vec3 dxNow = vLocal;
    vec3 dpNow = vec3(0.0);
    if (uLensing == 1) {
        r = hamiltonDeriv(x, p, pt, a, a2, dxNow, dpNow);

        // Single analytic leapfrog advance across empty exterior space to R_SHELL (27.5 M)
        if (rCam > R_SHELL + 1.5 && bProj > 0.0 && bSq < R_SHELL * R_SHELL) {
            float tSkip = max(0.0, bProj - sqrt(max(0.0, R_SHELL * R_SHELL - bSq))) * 0.92;
            if (tSkip > 1.0) {
                float speed0 = max(1.0, length(dxNow));
                float hSkip = tSkip / speed0;
                r = geodesicStep(x, p, hSkip, pt, a, a2, r, dxNow, dpNow);
            }
        }
    }

    bool jetsActive = (uShowJets == 1 && uJetPower > 0.005);

    for (int step = 0; step < MAX_STEPS; step++) {
        float speed = length(dxNow);
        float pNormSq = dot(p, p);
        float radialOut = dot(x, dxNow);

        if (r > rEscape && radialOut > 0.0) {
            res.exitDir = safeNormalize(dxNow, vLocal);
            res.escaped = true;
            break;
        }
        if (res.transmittance < 0.008) {
            res.exitDir = safeNormalize(dxNow, vLocal);
            res.escaped = true;
            break;
        }

        if (uLensing == 1) {
            // True interior cutoff (r < 0.08M) with zero artificial walls at r_+ for plunging observers
            if (r < 0.08 || pNormSq > 7225.0) {
                captured = true;
                break;
            }
            if (!startedInside && r <= rPlus * 1.014 && radialOut < 0.0) {
                captured = true;
                break;
            }
        } else if (r <= rPlus) {
            captured = true;
            break;
        }

        // High-efficiency adaptive step sizing (exact strong field <= 5.0M, fast weak-field traversal)
        float distHorizon = abs(r - rPlus);
        float dsCoord;
        if (r < rPlus) {
            dsCoord = clamp(0.075 * r, 0.015, 0.12);
        } else if (r > R_DISK_OUT + 1.5) {
            dsCoord = (radialOut > 0.0)
                    ? clamp(0.42 * r, 1.90, 9.50)
                    : clamp(0.30 * distHorizon, 0.90, 5.20);
        } else {
            float absY = abs(x.y);
            float diskGuide = (absY > 1.8) ? 4.20 : (0.62 + absY * 0.80);
            float fieldScale = (r > 5.0) ? (0.215 * distHorizon + 0.055) : (0.118 * distHorizon + 0.032);
            dsCoord = clamp(min(fieldScale, diskGuide), 0.030, 3.80);
        }

        if (jetsActive) {
            float absY = abs(x.y);
            if (absY > rPlus * 2.1 && absY < 40.0) {
                float rhoAxis = length(x.xz);
                if (rhoAxis < 1.35) {
                    dsCoord = min(dsCoord, 0.24 + rhoAxis * 0.26);
                }
            }
        }

        // Per-pixel animated IGN dithering eliminates volumetric slab/jet and grazing-angle banding
        if (step == 0) {
            dsCoord *= (0.60 + 0.40 * ign);
        } else if (startedInside && abs(x.y) < 0.85) {
            dsCoord *= (0.84 + 0.32 * fract(ign + float(step) * 0.618034));
        }

        vec3 prevX = x;
        vec3 prevP = p;
        vec3 prevDx = dxNow;

        float hStep;
        if (uLensing == 1) {
            float pNorm = sqrt(pNormSq);
            hStep = dsCoord / max(1.0, max(speed, pNorm * 0.45));
            r = geodesicStep(x, p, hStep, pt, a, a2, r, dxNow, dpNow);
        } else {
            hStep = dsCoord;
            x += dxNow * hStep;
            r = kerrRadius(x, a2);
        }

        if (uOrderVis == 1) {
            float lenPrev = max(1e-5, length(prevX));
            float lenCur = max(1e-5, length(x));
            totalDeflection += length(cross(prevX, x)) / (lenPrev * lenCur);
        }

        if (uLensing == 1) {
            if (r < 0.08 || dot(p, p) > 7225.0) {
                captured = true;
                break;
            }
            if (!startedInside && r <= rPlus * 1.014 && dot(x, dxNow) < 0.0) {
                captured = true;
                break;
            }
        } else if (r <= rPlus) {
            captured = true;
            break;
        }

        // --------------------------------------------------------------------
        // VOLUMETRIC ACCRETION DISK, ABSORBING DUST LANES & PLUNGE STREAMS
        // --------------------------------------------------------------------
        if (uShowDisk == 1 && r > rPlus * 1.03 && r <= R_DISK_OUT + 1.5) {
            bool crossed = (prevX.y > 0.0 && x.y <= 0.0) || (prevX.y < 0.0 && x.y >= 0.0);
            if (crossed || abs(x.y) < 0.52) {
                float drIsco = max(0.0, r - iscoR);
                float sqrtDr = sqrt(drIsco);
                float slabHalfH = 0.040 * sqrtDr * sqrt(sqrtDr) + 0.024;
                bool inSlab = (!crossed) && (abs(x.y) < slabHalfH) && (r <= R_DISK_OUT);

                if (crossed || inSlab) {
                    vec3 hitP;
                    vec3 hitPCov;
                    float stepWeight;
                    if (crossed) {
                        float tHit;
                        hitP = intersectEquatorialHermite(prevX, prevDx, x, dxNow, hStep, tHit);
                        hitPCov = mix(prevP, p, tHit);
                        stepWeight = 0.68;
                        diskCrossings++;
                    } else {
                        hitP = x;
                        hitPCov = p;
                        float segLen = length(x - prevX);
                        float vProf = exp(-(hitP.y * hitP.y) / max(1e-5, slabHalfH * slabHalfH * 0.36));
                        stepWeight = min(0.28, segLen * 0.58) * vProf;
                    }

                    float hitR, hitH;
                    vec3 hitL;
                    kerrMetricHL(hitP, a, a2, hitR, hitH, hitL);

                    if (hitR > rPlus * 1.04 && hitR <= R_DISK_OUT) {
                        float phi = safeAtan2(hitP.z, hitP.x);
                        bool inPlunge = (hitR < iscoR);

                        float uNorm = clamp((hitR - iscoR) / max(0.1, R_DISK_OUT - iscoR), 0.0, 1.0);
                        float density = 0.0;
                        float structure = 0.5;
                        float dustLaneFactor = 0.0;

                        if (inPlunge) {
                            float plungeU = clamp((hitR - rPlus * 1.04) / max(0.05, iscoR - rPlus * 1.04), 0.0, 1.0);
                            float spiral = phi - uTime * 2.2 + 5.2 * log(max(0.06, plungeU));
                            vec3 volCoord = vec3(cos(spiral) * 0.28, hitP.y * 0.65, sin(spiral) * 0.28);
                            float stream = textureLod(uNoiseVol, volCoord, 0.5).r;
                            float filament = pow(max(0.0, sin(spiral * 2.0 + stream * 5.2)), 2.8);
                            float fadeHorizon = smoothstep(0.0, 0.42, plungeU);
                            density = filament * fadeHorizon * 0.42 * uDiskDensity;
                            structure = 0.45 + 0.55 * filament;
                        } else {
                            // Differentially sheared Keplerian plasma turbulence (alias-free mipmapped 3D fBm)
                            float omegaVis = 2.05 * pow(max(1.0, iscoR) / hitR, 1.38);
                            float logSpiral = phi - uTime * omegaVis + 6.4 * log(hitR / max(1.0, iscoR));
                            float mipLod = clamp(float(max(0, diskCrossings - 1)) * 0.75 + uNorm * 0.45, 0.0, 2.2);

                            float rWrap = 0.22 + uNorm * 0.62;
                            vec3 volCoord = vec3(
                                cos(logSpiral) * rWrap + uNorm * 0.45,
                                hitP.y * 0.50 - uTime * 0.016,
                                sin(logSpiral) * rWrap + uNorm * 0.85
                            );
                            float turbMacro = textureLod(uNoiseVol, volCoord * 0.72, mipLod).r;
                            float turbMid = textureLod(uNoiseVol, volCoord * 1.75 + vec3(0.31, -0.17, 0.23), mipLod).r;

                            // Smooth magnetized MRI filaments & broad density waves (replaces harsh corduroy sine grooves)
                            float broadBands = 0.5 + 0.5 * sin(uNorm * 26.0 + turbMacro * 6.8);
                            float shearStream = 0.5 + 0.5 * cos(logSpiral * 2.0 + uNorm * 42.0 - turbMid * 7.2);
                            float ridgeFilament = 1.0 - abs(turbMid * 2.0 - 1.0);
                            float plasmaKnots = smoothstep(0.30, 0.84, turbMacro * 0.52 + turbMid * 0.32 + ridgeFilament * 0.16);

                            structure = 0.36 * broadBands + 0.34 * shearStream + 0.30 * plasmaKnots;

                            if (uNorm > 0.24) {
                                float dustNoise = textureLod(uNoiseVol, volCoord * 1.25 + vec3(0.17, 0.62, -0.33), mipLod).r;
                                dustLaneFactor = smoothstep(0.24, 0.84, uNorm) * smoothstep(0.42, 0.74, dustNoise);
                            }

                            float innerRamp = smoothstep(0.0, 0.036, uNorm);
                            float outerFade = pow(max(0.0, 1.0 - uNorm), 1.55);
                            float iscoPeak = exp(-pow((uNorm - 0.024) / 0.048, 2.0)) * 0.82;

                            density = max(0.0, (structure * 1.05 + iscoPeak) * innerRamp * outerFade) * uDiskDensity;
                        }

                        if (density > 0.0025) {
                            float gFactor = computeDiskDopplerG(hitP, hitPCov, pt, obsFreq, hitR, hitH, hitL, inPlunge);

                            vec3 emitCol;
                            float intensityBoost;
                            if (uOrderVis == 1) {
                                if (diskCrossings <= 1 && totalDeflection < PI * 0.95) {
                                    emitCol = vec3(1.00, 0.56, 0.14);
                                } else if (diskCrossings == 2 || totalDeflection < PI * 1.95) {
                                    emitCol = vec3(0.14, 0.88, 1.00);
                                } else {
                                    emitCol = vec3(1.00, 0.18, 0.76);
                                }
                                intensityBoost = 1.0;
                            } else {
                                float effR = max(iscoR * 0.96, hitR);
                                float baseTemp = (9800.0 * uDiskTempScale)
                                               * pow(max(1.0, iscoR) / effR, 0.75)
                                               * (0.62 + 0.58 * structure);
                                baseTemp *= (1.0 - 0.52 * dustLaneFactor);

                                emitCol = sampleBlackbody(baseTemp * gFactor);

                                // Relativistic specific intensity I_obs = g^3 * I_emit with gentle high-HDR shoulder
                                // preserves bright-approaching vs dim-receding asymmetry without flat white clipping.
                                float g3 = gFactor * gFactor * gFactor;
                                intensityBoost = g3 / (1.0 + 0.055 * g3);
                            }

                            float emitSuppression = 1.0 - 0.74 * dustLaneFactor;
                            float emission = density * intensityBoost * emitSuppression * stepWeight * 0.78;
                            float opacity = clamp(density * stepWeight * (0.56 + 1.35 * uNorm + 1.45 * dustLaneFactor), 0.0, 0.96);

                            res.accumColor += res.transmittance * emission * emitCol;
                            res.transmittance *= (1.0 - opacity);
                            if (firstDiskDist < 0.0 && opacity > 0.04) {
                                firstDiskDist = length(hitP - uCamPos);
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------------------
        // COLLIMATED BIPOLAR RELATIVISTIC SYNCHROTRON JETS (+/- Y AXIS)
        // --------------------------------------------------------------------
        if (jetsActive) {
            float absY = abs(x.y);
            if (absY > rPlus * 2.2 && absY < 40.0) {
                float rFunnel = (0.18 + 0.08 * a) * pow(1.0 + absY * 0.14, 0.52);
                float rho = length(x.xz);
                if (rho < rFunnel * 2.20) {
                    float jetSign = (x.y >= 0.0) ? 1.0 : -1.0;
                    float uJet = rho / max(0.05, rFunnel);

                    float spine = exp(-uJet * uJet * 4.8) * 1.15;
                    float sheath = exp(-pow((uJet - 0.82) / 0.38, 2.0)) * 0.34;
                    float radialProfile = (spine + sheath) * smoothstep(2.20, 1.25, uJet);

                    float baseLaunch = smoothstep(rPlus * 2.20, rPlus * 6.40, absY);
                    float topFade = exp(-absY * 0.015) * smoothstep(40.0, 22.0, absY);

                    float phiJet = safeAtan2(x.z, x.x);
                    float helixPhase = phiJet * 2.0 - jetSign * (absY * (0.48 + 0.55 * a) - uTime * 4.2);
                    vec3 jetNoiseCoord = vec3(
                        x.x * 0.18,
                        absY * 0.048 - uTime * 0.68,
                        x.z * 0.18
                    );
                    float jTurb = textureLod(uNoiseVol, jetNoiseCoord, 0.4).r;
                    float filament = 0.64 + 0.36 * pow(0.5 + 0.5 * sin(helixPhase + jTurb * 4.4), 1.5);
                    float knots = 0.70 + 0.30 * jTurb;

                    float jetDensity = radialProfile * baseLaunch * topFade * filament * knots * uJetPower;
                    if (jetDensity > 0.003) {
                        float gJet = 1.0;
                        if (uDoppler == 1) {
                            float betaJet = 0.66;
                            vec3 vJet = vec3(0.0, jetSign * betaJet, 0.0);
                            float rJ, Hj;
                            vec3 lJ;
                            kerrMetricHL(x, a, a2, rJ, Hj, lJ);
                            float lvJ = dot(lJ, vJet);
                            float normJ = 1.0 - betaJet * betaJet - 2.0 * Hj * (1.0 + lvJ) * (1.0 + lvJ);
                            float utJ = inversesqrt(max(0.05, normJ));
                            float emitFreqJ = utJ * (pt + dot(p, vJet));
                            gJet = clamp(obsFreq / max(0.08, emitFreqJ), 0.35, 2.50);
                        }

                        float synchTemp = (18000.0 + 9000.0 * jTurb) * clamp(gJet, 0.65, 1.70);
                        vec3 synchCol = mix(sampleBlackbody(synchTemp), vec3(0.36, 0.64, 1.55), 0.48);

                        float g3 = gJet * gJet * gJet;
                        float jetBoost = g3 / (1.0 + 0.28 * g3);

                        float segLenJet = length(x - prevX);
                        float jetEmit = jetDensity * jetBoost * segLenJet * 0.24;
                        float jetAlpha = clamp(jetDensity * segLenJet * 0.008, 0.0, 0.10);

                        res.accumColor += res.transmittance * jetEmit * synchCol;
                        res.transmittance *= (1.0 - jetAlpha);
                    }
                }
            }
        }
    }

    if (captured) {
        res.escaped = false;
    }

    if (firstDiskDist > 0.0) {
        res.hitDist = firstDiskDist;
    } else if (!res.escaped) {
        res.hitDist = max(1.0, rCam);
    } else {
        res.hitDist = 1e5;
    }
    return res;
}

vec3 applyInteriorClimax(vec3 col, float rayPt) {
    // Kerr Inner Cauchy Horizon (r_-) Mass-Inflation Blueshift Caustic:
    // Tightly hugs the horizon caustic boundary (rayPt ~ 0) as a crisp electric blue-white
    // celestial ring without fogging over the exterior sky and warped accretion disk.
    if (uCauchyFlash > 0.001) {
        float sharpRing = exp(-pow(rayPt / (0.014 + 0.022 * uCauchyFlash), 2.0));
        float coronaHalo = exp(-pow(rayPt / (0.052 + 0.055 * uCauchyFlash), 2.0));
        vec3 coreCol = vec3(0.82, 0.94, 1.70);
        vec3 haloCol = vec3(0.18, 0.54, 1.48);
        col += coreCol * sharpRing * uCauchyFlash * 4.2
             + haloCol * coronaHalo * uCauchyFlash * 0.72
             + haloCol * (uCauchyFlash * uCauchyFlash * 0.035);
    }

    // Schwarzschild Singularity Impact Fade (r -> 0)
    if (uSingularityFade > 0.001 && uCauchyFlash < 0.05) {
        col *= (1.0 - clamp(uSingularityFade, 0.0, 1.0));
    }

    return sanitizeHdr(col);
}

void main() {
    float a = clamp(uSpinA, 0.0, 0.998);
    float a2 = a * a;

    float rCam, Hcam;
    vec3 lCam;
    kerrMetricHL(uCamPos, a, a2, rCam, Hcam, lCam);

    // Temporally animated Interleaved Gradient Noise + Halton(2,3) sub-pixel TAA jitter
    float frameShift = float(uFrameIndex & 63) * 5.588238;
    float ign = interleavedGradientNoise(gl_FragCoord.xy + vec2(frameShift));
    RayResult primary = traceGeodesic(gl_FragCoord.xy + uJitter, a, a2, rCam, Hcam, lCam, ign);

    // Hardware 2x2 quad derivatives evaluated unconditionally in uniform control flow
    vec3 rawDdx = dFdx(primary.exitDir);
    vec3 rawDdy = dFdy(primary.exitDir);

    float maxGrad = 0.14;
    vec3 ddx = rawDdx * min(1.0, maxGrad / max(1e-6, length(rawDdx)));
    vec3 ddy = rawDdy * min(1.0, maxGrad / max(1e-6, length(rawDdy)));

    vec3 c0 = primary.accumColor;
    if (primary.escaped && primary.transmittance > 0.005) {
        c0 += primary.transmittance * sampleSkyWithGrid(primary.exitDir, ddx, ddy, primary.skyShiftG);
    }
    c0 = applyInteriorClimax(c0, primary.rayPt);

    // Store world hit distance in alpha channel for exact 3D camera parallax TAA reprojection
    FragColor = vec4(sanitizeHdr(c0), primary.hitDist);
}
