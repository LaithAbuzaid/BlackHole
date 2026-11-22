#version 330 core
in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uCurrentTex;   // .rgb = linear HDR (ray + TDE), .a = hitDist (world distance or 16384 sky)
uniform sampler2D uHistoryTex;   // .rgb = previous resolved linear HDR, .a = previous hitDist (for disocclusion)
uniform sampler2D uVelocityTex;  // .rg = screen-space UV motion (vUv - prevUv) when uUseVelocityTex == 1 (RG32F NEAREST)
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

// 5-tap Catmull-Rom bicubic history fetch evaluated directly in Karis perceptual space
// (eliminates linear-HDR negative-lobe dark ringing halos around photon rings & bright stars)
vec3 sampleHistoryCatmullRomPerceptual(sampler2D tex, vec2 uv, vec2 texSize, out float prevHitDist) {
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

    vec4 s0 = texture(tex, vec2(texPos12.x, texPos0.y));
    vec4 s1 = texture(tex, vec2(texPos0.x,  texPos12.y));
    vec4 s2 = texture(tex, vec2(texPos12.x, texPos12.y));
    vec4 s3 = texture(tex, vec2(texPos3.x,  texPos12.y));
    vec4 s4 = texture(tex, vec2(texPos12.x, texPos3.y));

    prevHitDist = s2.a;

    vec3 result = vec3(0.0);
    result += toPerceptual(sanitize(s0.rgb)) * (w12.x * w0.y);
    result += toPerceptual(sanitize(s1.rgb)) * (w0.x  * w12.y);
    result += toPerceptual(sanitize(s2.rgb)) * (w12.x * w12.y);
    result += toPerceptual(sanitize(s3.rgb)) * (w3.x  * w12.y);
    result += toPerceptual(sanitize(s4.rgb)) * (w12.x * w3.y);

    float weightSum = (w12.x * w0.y) + (w0.x * w12.y) + (w12.x * w12.y)
                    + (w3.x * w12.y) + (w12.x * w3.y);
    return clamp(result / max(1e-5, weightSum), vec3(0.0), vec3(0.999));
}

// Clip history toward box center along segment into variance AABB (Salvi 2016)
vec3 clipAabb(vec3 aabbMin, vec3 aabbMax, vec3 history) {
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
    float curHitDist = clamp(centerSample.a, 0.0, 16384.0);

    if (uResetHistory == 1) {
        FragColor = vec4(currentHdr, curHitDist);
        return;
    }

    vec2 texel = 1.0 / uResolution;
    vec2 prevUv;
    if (uUseVelocityTex == 1) {
        // 3x3 nearest-foreground velocity dilation prevents orbiting binary shadow silhouette smear
        vec2 bestOffset = vec2(0.0);
        float minHitD = curHitDist;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                vec2 off = vec2(dx, dy) * texel;
                float d = texture(uCurrentTex, vUv + off).a;
                if (d < minHitD) {
                    minHitD = d;
                    bestOffset = off;
                }
            }
        }
        vec2 velUv = texture(uVelocityTex, vUv + bestOffset).rg;
        if (isnan(velUv.x) || isinf(velUv.x) || isnan(velUv.y) || isinf(velUv.y)) {
            velUv = vec2(0.0);
        }
        prevUv = vUv - velUv;
    } else {
        // Exact analytic 3D world-space + infinity camera reprojection using FP16-safe hitDist
        vec2 ndc = vUv * 2.0 - 1.0;
        float aspect = uResolution.x / uResolution.y;

        vec3 rayDir = normalize(uCamForward
                              + ndc.x * aspect * uTanHalfFov * uCamRight
                              + ndc.y * uTanHalfFov * uCamUp);

        vec3 prevRel = (curHitDist > 8000.0)
                ? rayDir
                : ((uCamPos + rayDir * max(0.25, curHitDist)) - uPrevCamPos);

        float prevZ = dot(prevRel, uPrevCamForward);
        if (prevZ <= 1e-4) {
            FragColor = vec4(currentHdr, curHitDist);
            return;
        }

        float prevNdcX = dot(prevRel, uPrevCamRight) / (prevZ * aspect * uPrevTanHalfFov);
        float prevNdcY = dot(prevRel, uPrevCamUp)    / (prevZ * uPrevTanHalfFov);
        prevUv = vec2(prevNdcX, prevNdcY) * 0.5 + 0.5;
    }

    if (prevUv.x < 0.0 || prevUv.x > 1.0 || prevUv.y < 0.0 || prevUv.y > 1.0) {
        FragColor = vec4(currentHdr, curHitDist);
        return;
    }

    // 3x3 neighborhood statistical moments in Karis-weighted YCoCg space
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

    // Ensure current sample currYcc is ALWAYS inside the variance box (prevents ~80% energy loss
    // on sharp 1-pixel stars and thin relativistic photon rings!)
    float gamma = mix(1.30, 0.95, motionT);
    vec3 boxMin = min(mean - gamma * sigma, currYcc);
    vec3 boxMax = max(mean + gamma * sigma, currYcc);

    float prevHitDist;
    vec3 historyPerc = sampleHistoryCatmullRomPerceptual(uHistoryTex, prevUv, uResolution, prevHitDist);
    vec3 histYcc = rgbToYCoCg(historyPerc);
    vec3 clippedHistYcc = clipAabb(boxMin, boxMax, histYcc);

    // Hit-distance disocclusion detection: rapidly reject stale history behind moving shadows/orbits
    float depthDiff = abs(curHitDist - prevHitDist);
    float depthThresh = max(2.5, 0.30 * min(curHitDist, prevHitDist));
    float disocc = (pixelMotion > 0.35) ? smoothstep(depthThresh, depthThresh * 2.5, depthDiff) : 0.0;

    float alphaMotion = mix(uMinAlpha, max(0.26, uMinAlpha + 0.14), motionT);
    float alpha = clamp(max(alphaMotion, disocc * 0.72), uMinAlpha, 0.95);
    vec3 resolvedYcc = mix(clippedHistYcc, currYcc, alpha);
    vec3 resolvedHdr = sanitize(fromPerceptual(yCoCgToRgb(resolvedYcc)));

    FragColor = vec4(resolvedHdr, curHitDist);
}
