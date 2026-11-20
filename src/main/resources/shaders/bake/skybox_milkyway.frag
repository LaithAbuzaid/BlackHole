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

vec3 renderSpiralGalaxy(vec3 dir, vec3 center, vec3 axisU, vec3 axisV, float radius, float tilt, vec3 coreTint, vec3 armTint) {
    vec3 diff = dir - center;
    if (dot(diff, diff) > (radius * 2.8) * (radius * 2.8)) return vec3(0.0);

    float x = dot(diff, axisU) / radius;
    float y = dot(diff, axisV) / (radius * tilt);
    float r = sqrt(x * x + y * y);
    if (r > 2.5) return vec3(0.0);

    float ang = atan(y, x);
    float arm1 = 0.5 + 0.5 * cos(2.0 * ang - 6.6 * log(max(0.06, r)));
    float arm2 = 0.5 + 0.5 * cos(4.0 * ang - 11.2 * log(max(0.08, r)) + 0.8);
    float spiral = pow(arm1, 1.35) * 0.72 + arm2 * 0.28;

    float dustLane = smoothstep(0.15, 0.85, 0.5 + 0.5 * sin(2.0 * ang - 6.6 * log(max(0.06, r)) + 0.55));
    float bulge = exp(-r * r * 14.0) * 2.6;
    float disk = exp(-r * 2.35) * (0.32 + 0.92 * spiral) * (1.0 - 0.35 * dustLane * smoothstep(0.2, 1.0, r));

    return coreTint * bulge + armTint * disk;
}

// Harvard Spectral Classification (O, B, A, F, G, K, M) in linear HDR space
vec3 harvardStarColor(float s) {
    if (s < 0.05) return vec3(0.54, 0.72, 1.55); // O-type blue supergiant
    if (s < 0.16) return vec3(0.68, 0.84, 1.38); // B-type blue-white
    if (s < 0.32) return vec3(0.92, 0.96, 1.18); // A-type white-blue
    if (s < 0.50) return vec3(1.06, 1.04, 0.96); // F-type yellow-white
    if (s < 0.70) return vec3(1.18, 0.98, 0.76); // G-type solar yellow
    if (s < 0.86) return vec3(1.30, 0.82, 0.52); // K-type orange
    return vec3(1.42, 0.56, 0.36);               // M-type red dwarf/giant
}

void main() {
    vec2 uv = (gl_FragCoord.xy / uFaceSize) * 2.0 - 1.0;
    vec3 dir = getCubemapDir(uFace, uv);

    // Tilted Milky Way galactic plane with dark molecular dust rift & H-alpha clouds
    float galLat = dir.y * 0.838 - dir.x * 0.545;
    float band = exp(-(galLat * galLat) / 0.030);
    float coreBand = exp(-(galLat * galLat) / 0.0085);

    float neb1 = texture(uNoiseVol, dir * 0.55 + vec3(0.15, 0.42, -0.26)).r;
    float neb2 = texture(uNoiseVol, dir * 1.55 - vec3(0.51, 0.22, 0.36)).r;
    float neb3 = texture(uNoiseVol, dir * 3.40 + vec3(0.32, -0.44, 0.18)).r;
    float dustRift = smoothstep(0.32, 0.64, neb1 * 0.52 + neb2 * 0.32 + neb3 * 0.16);

    vec3 warmBulge = vec3(0.46, 0.27, 0.14);
    vec3 blueNebula = vec3(0.08, 0.16, 0.36);
    vec3 hAlpha = vec3(0.52, 0.09, 0.22) * smoothstep(0.60, 0.84, neb2 * 0.68 + neb3 * 0.32);
    vec3 nebCol = mix(blueNebula, warmBulge, coreBand * (0.38 + 0.62 * neb2)) + hAlpha;

    // Deep dark cosmic space + sculpted Milky Way band carved by dark dust rift
    vec3 col = vec3(0.00045, 0.00075, 0.0018)
             + nebCol * band * (0.18 + 0.52 * neb1)
             * (1.0 - 0.90 * dustRift * exp(-(galLat * galLat) / 0.0055));

    // Spiral background galaxies placed for subtle lensed arcs without overwhelming the hero black hole
    col += renderSpiralGalaxy(dir, normalize(vec3(-0.34, 0.14, -0.93)),
            normalize(vec3(0.93, 0.0, -0.34)), vec3(0.0, 1.0, 0.0), 0.048, 0.42,
            vec3(1.10, 0.84, 0.62), vec3(0.64, 0.78, 1.12));
    col += renderSpiralGalaxy(dir, normalize(vec3(0.24, -0.15, -0.96)),
            normalize(vec3(0.96, 0.0, 0.24)), vec3(0.0, 1.0, 0.0), 0.040, 0.46,
            vec3(1.05, 0.80, 0.60), vec3(0.58, 0.82, 1.18));
    col += renderSpiralGalaxy(dir, normalize(vec3(-0.88, 0.14, 0.45)),
            normalize(vec3(0.45, 0.0, 0.88)), vec3(0.0, 1.0, 0.0), 0.046, 0.40,
            vec3(1.05, 0.82, 0.60), vec3(0.74, 0.84, 1.12));
    col += renderSpiralGalaxy(dir, normalize(vec3(0.48, 0.22, 0.85)),
            normalize(vec3(-0.85, 0.0, 0.48)), vec3(0.0, 1.0, 0.0), 0.044, 0.45,
            vec3(1.08, 0.84, 0.64), vec3(0.72, 0.82, 1.14));

    // Crisp multi-scale Harvard spectral starfield (tight sub-degree pinpoints prevent bloated Einstein hoops)
    for (int layer = 0; layer < 3; layer++) {
        float scale = (layer == 0) ? 265.0 : ((layer == 1) ? 162.0 : 96.0);
        vec3 cell = floor(dir * scale);
        vec3 local = fract(dir * scale) - 0.5;
        float rnd = hash31(cell + float(layer) * 53.0 + 13.7);

        float baseThresh = (layer == 0) ? 0.948 : ((layer == 1) ? 0.978 : 0.9925);
        float thresh = baseThresh - band * (layer == 0 ? 0.022 : (layer == 1 ? 0.008 : 0.002));
        if (rnd > thresh) {
            vec3 jitter = vec3(
                hash31(cell + 1.3) - 0.5,
                hash31(cell + 2.7) - 0.5,
                hash31(cell + 4.1) - 0.5
            ) * 0.58;
            float d = length(local - jitter);
            float mag = (rnd - thresh) / max(1e-4, 1.0 - thresh);

            float core = exp(-d * d * (layer == 2 ? 210.0 : (layer == 1 ? 185.0 : 195.0)));
            float halo = exp(-d * d * (layer == 2 ? 38.0 : 46.0)) * (layer == 2 ? 0.14 : 0.08);

            vec3 starTint = harvardStarColor(hash31(cell + 8.9));
            float gain = (layer == 0) ? 1.45 : ((layer == 1) ? 2.75 : 4.60);
            col += starTint * (core + halo) * pow(mag, 2.05) * gain;
        }
    }

    FragColor = vec4(col, 1.0);
}
