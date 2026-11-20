#version 330 core
in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uHdrScene;
uniform sampler2D uBloomTex;
uniform float uBloomStrength;
uniform float uExposure;

vec3 toneMapACES(vec3 x) {
    x = max(vec3(0.0), x);
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
    vec3 hdr = vec3(r, g, b);

    vec3 bloom = texture(uBloomTex, vUv).rgb;
    vec3 combined = (hdr + bloom * uBloomStrength) * uExposure;

    float vignette = 1.0 - r2 * 0.26;
    FragColor = vec4(toneMapACES(combined * vignette), 1.0);
}
