#version 430 core

struct Particle {
    vec4 posMass;
    vec4 velTemp;
};

layout(std430, binding = 0) readonly buffer ParticleBuffer {
    Particle particles[];
};

uniform vec2 uResolution;
uniform vec3 uCamPos;
uniform vec3 uCamForward;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform sampler2D uBlackbodyLut;
uniform float uTanHalfFov;
uniform float uSpinA;
uniform float uHorizonR;
uniform int uDoppler;
uniform vec2 uJitter;
uniform vec4 uSlotStyle[2]; // .rgb = archetype tint, .a = ribbon width scale

out vec2 vLocalUv;
out vec3 vWorldPos;
out vec3 vColor;
out float vAlpha;
out float vTempNorm;

vec3 sampleBlackbody(float tempKelvin) {
    float norm = clamp((tempKelvin - 900.0) * (1.0 / 37100.0), 0.0, 1.0);
    float u = (0.5 + sqrt(norm) * 511.0) * (1.0 / 512.0);
    vec4 bb = texture(uBlackbodyLut, vec2(u, 0.5));
    return bb.rgb * bb.a;
}

void discardQuad() {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    vLocalUv = vec2(0.0);
    vWorldPos = vec3(0.0);
    vColor = vec3(0.0);
    vAlpha = 0.0;
    vTempNorm = 0.0;
}

