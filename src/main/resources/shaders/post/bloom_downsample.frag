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

vec3 karisAverage(vec3 c) {
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return c / (1.0 + luma);
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
        vec3 g0 = karisAverage((a + b + d + e) * 0.25);
        vec3 g1 = karisAverage((b + c + e + f) * 0.25);
        vec3 g2 = karisAverage((d + e + g + h) * 0.25);
        vec3 g3 = karisAverage((e + f + h + i) * 0.25);
        vec3 g4 = karisAverage((j + k + l + m) * 0.25);
        downsample = g0 * 0.125 + g1 * 0.125 + g2 * 0.125 + g3 * 0.125 + g4 * 0.5;
        float lum = dot(downsample, vec3(0.2126, 0.7152, 0.0722));
        downsample *= smoothstep(0.60, 1.55, lum);
    } else {
        downsample = e * 0.125;
        downsample += (a + c + g + i) * 0.03125;
        downsample += (b + d + f + h) * 0.0625;
        downsample += (j + k + l + m) * 0.125;
    }

    FragColor = vec4(max(vec3(0.0), downsample), 1.0);
}
