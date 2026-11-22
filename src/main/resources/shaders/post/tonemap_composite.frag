#version 330 core
in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uHdrScene;
uniform sampler2D uBloomTex;
uniform sampler2D uAnamorphicTex;
uniform float uBloomStrength;
uniform float uAnamorphicStrength;
uniform float uExposure;

vec3 sanitize(vec3 c) {
    bvec3 bad = bvec3(isnan(c.x) || isinf(c.x),
                      isnan(c.y) || isinf(c.y),
                      isnan(c.z) || isinf(c.z));
    return clamp(mix(c, vec3(0.0), bad), vec3(0.0), vec3(64.0));
}

// Stephen Hill ACES RRT + ODT fitted matrices (preserves blue/cyan jet & O-star hues without purple clip)
const mat3 ACES_INPUT_MAT = mat3(
    0.59719, 0.07600, 0.02840,
    0.35458, 0.90834, 0.13383,
    0.04823, 0.01566, 0.83777
);

const mat3 ACES_OUTPUT_MAT = mat3(
     1.60475, -0.10208, -0.00327,
    -0.53108,  1.10813, -0.07276,
    -0.07367, -0.00605,  1.07602
);

vec3 rrtAndOdtFit(vec3 v) {
    vec3 a = v * (v + 0.0245786) - 0.000090537;
    vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
    return a / b;
}

// Exact IEC 61966-2-1 linear-to-sRGB OETF (eliminates >3.3x dark-toe lift of naive pow(1/2.2))
vec3 linearToSrgb(vec3 c) {
    vec3 lo = 12.92 * c;
    vec3 hi = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, greaterThan(c, vec3(0.0031308)));
}

vec3 toneMapACESFitted(vec3 x) {
    x = sanitize(x);
    vec3 aces = ACES_INPUT_MAT * x;
    aces = rrtAndOdtFit(aces);
    vec3 linearOut = clamp(ACES_OUTPUT_MAT * aces, 0.0, 1.0);
    return linearToSrgb(linearOut);
}

// Exact inverse-CDF Triangular PDF (TPDF) dither from a single Interleaved Gradient Noise sample
float tpdfDither(vec2 px) {
    float u = fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
    float orig = u * 2.0 - 1.0;
    float tri = sign(orig) * (1.0 - sqrt(max(0.0, 1.0 - abs(orig))));
    return tri * (1.0 / 255.0);
}

void main() {
    vec2 fromCenter = vUv - 0.5;
    float r2 = dot(fromCenter, fromCenter);
    vec2 caOffset = fromCenter * r2 * 0.0018;

    float r = texture(uHdrScene, vUv + caOffset).r;
    float g = texture(uHdrScene, vUv).g;
    float b = texture(uHdrScene, vUv - caOffset).b;
    vec3 hdr = sanitize(vec3(r, g, b));

    vec3 bloom = (uBloomStrength > 0.001) ? sanitize(texture(uBloomTex, vUv).rgb) : vec3(0.0);

    vec3 anamorphic = vec3(0.0);
    if (uAnamorphicStrength > 0.001) {
        vec3 streak = sanitize(texture(uAnamorphicTex, vUv).rgb);
        vec3 lensTint = vec3(0.76, 0.90, 1.30);
        anamorphic = streak * lensTint * (uAnamorphicStrength * 0.88);
    }

    vec3 combined = (hdr + bloom * (uBloomStrength * 0.44) + anamorphic) * uExposure;

    float vignette = 1.0 - r2 * 0.20;
    vec3 ldr = toneMapACESFitted(combined * vignette);

    float dither = tpdfDither(gl_FragCoord.xy);
    FragColor = vec4(clamp(ldr + dither, 0.0, 1.0), 1.0);
}
