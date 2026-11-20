#version 330 core
out vec4 FragColor;

uniform float uFaceSize;
uniform int uFace;
uniform sampler3D uNoiseVol;

const float PI = 3.14159265359;

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

// Ray-sphere & ray-ring renderer for a large banded gas giant positioned across the wormhole caustic
vec3 renderRingedGasGiant(vec3 dir, inout float skyMask) {
    vec3 planetCenter = normalize(vec3(0.42, 0.16, -0.893)) * 6.8;
    float planetR = 1.72;

    // Tilted ring plane normal and orthonormal basis
    vec3 ringNormal = normalize(vec3(-0.36, 0.86, 0.36));
    vec3 ringU = normalize(cross(ringNormal, vec3(0.0, 0.0, 1.0)));
    vec3 ringV = cross(ringNormal, ringU);
    vec3 sunDir = normalize(vec3(-0.74, 0.40, 0.54));

    // Ray-sphere intersection (camera at origin)
    float b = dot(dir, planetCenter);
    float c = dot(planetCenter, planetCenter) - planetR * planetR;
    float disc = b * b - c;

    float tSphere = 1e9;
    bool hitSphere = false;
    if (disc > 0.0) {
        float t = b - sqrt(disc);
        if (t > 0.0) {
            tSphere = t;
            hitSphere = true;
        }
    }

    // Ray-ring plane intersection
    float denom = dot(dir, ringNormal);
    float tRing = 1e9;
    bool hitRing = false;
    vec3 ringPos = vec3(0.0);
    float ringR = 0.0;
    if (abs(denom) > 1e-5) {
        float t = dot(planetCenter, ringNormal) / denom;
        if (t > 0.0) {
            vec3 p = dir * t - planetCenter;
            float r = length(p);
            if (r > planetR * 1.24 && r < planetR * 2.55) {
                tRing = t;
                hitRing = true;
                ringPos = p;
                ringR = r;
            }
        }
    }

    vec3 planetCol = vec3(0.0);
    float planetAlpha = 0.0;

    if (hitSphere) {
        vec3 pHit = dir * tSphere;
        vec3 n = normalize(pHit - planetCenter);
        float lat = dot(n, ringNormal);
        float lon = atan(dot(n, ringV), dot(n, ringU));

        // Turbulent golden-ochre, cream, and teal-slate atmospheric cloud bands
        float turb1 = texture(uNoiseVol, n * 1.85 + vec3(0.24, 0.51, 0.17)).r;
        float turb2 = texture(uNoiseVol, n * 4.40 - vec3(0.37, 0.12, 0.62)).r;
        float bandCoord = lat * 12.0 + (turb1 - 0.5) * 1.65 + (turb2 - 0.5) * 0.45;
        float bands = 0.5 + 0.5 * sin(bandCoord);
        float storm = exp(-pow((lat - 0.18) / 0.11, 2.0) - pow(sin(lon - 0.7) / 0.28, 2.0));

        vec3 creamBand = vec3(1.18, 0.96, 0.68);
        vec3 amberBand = vec3(0.92, 0.56, 0.24);
        vec3 tealBand = vec3(0.26, 0.56, 0.60);
        vec3 albedo = mix(amberBand, creamBand, bands);
        albedo = mix(albedo, tealBand, smoothstep(0.45, 0.85, abs(lat)) * 0.55);
        albedo = mix(albedo, vec3(1.35, 0.52, 0.22), storm * 0.75);

        // Ring shadow cast onto planet cloud tops
        float ringShadow = 1.0;
        float sunDenom = dot(sunDir, ringNormal);
        if (abs(sunDenom) > 1e-4) {
            float tSh = -dot(pHit - planetCenter, ringNormal) / sunDenom;
            if (tSh > 0.0) {
                float rSh = length((pHit - planetCenter) + sunDir * tSh);
                if (rSh > planetR * 1.24 && rSh < planetR * 2.55) {
                    float uSh = (rSh - planetR * 1.24) / (planetR * 1.31);
                    float cassini = 1.0 - exp(-pow((uSh - 0.62) / 0.045, 2.0));
                    ringShadow = mix(1.0, 0.24, cassini * smoothstep(0.0, 0.08, uSh) * smoothstep(1.0, 0.90, uSh));
                }
            }
        }

        float ndl = max(0.0, dot(n, sunDir));
        float terminator = smoothstep(-0.08, 0.22, dot(n, sunDir));
        float fresnel = pow(1.0 - max(0.0, dot(n, -dir)), 3.2);
        vec3 rimGlow = vec3(0.42, 0.92, 1.12) * fresnel * (0.28 + 0.85 * terminator);
        vec3 ambient = vec3(0.03, 0.05, 0.06);

        planetCol = albedo * (ambient + vec3(1.42, 1.18, 0.86) * ndl * ringShadow) + rimGlow;
        planetAlpha = 1.0;
    }

    vec3 ringCol = vec3(0.0);
    float ringAlpha = 0.0;
    if (hitRing) {
        float u = (ringR - planetR * 1.24) / (planetR * 1.31);
        float fineRinglets = 0.5 + 0.5 * sin(u * 140.0 + texture(uNoiseVol, ringPos * 0.35).r * 3.0);
        float medBands = 0.5 + 0.5 * cos(u * 38.0);
        float cassiniGap = 1.0 - 0.88 * exp(-pow((u - 0.62) / 0.042, 2.0));
        float enckeGap = 1.0 - 0.72 * exp(-pow((u - 0.86) / 0.014, 2.0));
        float edgeFade = smoothstep(0.0, 0.07, u) * smoothstep(1.0, 0.92, u);

        float density = edgeFade * cassiniGap * enckeGap * (0.42 + 0.38 * medBands + 0.20 * fineRinglets);

        // Planet shadow cast onto rings
        float proj = dot(ringPos, sunDir);
        float perp2 = dot(ringPos, ringPos) - proj * proj;
        float planetShadow = (proj < 0.0 && perp2 < planetR * planetR)
                ? smoothstep(planetR * 0.94, planetR * 1.03, sqrt(max(0.0, perp2)))
                : 1.0;

        vec3 innerTint = vec3(1.30, 1.02, 0.66);
        vec3 outerTint = vec3(0.62, 0.98, 1.05);
        vec3 baseRing = mix(innerTint, outerTint, u) * (0.75 + 0.45 * fineRinglets);

        ringCol = baseRing * (0.06 + 1.28 * planetShadow);
        ringAlpha = clamp(density * 0.88, 0.0, 0.92);
    }

    vec3 outCol = vec3(0.0);
    if (hitRing && hitSphere) {
        if (tRing < tSphere) {
            outCol = ringCol * ringAlpha + planetCol * (1.0 - ringAlpha);
            skyMask = 0.0;
        } else {
            outCol = planetCol;
            skyMask = 0.0;
        }
    } else if (hitSphere) {
        outCol = planetCol;
        skyMask = 0.0;
    } else if (hitRing) {
        outCol = ringCol * ringAlpha;
        skyMask = 1.0 - ringAlpha;
    }
    return outCol;
}

