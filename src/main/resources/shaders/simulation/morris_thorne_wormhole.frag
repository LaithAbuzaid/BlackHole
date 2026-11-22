#version 330 core
layout(location = 0) out vec4 FragColor;

uniform vec2 uResolution;
uniform vec3 uCamRadial;   // Unit radial direction e_r on the wormhole celestial sphere
uniform vec3 uCamForward;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform samplerCube uSkyboxA;
uniform samplerCube uSkyboxB;
uniform float uWormholeL;
uniform float uWormholeRho;
uniform float uWormholeA;
uniform float uWormholeM;
uniform float uTanHalfFov;
uniform int uShowGrid;
uniform int uDiagMode;
uniform vec2 uJitter;

const float PI = 3.14159265359;
const int MAX_STEPS = 320;
const float SKY_HIT_DIST = 16384.0; // FP16-safe finite sentinel (max binary16 = 65504)

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

// Exact Dneg / Interstellar Morris-Thorne shape function r(l) and dr/dl (James et al. 2015)
// Metric: ds^2 = -dt^2 + dl^2 + r(l)^2 (dtheta^2 + sin^2(theta) dphi^2)
void wormholeMetric(float l, float mw, out float r, out float drdl) {
    float absL = abs(l);
    float x = max(0.0, (2.0 * (absL - uWormholeA)) / (PI * mw));
    float atanX = atan(x);
    r = max(0.15, uWormholeRho + mw * (x * atanX - 0.5 * log(1.0 + x * x)));
    float sgn = (l >= 0.0) ? 1.0 : -1.0;
    drdl = (2.0 / PI) * atanX * sgn;
}

void wormholeDeriv(float l, float vl, float H, float mw, out float dl, out float dvl, out float dphi) {
    float r, drdl;
    wormholeMetric(l, mw, r, drdl);
    float invR2 = 1.0 / (r * r);
    dl = vl;
    dvl = (H * H * invR2 / r) * drdl;
    dphi = H * invR2;
}

struct WormholeRay {
    vec3 exitDir;
    float endL;
    float totalPhi;
    float maxNullErr;
    int stepsUsed;
};

WormholeRay traceWormholeRay(vec2 pixelCoord, vec3 eR, float r0, float mw, float lEscape) {
    vec2 ndc = (pixelCoord / uResolution) * 2.0 - 1.0;
    float aspect = uResolution.x / uResolution.y;

    vec3 dRay = safeNormalize(uCamForward
                            + ndc.x * aspect * uTanHalfFov * uCamRight
                            + ndc.y * uTanHalfFov * uCamUp, uCamForward);

    float cosTheta = clamp(dot(dRay, eR), -1.0, 1.0);
    vec3 tanVec = dRay - cosTheta * eR;
    float sinTheta = length(tanVec);
    vec3 ePhi = (sinTheta > 1e-6) ? (tanVec / sinTheta) : vec3(0.0);

    float l = uWormholeL;
    float vl = cosTheta;
    float H = r0 * sinTheta;
    float H2 = H * H;
    float phi = 0.0;
    float maxNullErr = 0.0;
    int stepsUsed = 0;

    // Curvature-aware minimum length scale min(rho, M_w) prevents overshoot across sharp |l| = a lips
    float minScale = min(uWormholeRho, mw);

    for (int step = 0; step < MAX_STEPS; step++) {
        if (abs(l) > lEscape && (l * vl > 0.0)) {
            break;
        }
        stepsUsed = step + 1;

        // Refine smoothly near throat lip |l| = a where r''(l) jumps by 4 / (pi^2 * M_w)
        float distLip = abs(abs(l) - uWormholeA);
        float distThroat = max(0.0, abs(l) - uWormholeA);
        float h = clamp(0.048 * distThroat + 0.020 * minScale + 0.018 * min(distLip, mw), 0.009, 2.0);

        float k1l, k1v, k1p;
        wormholeDeriv(l, vl, H, mw, k1l, k1v, k1p);

        float k2l, k2v, k2p;
        wormholeDeriv(l + 0.5 * h * k1l, vl + 0.5 * h * k1v, H, mw, k2l, k2v, k2p);

        float k3l, k3v, k3p;
        wormholeDeriv(l + 0.5 * h * k2l, vl + 0.5 * h * k2v, H, mw, k3l, k3v, k3p);

        float k4l, k4v, k4p;
        wormholeDeriv(l + h * k3l, vl + h * k3v, H, mw, k4l, k4v, k4p);

        float h6 = h * (1.0 / 6.0);
        l += h6 * (k1l + 2.0 * k2l + 2.0 * k3l + k4l);
        vl += h6 * (k1v + 2.0 * k2v + 2.0 * k3v + k4v);
        phi += h6 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);

        // Monitor null constraint |v_l^2 + H^2/r^2 - 1| and softly re-project away from turning points
        float rCur, drCur;
        wormholeMetric(l, mw, rCur, drCur);
        float targetVl2 = 1.0 - H2 / (rCur * rCur);
        float nullErr = abs(vl * vl - targetVl2);
        maxNullErr = max(maxNullErr, nullErr);
        if (targetVl2 > 1e-4 && abs(vl) > 0.02 && nullErr > 1e-5) {
            vl = sign(vl) * sqrt(targetVl2);
        }
    }

    // Camera-independent spherical exit vector on S^2 (eliminates the camera-dependent
    // Householder reflection bug in Universe B: p_hat(phi) = cos(phi)*eR + sin(phi)*ePhi)
    WormholeRay res;
    res.exitDir = safeNormalize(cos(phi) * eR + sin(phi) * ePhi, dRay);
    res.endL = l;
    res.totalPhi = phi;
    res.maxNullErr = maxNullErr;
    res.stepsUsed = stepsUsed;
    return res;
}

