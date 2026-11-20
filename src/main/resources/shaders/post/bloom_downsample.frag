#version 330 core
in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uSource;
uniform int uIsFirstPass;

vec3 safeSample(vec2 uv) {
    vec3 c = texture(uSource, uv).rgb;
    bvec3 bad = bvec3(isnan(c.x) || isinf(c.x),
                      isnan(c.y) || isinf(c.y),
                      isnan(c.z) || isinf(c.z));
    return clamp(mix(c, vec3(0.0), bad), vec3(0.0), vec3(48.0));
}

float karisWeight(vec3 c) {
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return 1.0 / (1.0 + luma);
}

vec3 softKneeThreshold(vec3 c, float threshold, float knee) {
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float soft = clamp(lum - threshold + knee, 0.0, 2.0 * knee);
    soft = (soft * soft) / (4.0 * knee + 1e-5);
    float contrib = max(soft, lum - threshold) / max(lum, 1e-5);
    return c * contrib;
}

void main() {
    vec2 texel = 1.0 / vec2(textureSize(uSource, 0));
    float x = texel.x;
    float y = texel.y;

    vec3 a = safeSample(vec2(vUv.x - 2.0 * x, vUv.y + 2.0 * y));
    vec3 b = safeSample(vec2(vUv.x,           vUv.y + 2.0 * y));
    vec3 c = safeSample(vec2(vUv.x + 2.0 * x, vUv.y + 2.0 * y));
    vec3 d = safeSample(vec2(vUv.x - 2.0 * x, vUv.y));
    vec3 e = safeSample(vec2(vUv.x,           vUv.y));
    vec3 f = safeSample(vec2(vUv.x + 2.0 * x, vUv.y));
    vec3 g = safeSample(vec2(vUv.x - 2.0 * x, vUv.y - 2.0 * y));
    vec3 h = safeSample(vec2(vUv.x,           vUv.y - 2.0 * y));
    vec3 i = safeSample(vec2(vUv.x + 2.0 * x, vUv.y - 2.0 * y));

    vec3 j = safeSample(vec2(vUv.x - x, vUv.y + y));
    vec3 k = safeSample(vec2(vUv.x + x, vUv.y + y));
    vec3 l = safeSample(vec2(vUv.x - x, vUv.y - y));
    vec3 m = safeSample(vec2(vUv.x + x, vUv.y - y));

    vec3 downsample;
    if (uIsFirstPass == 1) {
        // Proper Jimenez/Karis normalized inverse-luma weighted average:
        // suppresses single-pixel fireflies while preserving full HDR dynamic range.
        vec3 g0 = (a + b + d + e) * 0.25;
        vec3 g1 = (b + c + e + f) * 0.25;
        vec3 g2 = (d + e + g + h) * 0.25;
        vec3 g3 = (e + f + h + i) * 0.25;
        vec3 g4 = (j + k + l + m) * 0.25;

        float w0 = karisWeight(g0) * 0.125;
        float w1 = karisWeight(g1) * 0.125;
        float w2 = karisWeight(g2) * 0.125;
        float w3 = karisWeight(g3) * 0.125;
        float w4 = karisWeight(g4) * 0.500;
        float wSum = max(1e-5, w0 + w1 + w2 + w3 + w4);

        downsample = (g0 * w0 + g1 * w1 + g2 * w2 + g3 * w3 + g4 * w4) / wSum;
        downsample = softKneeThreshold(downsample, 0.85, 0.65);
    } else {
        downsample = e * 0.125;
        downsample += (a + c + g + i) * 0.03125;
        downsample += (b + d + f + h) * 0.0625;
        downsample += (j + k + l + m) * 0.125;
    }

    FragColor = vec4(max(vec3(0.0), downsample), 1.0);
}
