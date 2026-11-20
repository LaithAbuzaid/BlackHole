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

out vec3 vColor;
out float vAlpha;

vec3 sampleBlackbody(float tempKelvin) {
    float norm = clamp((tempKelvin - 900.0) * (1.0 / 37100.0), 0.0, 1.0);
    return texture(uBlackbodyLut, vec2(sqrt(norm), 0.5)).rgb;
}

void main() {
    Particle p = particles[gl_VertexID];
    if (p.posMass.w <= 0.0) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        gl_PointSize = 0.0;
        vColor = vec3(0.0);
        vAlpha = 0.0;
        return;
    }

    vec3 pos = p.posMass.xyz;
    vec3 rel = pos - uCamPos;
    float zCam = dot(rel, uCamForward);
    if (zCam <= 0.15) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        gl_PointSize = 0.0;
        vColor = vec3(0.0);
        vAlpha = 0.0;
        return;
    }

    // Analytical black hole shadow-cone occlusion check (only when camera is outside horizon):
    // Apparent critical shadow widens by 1 / sqrt(1 - r_s / r_cam) as camera approaches photon sphere.
    float distToPart = length(rel);
    vec3 rayDir = rel / max(1e-5, distToPart);
    float camToBh = length(uCamPos);
    float projBh = dot(-uCamPos, rayDir);

    if (camToBh > uHorizonR * 1.15 && projBh > 0.0 && distToPart > projBh) {
        vec3 closestPt = uCamPos + rayDir * projBh;
        float impactB = length(closestPt);
        // Critical impact parameter b_crit (~5.196M Schwarzschild) shifted asymmetrically by Kerr spin
        float progradeSide = dot(closestPt, vec3(uCamRight.x, 0.0, uCamRight.z));
        float bCrit = 5.18 - 0.50 * uSpinA + clamp(progradeSide * 0.22 * uSpinA, -1.70, 1.50);
        float gravWidening = inversesqrt(max(0.18, 1.0 - (2.0 / max(2.45, camToBh))));
        if (impactB < bCrit * min(1.85, gravWidening) * 0.96) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            gl_PointSize = 0.0;
            vColor = vec3(0.0);
            vAlpha = 0.0;
            return;
        }
    }

    // Exact perspective projection matching kerr_blackhole.frag camera basis
    float xCam = dot(rel, uCamRight);
    float yCam = dot(rel, uCamUp);
    float aspect = uResolution.x / uResolution.y;

    float ndcX = xCam / (zCam * aspect * uTanHalfFov);
    float ndcY = yCam / (zCam * uTanHalfFov);

    gl_Position = vec4(ndcX, ndcY, 0.0, 1.0);

    // Physical relativistic Doppler beaming + gravitational redshift
    float r = max(uHorizonR * 1.02, length(pos));
    float gFactor = 1.0;
    if (uDoppler == 1) {
        vec3 vel = p.velTemp.xyz;
        float beta = min(0.82, length(vel));
        float cosAng = (beta > 1e-4) ? dot(vel / beta, -rayDir) : 0.0;
        float gamma = inversesqrt(max(0.12, 1.0 - beta * beta));
        float gravShift = sqrt(max(0.18, 1.0 - uHorizonR / r));
        gFactor = clamp(gravShift / (gamma * (1.0 - beta * cosAng)), 0.20, 2.80);
    }

    float obsTemp = p.velTemp.w * gFactor;
    float g3 = gFactor * gFactor * gFactor;
    float boost = g3 / (1.0 + 0.25 * g3);

    float tempNorm = clamp((p.velTemp.w - 3200.0) / 18000.0, 0.0, 1.8);
    vColor = sampleBlackbody(obsTemp) * boost * (0.95 + 3.20 * tempNorm);

    float pxScale = (uResolution.y * 0.5) / max(0.25, zCam * uTanHalfFov);
    gl_PointSize = clamp(pxScale * (0.068 + 0.048 * tempNorm), 2.2, 15.0);
    vAlpha = clamp(0.18 + 0.22 * tempNorm, 0.10, 0.48);
}
