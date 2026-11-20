#version 430 core
in vec2 vLocalUv;
in vec3 vWorldPos;
in vec3 vColor;
in float vAlpha;
in float vTempNorm;

uniform sampler3D uNoiseVol;
uniform float uTime;

layout(location = 0) out vec4 FragColor;

void main() {
    float r2 = dot(vLocalUv, vLocalUv);
    if (r2 > 1.0) {
        discard;
    }

    // Smooth world-continuous 3D turbulence carves large-scale volumetric wisps without point grain
    vec3 noiseCoord = vWorldPos * 0.075 + vec3(vLocalUv.x * 0.018, vLocalUv.y * 0.025, uTime * 0.025);
    float turb = texture(uNoiseVol, noiseCoord).r;
    float filament = 0.72 + 0.56 * turb;

    // Silky volumetric plasma kernel (broad feathered falloff avoids per-particle pinpricks)
    float edgeFade = 1.0 - r2;
    float softCloud = edgeFade * edgeFade * exp(-r2 * 1.35);
    float innerGlow = edgeFade * exp(-r2 * 3.10);
    float density = (0.70 * softCloud + 0.30 * innerGlow) * filament;

    // Rich molten orange/crimson outer corona transitioning into incandescent gold-white core
    vec3 outerCorona = vColor * vec3(1.12, 0.78, 0.52);
    vec3 hotCore = vColor * vec3(1.15, 1.08, 0.98);
    vec3 finalCol = mix(outerCorona, hotCore, innerGlow * clamp(0.35 + 0.45 * vTempNorm, 0.0, 0.85));

    FragColor = vec4(finalCol, vAlpha * density);
}
