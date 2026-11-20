#version 330 core
in vec2 vPos;
in vec2 vUv;
in vec4 vColor;
in vec4 vRect;
in vec2 vParams;

uniform sampler2D uFontTex;
uniform vec2 uFontUnitRange;
out vec4 FragColor;

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    float mode = vParams.y;
    if (mode > 1.5) {
        vec4 mtsdf = texture(uFontTex, vUv);
        float sd = median(mtsdf.r, mtsdf.g, mtsdf.b);
        vec2 screenTexSize = vec2(1.0) / max(fwidth(vUv), vec2(1e-6));
        float screenPxRange = max(0.5 * dot(uFontUnitRange, screenTexSize), 1.0);

        // Razor-sharp MSDF contour reconstruction with slight optical weight compensation for small HUD text
        float glyphAlpha = clamp(screenPxRange * (sd - 0.485) + 0.5, 0.0, 1.0);

        // Subtle dark legibility micro-halo using true single-channel SDF (.a) over bright HDR plasma
        float haloAlpha = clamp(screenPxRange * (mtsdf.a - 0.34) + 0.5, 0.0, 1.0) * 0.36;

        float outAlpha = glyphAlpha + haloAlpha * (1.0 - glyphAlpha);
        if (outAlpha < 0.008) discard;
        vec3 haloColor = vec3(0.01, 0.015, 0.025);
        vec3 outColor = (vColor.rgb * glyphAlpha + haloColor * haloAlpha * (1.0 - glyphAlpha)) / outAlpha;
        FragColor = vec4(outColor, vColor.a * outAlpha);
    } else {
        float d = sdRoundedBox(vPos - vRect.xy, vRect.zw, vParams.x);
        if (mode < 0.5) {
            float alpha = 1.0 - smoothstep(-0.75, 0.75, d);
            if (alpha < 0.004) discard;
            FragColor = vec4(vColor.rgb, vColor.a * alpha);
        } else {
            float outer = 1.0 - smoothstep(-0.75, 0.75, d);
            float inner = smoothstep(-2.0, -0.6, d);
            float alpha = outer * inner;
            if (alpha < 0.004) discard;
            FragColor = vec4(vColor.rgb, vColor.a * alpha);
        }
    }
}