vec3 starTintB(float s) {
    if (s < 0.18) return vec3(0.48, 0.96, 1.42); // Cyan-teal OB giant
    if (s < 0.40) return vec3(0.88, 1.05, 1.18); // Bright white-aqua
    if (s < 0.72) return vec3(1.26, 0.96, 0.56); // Warm golden giant
    return vec3(1.44, 0.68, 0.34);               // Deep amber-orange star
}

void main() {
    vec2 uv = (gl_FragCoord.xy / uFaceSize) * 2.0 - 1.0;
    vec3 dir = getCubemapDir(uFace, uv);

    // High-contrast astrophotography-style deep-space teal & golden-amber nebula belt
    float nebLat = dir.y * 0.72 + dir.x * 0.64 - dir.z * 0.27;
    float belt = exp(-(nebLat * nebLat) / 0.055);
    float coreBelt = exp(-(nebLat * nebLat) / 0.014);

    // Domain-warped multi-scale nebula wisps (eliminates flat Perlin-cloud wallpaper look)
    vec3 warp = vec3(
        texture(uNoiseVol, dir * 0.70 + vec3(0.11, 0.37, -0.21)).r,
        texture(uNoiseVol, dir * 0.70 + vec3(-0.42, 0.19, 0.53)).r,
        texture(uNoiseVol, dir * 0.70 + vec3(0.28, -0.61, 0.14)).r
    ) - 0.5;
    vec3 wDir = dir + warp * 0.32;

    float n1 = texture(uNoiseVol, wDir * 0.65 + vec3(0.41, -0.28, 0.19)).r;
    float n2 = texture(uNoiseVol, wDir * 1.75 - vec3(0.22, 0.55, -0.34)).r;
    float n3 = texture(uNoiseVol, wDir * 3.80 + vec3(-0.18, 0.27, 0.61)).r;
    float ridge = 1.0 - abs(n2 * 2.0 - 1.0);
    float wisps = pow(clamp(n1 * 0.45 + ridge * 0.40 + n3 * 0.15, 0.0, 1.0), 2.15);
    float darkPillars = smoothstep(0.44, 0.72, n1 * 0.50 + n2 * 0.35 + n3 * 0.15);

    vec3 deepSapphire = vec3(0.015, 0.09, 0.17);
    vec3 brightTeal = vec3(0.06, 0.46, 0.58);
    vec3 goldenAmber = vec3(0.95, 0.48, 0.14);
    vec3 warmGold = vec3(1.32, 0.84, 0.36);

    vec3 nebula = mix(deepSapphire, brightTeal, smoothstep(0.36, 0.78, n1 * 0.6 + ridge * 0.4))
                * (0.14 + 0.86 * belt) * smoothstep(0.22, 0.72, wisps);
    nebula += mix(goldenAmber, warmGold, coreBelt * ridge)
            * (0.18 + 1.25 * belt) * smoothstep(0.34, 0.78, wisps) * 1.15;
    nebula *= (1.0 - 0.82 * darkPillars * exp(-(nebLat * nebLat) / 0.022));

    // Deep dark cosmic space so nebula filaments, stars, and ringed gas giant pop dramatically
    vec3 col = vec3(0.0007, 0.0018, 0.0034) + nebula * 0.72;

    // Bright golden illuminating star on opposite limb
    vec3 sunAxis = normalize(vec3(-0.74, 0.40, 0.54));
    float sunDot = max(0.0, dot(dir, sunAxis));
    col += vec3(1.65, 1.22, 0.72) * (pow(sunDot, 1600.0) * 6.0 + pow(sunDot, 55.0) * 0.35);

    // Dense sparkling golden star cluster offset from throat center
    vec3 clusterDir = normalize(vec3(-0.34, -0.18, -0.92));
    float clusterGlow = exp(-pow(length(dir - clusterDir) / 0.14, 2.0));
    col += vec3(0.42, 0.30, 0.15) * clusterGlow * 0.35;

    for (int layer = 0; layer < 3; layer++) {
        float scale = (layer == 0) ? 265.0 : ((layer == 1) ? 160.0 : 94.0);
        vec3 cell = floor(dir * scale);
        vec3 local = fract(dir * scale) - 0.5;
        float rnd = hash31(cell + float(layer) * 73.0 + 19.0);

        float baseThresh = (layer == 0) ? 0.946 : ((layer == 1) ? 0.978 : 0.992);
        float thresh = baseThresh - clusterGlow * 0.032 - belt * 0.014;
        if (rnd > thresh) {
            vec3 jitter = vec3(
                hash31(cell + 3.1) - 0.5,
                hash31(cell + 5.7) - 0.5,
                hash31(cell + 7.3) - 0.5
            ) * 0.58;
            float d = length(local - jitter);
            float mag = (rnd - thresh) / max(1e-4, 1.0 - thresh);

            float core = exp(-d * d * (layer == 2 ? 205.0 : 190.0));
            float halo = exp(-d * d * (layer == 2 ? 38.0 : 48.0)) * (layer == 2 ? 0.14 : 0.08);

            vec3 tint = starTintB(hash31(cell + 11.4));
            float gain = (layer == 0) ? 1.55 : ((layer == 1) ? 2.85 : 4.80);
            col += tint * (core + halo) * pow(mag, 2.0) * gain;
        }
    }

    // Composite large ringed gas giant near throat axis for dramatic lensed ghost arcs
    float skyMask = 1.0;
    vec3 planetLayer = renderRingedGasGiant(dir, skyMask);
    col = col * skyMask + planetLayer;

    FragColor = vec4(col, 1.0);
}
