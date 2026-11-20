#version 330 core
in vec2 vPos;
in vec2 vUv;
in vec4 vColor;
in vec4 vRect;
in vec2 vParams;

uniform sampler2D uFontTex;
out vec4 FragColor;

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

void main() {
    float mode = vParams.y;
    if (mode > 1.5) {
        float glyphAlpha = texture(uFontTex, vUv).r;
        if (glyphAlpha < 0.1) discard;
        FragColor = vec4(vColor.rgb, vColor.a * glyphAlpha);
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