vec3 sampleUniverse(WormholeRay ray, vec3 ddx, vec3 ddy) {
    vec3 col = (ray.endL >= 0.0)
            ? textureGrad(uSkyboxA, ray.exitDir, ddx, ddy).rgb
            : textureGrad(uSkyboxB, ray.exitDir, ddx, ddy).rgb;

    if (uShowGrid == 1) {
        vec3 dir = ray.exitDir;
        float lon = safeAtan2(dir.z, dir.x);
        float lat = asin(clamp(dir.y, -1.0, 1.0));

        float gridSpacing = PI / 12.0;
        float cosLat = max(0.06, cos(lat));
        float dLon = abs(fract(lon / gridSpacing + 0.5) - 0.5) * gridSpacing * cosLat;
        float dLat = abs(fract(lat / gridSpacing + 0.5) - 0.5) * gridSpacing;

        float line = 1.0 - smoothstep(0.0022, 0.0062, min(dLon, dLat));
        float equator = 1.0 - smoothstep(0.0035, 0.0095, abs(lat));
        float prime = 1.0 - smoothstep(0.0035, 0.0095, abs(lon) * cosLat);

        vec3 gridTint = (ray.endL >= 0.0) ? vec3(0.20, 0.58, 1.00) : vec3(0.18, 0.96, 0.72);
        col += gridTint * line * 0.45;
        col += vec3(1.00, 0.56, 0.18) * max(equator, prime) * 0.85;
    }
    return sanitizeHdr(col);
}

void main() {
    vec3 eR = safeNormalize(uCamRadial, vec3(0.0, 0.0, 1.0));
    float mw = max(0.08, uWormholeM);
    float r0, dr0;
    wormholeMetric(uWormholeL, mw, r0, dr0);
    float lEscape = max(42.0, abs(uWormholeL) + 22.0);

    WormholeRay primary = traceWormholeRay(gl_FragCoord.xy + uJitter, eR, r0, mw, lEscape);

    float sideMask = (primary.endL >= 0.0) ? 1.0 : 0.0;
    bool portalRimEdge = fwidth(sideMask) > 0.01;

    vec3 rawDdx = dFdx(primary.exitDir);
    vec3 rawDdy = dFdy(primary.exitDir);

    float maxGrad = (portalRimEdge || primary.totalPhi > 3.0) ? 0.004 : 0.12;
    vec3 ddx = rawDdx * min(1.0, maxGrad / max(1e-6, length(rawDdx)));
    vec3 ddy = rawDdy * min(1.0, maxGrad / max(1e-6, length(rawDdy)));

    vec3 c0;
    if (uDiagMode == 1) {
        // Log10 null-constraint residual |v_l^2 + H^2/r^2 - 1| over [1e-7 .. 1e-2]
        float logErr = log(max(1e-7, primary.maxNullErr)) / log(10.0);
        c0 = turboColormap((logErr + 7.0) / 5.0);
    } else if (uDiagMode == 2) {
        c0 = turboColormap(float(primary.stepsUsed) / float(MAX_STEPS));
    } else {
        c0 = sampleUniverse(primary, ddx, ddy);
    }

    bool crossedThroat = (primary.endL * uWormholeL < 0.0) || (primary.totalPhi > 1.35);
    float hitDist = crossedThroat ? max(0.5, abs(uWormholeL)) : SKY_HIT_DIST;

    FragColor = vec4(sanitizeHdr(c0), hitDist);
}
