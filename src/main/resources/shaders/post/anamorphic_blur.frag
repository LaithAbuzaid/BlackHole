#version 330 core
in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uSource;
uniform float uStepScale;
uniform int uExtractThreshold;

vec3 sampleTap(vec2 uv) {
    // Zero-pad outside [0, 1] viewport bounds (prevents bright border-clamped horizontal edge streaks)
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        return vec3(0.0);
    }
    vec3 c = texture(uSource, uv).rgb;
    bvec3 bad = bvec3(isnan(c.x) || isinf(c.x),
                      isnan(c.y) || isinf(c.y),
                      isnan(c.z) || isinf(c.z));
    c = clamp(mix(c, vec3(0.0), bad), vec3(0.0), vec3(32.0));
    if (uExtractThreshold == 1) {
        float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float knee = smoothstep(0.40, 1.55, lum);
        c *= knee;
    }
    return c;
}

void main() {
    // Resolution-independent UV stride anchored to 1/8 of 1920px (identical streak width at 720p, 1080p, 4K)
    float dx = (1.0 / 240.0) * uStepScale;

    // 13-tap wide horizontal Gaussian kernel
    const float w0 = 0.145;
    const float w1 = 0.136;
    const float w2 = 0.115;
    const float w3 = 0.088;
    const float w4 = 0.061;
    const float w5 = 0.038;
    const float w6 = 0.022;

    vec3 sum = sampleTap(vUv) * w0;
    sum += (sampleTap(vUv + vec2(dx * 1.5, 0.0)) + sampleTap(vUv - vec2(dx * 1.5, 0.0))) * w1;
    sum += (sampleTap(vUv + vec2(dx * 3.5, 0.0)) + sampleTap(vUv - vec2(dx * 3.5, 0.0))) * w2;
    sum += (sampleTap(vUv + vec2(dx * 6.0, 0.0)) + sampleTap(vUv - vec2(dx * 6.0, 0.0))) * w3;
    sum += (sampleTap(vUv + vec2(dx * 9.0, 0.0)) + sampleTap(vUv - vec2(dx * 9.0, 0.0))) * w4;
    sum += (sampleTap(vUv + vec2(dx * 12.5, 0.0)) + sampleTap(vUv - vec2(dx * 12.5, 0.0))) * w5;
    sum += (sampleTap(vUv + vec2(dx * 16.5, 0.0)) + sampleTap(vUv - vec2(dx * 16.5, 0.0))) * w6;

    FragColor = vec4(max(vec3(0.0), sum * (1.0 / 1.065)), 1.0);
}
