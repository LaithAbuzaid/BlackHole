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

    // Soft volumetric Gaussian plasma kernel that blends smoothly into continuous tidal streams
    float gauss = exp(-r2 * 2.5) * (1.0 - r2);
    FragColor = vec4(vColor, vAlpha * gauss);
}
