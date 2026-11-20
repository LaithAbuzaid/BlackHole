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

// Interleaved Gradient Noise (Jimenez 2014) for TPDF 8-bit post-tonemap dithering
float ign(vec2 px) {
    return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec2 fromCenter = vUv - 0.5;
    float r2 = dot(fromCenter, fromCenter);
    vec2 caOffset = fromCenter * r2 * 0.0020;

    float r = texture(uHdrScene, vUv + caOffset).r;
    float g = texture(uHdrScene, vUv).g;
    float b = texture(uHdrScene, vUv - caOffset).b;
    vec3 hdr = sanitize(vec3(r, g, b));

    // Proper HDR bloom pyramid (already Karis-filtered and soft-knee thresholded)
    vec3 bloom = sanitize(texture(uBloomTex, vUv).rgb);

    // 2-pass cascaded horizontal Interstellar-style anamorphic lens streak
    vec3 anamorphic = vec3(0.0);
    if (uAnamorphicStrength > 0.001) {
        vec3 streak = sanitize(texture(uAnamorphicTex, vUv).rgb);
        // Gentle shadow protection keeps the center of the event horizon pure pitch black
        // while allowing realistic optical veil along the bright horizon silhouette.
        float hdrLuma = dot(hdr, vec3(0.2126, 0.7152, 0.0722));
        float shadowGuard = mix(0.28, 1.0, smoothstep(0.0, 0.06, hdrLuma));
        vec3 lensTint = vec3(0.74, 0.89, 1.32);
        anamorphic = streak * lensTint * (uAnamorphicStrength * 0.92 * shadowGuard);
    }

    vec3 combined = (hdr + bloom * (uBloomStrength * 0.42) + anamorphic) * uExposure;

    float vignette = 1.0 - r2 * 0.22;
    vec3 ldr = toneMapACES(combined * vignette);

    // TPDF dithering eliminates 8-bit quantization banding in deep-space & plunge gradients
    float dither = (ign(gl_FragCoord.xy) - ign(gl_FragCoord.xy + vec2(17.0, 31.0))) * (1.0 / 255.0);
    FragColor = vec4(clamp(ldr + dither, 0.0, 1.0), 1.0);
}