void main() {
    Particle p = particles[gl_InstanceID];
    if (p.posMass.w <= 0.0) {
        discardQuad();
        return;
    }

    vec3 pos = p.posMass.xyz;
    vec3 vel = p.velTemp.xyz;
    vec3 rel = pos - uCamPos;
    float zCamRaw = dot(rel, uCamForward);
    if (zCamRaw <= 0.15) {
        discardQuad();
        return;
    }

    float distToPart = length(rel);
    vec3 rayDir = rel / max(1e-5, distToPart);
    float camToBh = length(uCamPos);
    float projBh = dot(-uCamPos, rayDir);

    // Analytic Kerr-calibrated gravitational lensing + feathered shadow occlusion
    // Bends debris streams passing behind the black hole around the Einstein ring.
    vec3 apparentDir = rayDir;
    float shadowFade = 1.0;
    float lensStretch = 1.0;

    if (camToBh > uHorizonR * 1.15 && projBh > 0.0 && distToPart > projBh) {
        vec3 closestPt = uCamPos + rayDir * projBh;
        float impactB = length(closestPt);
        float dLs = distToPart - projBh;

        // Thin-lens Einstein radius calibrated to M=1 spacetime geometry: R_E^2 = 4 M D_L D_LS / D_S
        float rEinsteinSq = 4.0 * (projBh * dLs) / max(1.0, distToPart);
        float bApp = 0.5 * (impactB + sqrt(impactB * impactB + 4.0 * rEinsteinSq));

        // Critical impact parameter b_crit (~5.196M Schwarzschild) shifted asymmetrically by Kerr spin
        float progradeSide = dot(closestPt, vec3(uCamRight.x, 0.0, uCamRight.z));
        float bCrit = 5.18 - 0.50 * uSpinA + clamp(progradeSide * 0.22 * uSpinA, -1.70, 1.50);
        float gravWidening = inversesqrt(max(0.18, 1.0 - (2.0 / max(2.45, camToBh))));
        float occRadius = bCrit * min(1.85, gravWidening);

        // Smoothly deflect apparent ray direction radially outward around the lens plane
        if (impactB > 0.20) {
            vec3 lensRadial = closestPt / impactB;
            float deltaB = (bApp - impactB) * smoothstep(0.25, 1.40, impactB);
            vec3 deflectedPt = closestPt + lensRadial * deltaB;
            apparentDir = normalize(deflectedPt - uCamPos);
            lensStretch = clamp(bApp / max(1.5, impactB), 1.0, 1.75);
        }

        shadowFade = smoothstep(occRadius * 0.85, occRadius * 1.10, bApp)
                   * smoothstep(0.40, 1.45, impactB);
        if (shadowFade <= 0.005) {
            discardQuad();
            return;
        }
    }

    // Exact perspective projection matching kerr_blackhole.frag camera basis
    float xCam = dot(apparentDir, uCamRight) * distToPart;
    float yCam = dot(apparentDir, uCamUp) * distToPart;
    float zCam = dot(apparentDir, uCamForward) * distToPart;
    if (zCam <= 0.15) {
        discardQuad();
        return;
    }

    float aspect = uResolution.x / uResolution.y;
    vec2 centerNdc = vec2(
        xCam / (zCam * aspect * uTanHalfFov) - (uJitter.x * 2.0 / uResolution.x),
        yCam / (zCam * uTanHalfFov) - (uJitter.y * 2.0 / uResolution.y)
    );

    // Physical relativistic Doppler beaming + Schwarzschild/Kerr gravitational redshift sqrt(1 - 2M/r)
    float r = max(uHorizonR * 1.02, length(pos));
    float beta = min(0.82, length(vel));
    float gFactor = 1.0;
    if (uDoppler == 1) {
        float cosAng = (beta > 1e-4) ? dot(vel / beta, -apparentDir) : 0.0;
        float gamma = inversesqrt(max(0.12, 1.0 - beta * beta));
        float gravShift = sqrt(max(0.12, 1.0 - min(0.88, 2.0 / max(2.05, r))));
        gFactor = clamp(gravShift / (gamma * (1.0 - beta * cosAng)), 0.20, 2.60);
    }

    uint slot = (uint(gl_InstanceID) >> 16u) & 1u;
    vec4 slotStyle = uSlotStyle[slot];

    float obsTemp = p.velTemp.w * gFactor;
    float g3 = gFactor * gFactor * gFactor;
    float boost = g3 / (1.0 + 0.38 * g3);

    float tempNorm = clamp((p.velTemp.w - 3000.0) / 18000.0, 0.0, 1.5);
    vColor = sampleBlackbody(obsTemp) * slotStyle.rgb * boost * (0.50 + 1.28 * tempNorm);
    vTempNorm = tempNorm;
    vWorldPos = pos;

    // Screen-space velocity tangent for smooth anisotropic tidal-ribbon elongation
    vec3 velCam = vec3(dot(vel, uCamRight), dot(vel, uCamUp), dot(vel, uCamForward));
    vec2 screenVel = vec2(velCam.x * zCam - xCam * velCam.z,
                          velCam.y * zCam - yCam * velCam.z);
    float svLen = length(screenVel);
    vec2 dir2D = (svLen > 1e-5) ? (screenVel / svLen) : vec2(1.0, 0.0);
    vec2 perp2D = vec2(-dir2D.y, dir2D.x);

    // Cohesive sphere before Roche crossing; stretches into smooth silky plasma ribbons inside r < 14M
    float tidalStrain = clamp(240.0 / (r * r * r), 0.0, 1.0);
    float screenSpeedFactor = clamp(svLen / max(0.8, zCam * 0.12), 0.38, 1.0);
    float stretch = (1.0 + (2.10 + 2.85 * beta) * smoothstep(0.05, 0.65, tidalStrain) * screenSpeedFactor) * lensStretch;
    stretch = clamp(stretch, 1.0, 5.2);

    // Generous volumetric overlap eliminates sandy point-sprite grain and weaves a fluid stream
    float pxScale = (uResolution.y * 0.5) / max(0.25, zCam * uTanHalfFov);
    float halfW = clamp(pxScale * (0.155 + 0.075 * tempNorm) * slotStyle.a, 4.6, 20.5);
    float halfL = clamp(halfW * stretch, halfW, 50.0);

    // Expand instanced triangle-strip quad aligned to screen-space plasma flow
    vec2 corner = vec2(float((gl_VertexID & 1) * 2 - 1),
                       float(((gl_VertexID >> 1) & 1) * 2 - 1));
    vec2 offsetPx = dir2D * (corner.x * halfL) + perp2D * (corner.y * halfW);
    vec2 finalNdc = centerNdc + (offsetPx * 2.0) / uResolution;

    gl_Position = vec4(finalNdc, 0.0, 1.0);
    vLocalUv = corner;

    // Conserve integrated volumetric radiance as ribbons overlap and elongate
    float areaNorm = 1.0 / (0.68 + 0.24 * stretch);
    vAlpha = clamp((0.042 + 0.062 * tempNorm) * areaNorm, 0.020, 0.125) * shadowFade;
}
