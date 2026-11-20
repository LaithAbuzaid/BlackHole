#version 330 core
out vec4 FragColor;

uniform float uSliceZ;
uniform float uSize;

vec3 modWrap(vec3 p, float period) {
    return mod(p, period);
}

float hash31(vec3 p) {
    p = fract(p * vec3(443.8975, 397.2973, 491.1871));
    p += dot(p, p.yxz + 19.19);
    return fract((p.x + p.y) * p.z);
}

float periodicNoise3D(vec3 p, float period) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    vec3 i0 = modWrap(i, period);
    vec3 i1 = modWrap(i + 1.0, period);

    float n000 = hash31(vec3(i0.x, i0.y, i0.z));
    float n100 = hash31(vec3(i1.x, i0.y, i0.z));
    float n010 = hash31(vec3(i0.x, i1.y, i0.z));
    float n110 = hash31(vec3(i1.x, i1.y, i0.z));
    float n001 = hash31(vec3(i0.x, i0.y, i1.z));
    float n101 = hash31(vec3(i1.x, i0.y, i1.z));
    float n011 = hash31(vec3(i0.x, i1.y, i1.z));
    float n111 = hash31(vec3(i1.x, i1.y, i1.z));

    float nx00 = mix(n000, n100, f.x);
    float nx10 = mix(n010, n110, f.x);
    float nx01 = mix(n001, n101, f.x);
    float nx11 = mix(n011, n111, f.x);

    return mix(mix(nx00, nx10, f.y), mix(nx01, nx11, f.y), f.z);
}

void main() {
    vec3 uvw = vec3(gl_FragCoord.xy / uSize, uSliceZ);
    float sum = 0.0;
    float amp = 0.52;
    float period = 8.0;
    for (int o = 0; o < 4; o++) {
        sum += amp * periodicNoise3D(uvw * period, period);
        period *= 2.0;
        amp *= 0.49;
    }
    FragColor = vec4(sum, 0.0, 0.0, 1.0);
}
