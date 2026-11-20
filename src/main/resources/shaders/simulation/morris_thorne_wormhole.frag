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
uniform vec2 uJitter;

const float PI = 3.14159265359;
const int MAX_STEPS = 320;

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

// Exact Dneg / Interstellar Morris-Thorne shape function r(l) and dr/dl
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
    float phi = 0.0;

    for (int step = 0; step < MAX_STEPS; step++) {
        if (abs(l) > lEscape && (l * vl > 0.0)) {
            break;
        }

        float distThroat = max(0.0, abs(l) - uWormholeA);
        float h = clamp(0.058 * distThroat + 0.022 * uWormholeRho, 0.014, 2.20);

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
    }

    float sgnL = (l >= 0.0) ? 1.0 : -1.0;
    WormholeRay res;
    res.exitDir = safeNormalize(sgnL * cos(phi) * eR + sin(phi) * ePhi, dRay);
    res.endL = l;
    res.totalPhi = phi;
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

    // Delicate gravitational caustic shimmer right on the photon-sphere rim (no thick artificial bezel)
    float rimCaustic = smoothstep(3.8, 7.6, ray.totalPhi) * 0.045;
    col += vec3(0.68, 0.88, 1.15) * rimCaustic;
    return sanitizeHdr(col);
}

void main() {
    vec3 eR = safeNormalize(uCamRadial, vec3(0.0, 0.0, 1.0));
    float mw = max(0.08, uWormholeM);
    float r0, dr0;
    wormholeMetric(uWormholeL, mw, r0, dr0);
    float lEscape = max(42.0, abs(uWormholeL) + 22.0);

    // Pure 1 ray/px Halton-jittered geodesic (16-phase temporal super-sampling handled by TAA resolve)
    WormholeRay primary = traceWormholeRay(gl_FragCoord.xy + uJitter, eR, r0, mw, lEscape);

    float sideMask = (primary.endL >= 0.0) ? 1.0 : 0.0;
    bool portalRimEdge = fwidth(sideMask) > 0.01;

    vec3 rawDdx = dFdx(primary.exitDir);
    vec3 rawDdy = dFdy(primary.exitDir);

    // Prevent cross-universe finite differences from pulling coarse mipmaps along the throat rim
    float maxGrad = (portalRimEdge || primary.totalPhi > 3.0) ? 0.004 : 0.12;
    vec3 ddx = rawDdx * min(1.0, maxGrad / max(1e-6, length(rawDdx)));
    vec3 ddy = rawDdy * min(1.0, maxGrad / max(1e-6, length(rawDdy)));

    vec3 c0 = sampleUniverse(primary, ddx, ddy);

    // Throat-crossing & strongly-lensed rim rays reproject at throat distance; exterior sky at infinity
    bool crossedThroat = (primary.endL * uWormholeL < 0.0) || (primary.totalPhi > 1.35);
    float hitDist = crossedThroat ? max(0.5, abs(uWormholeL)) : 1e5;

    FragColor = vec4(sanitizeHdr(c0), hitDist);
}
