#version 330 core
in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uCurrentTex;   // .rgb = linear HDR (ray + TDE), .a = hitDist (world distance or 1e5 sky)
uniform sampler2D uHistoryTex;   // previous resolved linear HDR
uniform sampler2D uVelocityTex;  // .rg = screen-space UV motion (vUv - prevUv) when uUseVelocityTex == 1
uniform int uUseVelocityTex;
uniform vec2 uResolution;
uniform vec3 uCamPos;
uniform vec3 uCamForward;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform float uTanHalfFov;
uniform vec3 uPrevCamPos;
uniform vec3 uPrevCamForward;
uniform vec3 uPrevCamRight;
uniform vec3 uPrevCamUp;
uniform float uPrevTanHalfFov;
uniform int uResetHistory;
uniform float uMinAlpha;         // 0.0625 default; 1/(k+1) during static showcase convergence

vec3 sanitize(vec3 c) {
    bvec3 bad = bvec3(isnan(c.x) || isinf(c.x),
                      isnan(c.y) || isinf(c.y),
                      isnan(c.z) || isinf(c.z));
    return clamp(mix(c, vec3(0.0), bad), vec3(0.0), vec3(48.0));
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Reversible Karis perceptual compression prevents bright 1px stars & photon rings from flickering
vec3 toPerceptual(vec3 c) {
    return c / (1.0 + luma(c));
}

vec3 fromPerceptual(vec3 c) {
    return c / max(1e-4, 1.0 - luma(c));
}

vec3 rgbToYCoCg(vec3 c) {
    return vec3(
         0.25 * c.r + 0.50 * c.g + 0.25 * c.b,
         0.50 * c.r              - 0.50 * c.b,
        -0.25 * c.r + 0.50 * c.g - 0.25 * c.b
    );
}

vec3 yCoCgToRgb(vec3 ycc) {
    float tmp = ycc.x - ycc.z;
    return max(vec3(0.0), vec3(tmp + ycc.y, ycc.x + ycc.z, tmp - ycc.y));
}

// 5-tap Filmic SMAA / Unreal Catmull-Rom bicubic history fetch (eliminates bilinear blur during orbit)
vec3 sampleHistoryCatmullRom(sampler2D tex, vec2 uv, vec2 texSize) {
    vec2 samplePos = uv * texSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;

    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);

    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / max(vec2(1e-5), w12);

    vec2 invSize = 1.0 / texSize;
    vec2 texPos0  = (texPos1 - 1.0) * invSize;
    vec2 texPos3  = (texPos1 + 2.0) * invSize;
    vec2 texPos12 = (texPos1 + offset12) * invSize;

    vec3 result = vec3(0.0);
    result += sanitize(texture(tex, vec2(texPos12.x, texPos0.y)).rgb)  * (w12.x * w0.y);
    result += sanitize(texture(tex, vec2(texPos0.x,  texPos12.y)).rgb) * (w0.x  * w12.y);
    result += sanitize(texture(tex, vec2(texPos12.x, texPos12.y)).rgb) * (w12.x * w12.y);
    result += sanitize(texture(tex, vec2(texPos3.x,  texPos12.y)).rgb) * (w3.x  * w12.y);
    result += sanitize(texture(tex, vec2(texPos12.x, texPos3.y)).rgb)  * (w12.x * w3.y);

    float weightSum = (w12.x * w0.y) + (w0.x * w12.y) + (w12.x * w12.y)
                    + (w3.x * w12.y) + (w12.x * w3.y);
    return max(vec3(0.0), result / max(1e-5, weightSum));
}

// Clip history toward current 3x3 mean along segment into variance AABB (Salvi 2016)
vec3 clipAabb(vec3 aabbMin, vec3 aabbMax, vec3 center, vec3 history) {
    vec3 pClip = 0.5 * (aabbMax + aabbMin);
    vec3 eClip = 0.5 * (aabbMax - aabbMin) + 1e-4;
    vec3 vClip = history - pClip;
    vec3 vUnit = vClip / eClip;
    vec3 aUnit = abs(vUnit);
    float maUnit = max(aUnit.x, max(aUnit.y, aUnit.z));
    if (maUnit > 1.0) {
        return pClip + vClip / maUnit;
    }
    return history;
}

