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

// Ray-sphere & ray-ring renderer for a large banded gas giant positioned near the wormhole throat axis
vec3 renderRingedGasGiant(vec3 dir, inout float skyMask) {
    vec3 planetCenter = normalize(vec3(0.22, 0.08, -0.972)) * 11.5;
    float planetR = 1.65;

    // Tilted ring plane normal and orthonormal basis
    vec3 ringNormal = normalize(vec3(-0.34, 0.88, 0.33));
    vec3 ringU = normalize(cross(ringNormal, vec3(0.0, 0.0, 1.0)));
    vec3 ringV = cross(ringNormal, ringU);
    vec3 sunDir = normalize(vec3(-0.72, 0.42, 0.55));

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

    // Distinct tilted golden-amber & teal emission nebula belt (orthogonal tilt vs Universe A)
    float nebLat = dir.y * 0.72 + dir.x * 0.64 - dir.z * 0.27;
    float belt = exp(-(nebLat * nebLat) / 0.085);
    float coreBelt = exp(-(nebLat * nebLat) / 0.018);

    float n1 = texture(uNoiseVol, dir * 0.65 + vec3(0.41, -0.28, 0.19)).r;
    float n2 = texture(uNoiseVol, dir * 1.65 - vec3(0.22, 0.55, -0.34)).r;
    float n3 = texture(uNoiseVol, dir * 3.40 + vec3(-0.18, 0.27, 0.61)).r;
    float filaments = pow(clamp(n1 * 0.52 + n2 * 0.33 + n3 * 0.15, 0.0, 1.0), 1.35);
    float darkPillars = smoothstep(0.46, 0.76, n2 * 0.65 + n3 * 0.35);

    vec3 deepTeal = vec3(0.05, 0.36, 0.46);
    vec3 brightCyan = vec3(0.14, 0.74, 0.82);
    vec3 goldenAmber = vec3(1.12, 0.62, 0.18);
    vec3 warmGold = vec3(1.38, 0.94, 0.42);

    vec3 nebula = mix(deepTeal, brightCyan, smoothstep(0.28, 0.74, n1)) * (0.42 + 0.60 * belt);
    nebula += mix(goldenAmber, warmGold, coreBelt * n2) * (0.35 + 1.35 * belt) * smoothstep(0.26, 0.70, filaments) * 1.35;
    nebula *= (1.0 - 0.70 * darkPillars * exp(-(nebLat * nebLat) / 0.028));

    // Rich cosmic emerald-indigo background
    vec3 col = vec3(0.0020, 0.0052, 0.0078) + nebula * 0.62;

    // Bright golden illuminating star cluster on opposite limb
    vec3 sunAxis = normalize(vec3(-0.72, 0.42, 0.55));
    float sunDot = max(0.0, dot(dir, sunAxis));
    col += vec3(1.65, 1.24, 0.72) * (pow(sunDot, 1200.0) * 6.5 + pow(sunDot, 45.0) * 0.55);

    // Dense sparkling star clusters (4 layers including dense golden cluster near throat)
    vec3 clusterDir = normalize(vec3(-0.28, -0.14, -0.95));
    float clusterGlow = exp(-pow(length(dir - clusterDir) / 0.16, 2.0));
    col += vec3(0.48, 0.36, 0.18) * clusterGlow * 0.45;

    for (int layer = 0; layer < 3; layer++) {
        float scale = (layer == 0) ? 235.0 : ((layer == 1) ? 124.0 : 62.0);
        vec3 cell = floor(dir * scale);
        vec3 local = fract(dir * scale) - 0.5;
        float rnd = hash31(cell + float(layer) * 73.0 + 19.0);

        float baseThresh = (layer == 0) ? 0.946 : ((layer == 1) ? 0.974 : 0.989);
        float thresh = baseThresh - clusterGlow * 0.035 - belt * 0.012;
        if (rnd > thresh) {
            vec3 jitter = vec3(
                hash31(cell + 3.1) - 0.5,
                hash31(cell + 5.7) - 0.5,
                hash31(cell + 7.3) - 0.5
            ) * 0.62;
            float d = length(local - jitter);
            float mag = (rnd - thresh) / max(1e-4, 1.0 - thresh);

            float core = exp(-d * d * (layer == 2 ? 76.0 : 126.0));
            float halo = exp(-d * d * (layer == 2 ? 14.0 : 24.0)) * 0.24;

            vec3 tint = starTintB(hash31(cell + 11.4));
            float gain = (layer == 0) ? 2.0 : ((layer == 1) ? 3.8 : 8.2);
            col += tint * (core + halo) * pow(mag, 1.85) * gain;
        }
    }

    // Composite large ringed gas giant near throat axis for dramatic lensed ghost arcs
    float skyMask = 1.0;
    vec3 planetLayer = renderRingedGasGiant(dir, skyMask);
    col = col * skyMask + planetLayer;

    FragColor = vec4(col, 1.0);
}
