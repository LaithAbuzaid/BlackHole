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
    return max(vec3(0.0), mix(c, vec3(0.0), bad));
}

vec3 toneMapACES(vec3 x) {
    x = sanitize(x);
    vec3 mapped = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
    return pow(clamp(mapped, 0.0, 1.0), vec3(1.0 / 2.2));
}

// Bright-pass luminance knee so anamorphic flare only emanates from hot ISCO / TDE peaks
// without lifting black levels inside the pitch-black event horizon shadow.
vec3 sampleAnamorphicTap(vec2 uv) {
    vec3 s = sanitize(texture(uAnamorphicTex, clamp(uv, vec2(0.002), vec2(0.998))).rgb);
    float luma = dot(s, vec3(0.2126, 0.7152, 0.0722));
    float knee = smoothstep(0.48, 1.35, luma);
    return s * knee;
}

void main() {
    vec2 fromCenter = vUv - 0.5;
    float r2 = dot(fromCenter, fromCenter);
    vec2 caOffset = fromCenter * r2 * 0.0032;

    float r = texture(uHdrScene, vUv + caOffset).r;
    float g = texture(uHdrScene, vUv).g;
    float b = texture(uHdrScene, vUv - caOffset).b;
    vec3 hdr = sanitize(vec3(r, g, b));

    vec3 bloom = sanitize(texture(uBloomTex, vUv).rgb);

    // Subtle 7-tap wide horizontal Interstellar-style anamorphic lens streak
    vec3 anamorphic = vec3(0.0);
    if (uAnamorphicStrength > 0.001) {
        const float stepU = 0.034;
        vec3 streak = sampleAnamorphicTap(vUv) * 0.24
                    + (sampleAnamorphicTap(vUv + vec2( stepU, 0.0)) + sampleAnamorphicTap(vUv - vec2( stepU, 0.0))) * 0.21
                    + (sampleAnamorphicTap(vUv + vec2( stepU * 2.2, 0.0)) + sampleAnamorphicTap(vUv - vec2( stepU * 2.2, 0.0))) * 0.14
                    + (sampleAnamorphicTap(vUv + vec2( stepU * 3.8, 0.0)) + sampleAnamorphicTap(vUv - vec2( stepU * 3.8, 0.0))) * 0.08;
        // Preserve pure pitch-black event horizon interior (zero lifted blacks inside shadow)
        float hdrLuma = dot(hdr, vec3(0.2126, 0.7152, 0.0722));
        float shadowGuard = mix(0.06, 1.0, smoothstep(0.0, 0.045, hdrLuma));
        vec3 lensTint = vec3(0.72, 0.88, 1.24);
        anamorphic = streak * lensTint * (uAnamorphicStrength * 0.85 * shadowGuard);
    }

    vec3 combined = (hdr + bloom * uBloomStrength + anamorphic) * uExposure;

    float vignette = 1.0 - r2 * 0.26;
    FragColor = vec4(toneMapACES(combined * vignette), 1.0);
}
