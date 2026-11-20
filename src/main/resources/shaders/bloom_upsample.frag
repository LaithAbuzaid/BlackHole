#version 330 core
in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uSource;
uniform float uFilterRadius;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(uSource, 0));
    float x = texel.x * uFilterRadius;
    float y = texel.y * uFilterRadius;

    vec3 a = texture(uSource, vec2(vUv.x - x, vUv.y + y)).rgb;
    vec3 b = texture(uSource, vec2(vUv.x,     vUv.y + y)).rgb;
    vec3 c = texture(uSource, vec2(vUv.x + x, vUv.y + y)).rgb;

    vec3 d = texture(uSource, vec2(vUv.x - x, vUv.y)).rgb;
    vec3 e = texture(uSource, vec2(vUv.x,     vUv.y)).rgb;
    vec3 f = texture(uSource, vec2(vUv.x + x, vUv.y)).rgb;

    vec3 g = texture(uSource, vec2(vUv.x - x, vUv.y - y)).rgb;
    vec3 h = texture(uSource, vec2(vUv.x,     vUv.y - y)).rgb;
    vec3 i = texture(uSource, vec2(vUv.x + x, vUv.y - y)).rgb;

    vec3 upsample = e * 4.0;
    upsample += (b + d + f + h) * 2.0;
    upsample += (a + c + g + i);
    upsample *= (1.0 / 16.0);

    FragColor = vec4(upsample, 1.0);
}
