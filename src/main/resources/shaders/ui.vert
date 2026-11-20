#version 330 core
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec4 aRect;
layout(location = 4) in vec2 aParams;

uniform vec2 uResolution;

out vec2 vPos;
out vec2 vUv;
out vec4 vColor;
out vec4 vRect;
out vec2 vParams;

void main() {
    vPos = aPos;
    vUv = aUv;
    vColor = aColor;
    vRect = aRect;
    vParams = aParams;

    vec2 ndc = vec2((aPos.x / uResolution.x) * 2.0 - 1.0,
                    1.0 - (aPos.y / uResolution.y) * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
}
