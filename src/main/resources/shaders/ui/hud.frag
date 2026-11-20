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
    if (mode > 1.5 && mode < 2.5) {
        // Mode 2: 3-channel MTSDF JetBrains Mono glyph + legibility halo
        vec4 mtsdf = texture(uFontTex, vUv);
        float sd = median(mtsdf.r, mtsdf.g, mtsdf.b);
        vec2 screenTexSize = vec2(1.0) / max(fwidth(vUv), vec2(1e-6));
        float screenPxRange = max(0.5 * dot(uFontUnitRange, screenTexSize), 1.0);

        float glyphAlpha = clamp(screenPxRange * (sd - 0.485) + 0.5, 0.0, 1.0);
        float haloAlpha = clamp(screenPxRange * (mtsdf.a - 0.34) + 0.5, 0.0, 1.0) * 0.36;

        float outAlpha = glyphAlpha + haloAlpha * (1.0 - glyphAlpha);
        if (outAlpha < 0.008) discard;
        vec3 haloColor = vec3(0.01, 0.015, 0.025);
        vec3 outColor = (vColor.rgb * glyphAlpha + haloColor * haloAlpha * (1.0 - glyphAlpha)) / outAlpha;
        FragColor = vec4(outColor, vColor.a * outAlpha);
    } else {
        float d = sdRoundedBox(vPos - vRect.xy, vRect.zw, vParams.x);
        if (mode < 0.5) {
            // Mode 0: Solid SDF rounded box (tracks, bars, accents, indicator dots)
            float alpha = 1.0 - smoothstep(-0.75, 0.75, d);
            if (alpha < 0.004) discard;
            FragColor = vec4(vColor.rgb, vColor.a * alpha);
        } else if (mode < 1.5) {
            // Mode 1: Crisp 1px SDF rounded border
            float outer = 1.0 - smoothstep(-0.75, 0.75, d);
            float inner = smoothstep(-2.0, -0.6, d);
            float alpha = outer * inner;
            if (alpha < 0.004) discard;
            FragColor = vec4(vColor.rgb, vColor.a * alpha);
        } else {
            // Mode 3: Frosted-glass vertical gradient fill + subtle top inner specular rim
            float alpha = 1.0 - smoothstep(-0.75, 0.75, d);
            if (alpha < 0.004) discard;
            float tY = clamp(vUv.y, 0.0, 1.0);
            vec3 topCol = vColor.rgb * 1.22 + vec3(0.022, 0.030, 0.044);
            vec3 botCol = vColor.rgb * 0.80;
            vec3 gradCol = mix(topCol, botCol, smoothstep(0.0, 1.0, tY));

            // Subtle 1.5px top frost sheen inside rounded top edge
            float localTop = vPos.y - (vRect.y - vRect.w);
            float topRim = (1.0 - smoothstep(0.4, 2.2, abs(localTop - 1.0))) * smoothstep(-3.0, -0.5, d);
            gradCol += vec3(0.14, 0.19, 0.28) * topRim * 0.45;
            FragColor = vec4(gradCol, vColor.a * alpha);
        }
    }
}
