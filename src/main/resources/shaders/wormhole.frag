#version 330 core
layout(location = 0) out vec4 FragColor;

uniform vec2 uResolution;
uniform vec3 uCamRadial;   // Unit radial direction e_r on the wormhole celestial sphere
uniform vec3 uCamForward;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform samplerCube uSkyboxA; // Universe A (l > 0): Milky Way & spiral galaxies
uniform samplerCube uSkyboxB; // Universe B (l < 0): Golden-amber/teal nebula & ringed gas giant
uniform float uWormholeL;     // Signed proper distance l_cam in [-35, +35]
uniform float uWormholeRho;   // Throat radius rho
uniform float uWormholeA;     // Throat half-length a
uniform float uWormholeM;     // Lensing transition width M_w
uniform float uTanHalfFov;
uniform int uShowGrid;
uniform int uAdaptiveAa;

const float PI = 3.14159265359;
const int MAX_STEPS = 220;

// Exact Dneg / Interstellar Morris-Thorne shape function r(l) and dr/dl
void wormholeMetric(float l, out float r, out float drdl) {
    float absL = abs(l);
    float mw = max(0.08, uWormholeM);
    float x = max(0.0, (2.0 * (absL - uWormholeA)) / (PI * mw));
    float atanX = atan(x);
    r = max(0.15, uWormholeRho + mw * (x * atanX - 0.5 * log(1.0 + x * x)));
    float sgn = (l >= 0.0) ? 1.0 : -1.0;
    drdl = (2.0 / PI) * atanX * sgn;
}

void wormholeDeriv(float l, float vl, float H, out float dl, out float dvl, out float dphi) {
    float r, drdl;
    wormholeMetric(l, r, drdl);
    float invR2 = 1.0 / (r * r);
    dl = vl;
    dvl = (H * H * invR2 / r) * drdl;
    dphi = H * invR2;
}

struct WormholeRay {
    vec3 exitDir;
    float endL;
    float totalPhi;
    bool crossedThroat;
};

WormholeRay traceWormholeRay(vec2 pixelCoord) {
    vec2 ndc = (pixelCoord / uResolution) * 2.0 - 1.0;
    float aspect = uResolution.x / uResolution.y;

    vec3 dRay = normalize(uCamForward
                        + ndc.x * aspect * uTanHalfFov * uCamRight
                        + ndc.y * uTanHalfFov * uCamUp);

    vec3 eR = normalize(uCamRadial);
    float cosTheta = clamp(dot(dRay, eR), -1.0, 1.0);
    vec3 tanVec = dRay - cosTheta * eR;
    float sinTheta = length(tanVec);
    vec3 ePhi = (sinTheta > 1e-6) ? (tanVec / sinTheta) : vec3(0.0);

    float r0, dr0;
    wormholeMetric(uWormholeL, r0, dr0);

    float l = uWormholeL;
    float vl = cosTheta;
    float H = r0 * sinTheta;
    float phi = 0.0;

    float lEscape = max(42.0, abs(uWormholeL) + 22.0);
    bool startPos = (uWormholeL >= 0.0);
    bool crossed = false;

    for (int step = 0; step < MAX_STEPS; step++) {
        if (abs(l) > lEscape && (l * vl > 0.0)) {
            break;
        }

        // Adaptive step size: fine resolution inside/near throat, rapid integration in flat space
        float distThroat = max(0.0, abs(l) - uWormholeA);
        float h = clamp(0.065 * distThroat + 0.065 * uWormholeRho, 0.04, 2.20);

        float k1l, k1v, k1p;
        wormholeDeriv(l, vl, H, k1l, k1v, k1p);

        float k2l, k2v, k2p;
        wormholeDeriv(l + 0.5 * h * k1l, vl + 0.5 * h * k1v, H, k2l, k2v, k2p);

        float k3l, k3v, k3p;
        wormholeDeriv(l + 0.5 * h * k2l, vl + 0.5 * h * k2v, H, k3l, k3v, k3p);

        float k4l, k4v, k4p;
        wormholeDeriv(l + h * k3l, vl + h * k3v, H, k4l, k4v, k4p);

        float h6 = h * (1.0 / 6.0);
        l += h6 * (k1l + 2.0 * k2l + 2.0 * k3l + k4l);
        vl += h6 * (k1v + 2.0 * k2v + 2.0 * k3v + k4v);
        phi += h6 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);

        if ((l >= 0.0) != startPos) {
            crossed = true;
        }
    }

    // Orientation-preserving 3D exit direction continuous across l = 0
    float sgnL = (l >= 0.0) ? 1.0 : -1.0;
    WormholeRay res;
    res.exitDir = normalize(sgnL * cos(phi) * eR + sin(phi) * ePhi);
    res.endL = l;
    res.totalPhi = phi;
    res.crossedThroat = crossed;
    return res;
}

vec3 sampleUniverse(WormholeRay ray, vec3 ddx, vec3 ddy) {
    vec3 col = (ray.endL >= 0.0)
            ? textureGrad(uSkyboxA, ray.exitDir, ddx, ddy).rgb
            : textureGrad(uSkyboxB, ray.exitDir, ddx, ddy).rgb;

    if (uShowGrid == 1) {
        vec3 dir = ray.exitDir;
        float lon = atan(dir.z, dir.x);
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

    // Subtle physical caustic shimmer right along multi-winding Einstein ring near throat rim
    float rimCaustic = smoothstep(2.6, 5.8, ray.totalPhi) * 0.08;
    col += vec3(0.75, 0.90, 1.15) * rimCaustic;
    return col;
}

void main() {
    WormholeRay primary = traceWormholeRay(gl_FragCoord.xy);

    // Uniform-control-flow screen-space ray differentials for sharp textureGrad mipmapping
    vec3 rawDdx = dFdx(primary.exitDir);
    vec3 rawDdy = dFdy(primary.exitDir);
    float dirGrad = length(rawDdx) + length(rawDdy);

    float maxGrad = 0.14;
    vec3 ddx = rawDdx * min(1.0, maxGrad / max(1e-6, length(rawDdx)));
    vec3 ddy = rawDdy * min(1.0, maxGrad / max(1e-6, length(rawDdy)));

    vec3 c0 = sampleUniverse(primary, ddx, ddy);

    float sideMask = (primary.endL >= 0.0) ? 1.0 : 0.0;
    bool portalRimEdge = fwidth(sideMask) > 0.01;
    float luma0 = dot(c0, vec3(0.2126, 0.7152, 0.0722));
    bool needsAa = portalRimEdge
                || (primary.totalPhi > 2.2)
                || (dirGrad > 0.045)
                || (fwidth(luma0) > 0.15);

    if (uAdaptiveAa == 0 || !needsAa) {
        FragColor = vec4(c0, 1.0);
        return;
    }

    // Edge-aware Adaptive 4x R2 stratified AA along Einstein ring & portal rim
    vec2 r2Offsets[3] = vec2[](
        vec2( 0.254878,  0.069840),
        vec2(-0.490245, -0.360319),
        vec2(-0.235367,  0.209521)
    );

    vec3 sum = c0;
    vec3 subDdx = ddx * 0.5;
    vec3 subDdy = ddy * 0.5;

    for (int s = 0; s < 3; s++) {
        WormholeRay sub = traceWormholeRay(gl_FragCoord.xy + r2Offsets[s]);
        sum += sampleUniverse(sub, subDdx, subDdy);
    }

    FragColor = vec4(sum * 0.25, 1.0);
}