void main() {
    vec4 centerSample = texture(uCurrentTex, vUv);
    vec3 currentHdr = sanitize(centerSample.rgb);

    if (uResetHistory == 1) {
        FragColor = vec4(currentHdr, 1.0);
        return;
    }

    vec2 prevUv;
    if (uUseVelocityTex == 1) {
        // Explicit MRT object + camera motion vectors (eliminates ghosting on rapidly orbiting binary shadows)
        vec2 velUv = texture(uVelocityTex, vUv).rg;
        if (isnan(velUv.x) || isinf(velUv.x) || isnan(velUv.y) || isinf(velUv.y)) {
            velUv = vec2(0.0);
        }
        prevUv = vUv - velUv;
    } else {
        // Exact analytic 3D world-space + infinity camera reprojection using hitDist in alpha channel
        float hitDist = centerSample.a;
        vec2 ndc = vUv * 2.0 - 1.0;
        float aspect = uResolution.x / uResolution.y;

        vec3 rayDir = normalize(uCamForward
                              + ndc.x * aspect * uTanHalfFov * uCamRight
                              + ndc.y * uTanHalfFov * uCamUp);

        vec3 prevRel = (hitDist > 5000.0)
                ? rayDir
                : ((uCamPos + rayDir * max(0.25, hitDist)) - uPrevCamPos);

        float prevZ = dot(prevRel, uPrevCamForward);
        if (prevZ <= 1e-4) {
            FragColor = vec4(currentHdr, 1.0);
            return;
        }

        float prevNdcX = dot(prevRel, uPrevCamRight) / (prevZ * aspect * uPrevTanHalfFov);
        float prevNdcY = dot(prevRel, uPrevCamUp)    / (prevZ * uPrevTanHalfFov);
        prevUv = vec2(prevNdcX, prevNdcY) * 0.5 + 0.5;
    }

    if (prevUv.x < 0.0 || prevUv.x > 1.0 || prevUv.y < 0.0 || prevUv.y > 1.0) {
        FragColor = vec4(currentHdr, 1.0);
        return;
    }

    // 3x3 neighborhood statistical moments in Karis-weighted YCoCg space
    vec2 texel = 1.0 / uResolution;
    vec3 m1 = vec3(0.0);
    vec3 m2 = vec3(0.0);
    vec3 currYcc = vec3(0.0);

    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            vec3 s = sanitize(texture(uCurrentTex, vUv + vec2(dx, dy) * texel).rgb);
            vec3 ycc = rgbToYCoCg(toPerceptual(s));
            m1 += ycc;
            m2 += ycc * ycc;
            if (dx == 0 && dy == 0) {
                currYcc = ycc;
            }
        }
    }

    vec3 mean = m1 * (1.0 / 9.0);
    vec3 sigma = sqrt(max(vec3(0.0), m2 * (1.0 / 9.0) - mean * mean));

    float pixelMotion = length((vUv - prevUv) * uResolution);
    float motionT = clamp(pixelMotion * 0.22, 0.0, 1.0);

    // Slightly wider box when static for silky 16x sub-pixel convergence; tighter when moving for zero ghosting
    float gamma = mix(1.30, 0.95, motionT);
    vec3 boxMin = mean - gamma * sigma;
    vec3 boxMax = mean + gamma * sigma;

    vec3 historyHdr = sampleHistoryCatmullRom(uHistoryTex, prevUv, uResolution);
    vec3 histYcc = rgbToYCoCg(toPerceptual(historyHdr));
    vec3 clippedHistYcc = clipAabb(boxMin, boxMax, currYcc, histYcc);

    // Blend factor: exact progressive convergence when static/showcase, responsive when orbiting
    float alpha = mix(uMinAlpha, 0.20, motionT);
    vec3 resolvedYcc = mix(clippedHistYcc, currYcc, alpha);
    vec3 resolvedHdr = sanitize(fromPerceptual(yCoCgToRgb(resolvedYcc)));

    FragColor = vec4(resolvedHdr, 1.0);
}
