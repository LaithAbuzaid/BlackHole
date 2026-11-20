#version 330 core
in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uHdrScene;
uniform sampler2D uBloomTex;
uniform float uBloomStrength;
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

void main() {
    vec2 fromCenter = vUv - 0.5;
    float r2 = dot(fromCenter, fromCenter);
    vec2 caOffset = fromCenter * r2 * 0.0032;

    float r = texture(uHdrScene, vUv + caOffset).r;
    float g = texture(uHdrScene, vUv).g;
    float b = texture(uHdrScene, vUv - caOffset).b;
    vec3 hdr = sanitize(vec3(r, g, b));

    vec3 bloom = sanitize(texture(uBloomTex, vUv).rgb);
    vec3 combined = (hdr + bloom * uBloomStrength) * uExposure;

    float vignette = 1.0 - r2 * 0.26;
    FragColor = vec4(toneMapACES(combined * vignette), 1.0);
}
