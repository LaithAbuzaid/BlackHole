#version 430 core
in vec3 vColor;
in float vAlpha;

layout(location = 0) out vec4 FragColor;

void main() {
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    float r2 = dot(p, p);
    if (r2 > 1.0) {
        discard;
    }

    // Soft volumetric Gaussian plasma kernel feeding cleanly into additive HDR bloom
    float gauss = exp(-r2 * 4.5);
    FragColor = vec4(vColor, vAlpha * gauss);
}
