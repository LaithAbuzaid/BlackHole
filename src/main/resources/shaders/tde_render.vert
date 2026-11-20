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
uniform float uTanHalfFov;
uniform float uSpinA;
uniform float uHorizonR;
uniform int uDoppler;

out vec3 vColor;
out float vAlpha;

vec3 blackbody(float tempKelvin) {
    float t = clamp(tempKelvin, 1000.0, 38000.0) / 100.0;
    vec3 col;
    if (t <= 66.0) {
        col.r = 1.0;
        col.g = clamp((99.4708025861 * log(max(1.0, t)) - 161.1195681661) / 255.0, 0.0, 1.0);
    } else {
        col.r = clamp((329.698727446 * pow(t - 60.0, -0.1332047592)) / 255.0, 0.0, 1.0);
        col.g = clamp((288.1221695283 * pow(t - 60.0, -0.0755148492)) / 255.0, 0.0, 1.0);
    }
    if (t >= 66.0) {
        col.b = 1.0;
    } else if (t <= 19.0) {
        col.b = 0.0;
    } else {
        col.b = clamp((138.5177312231 * log(t - 10.0) - 305.0447927307) / 255.0, 0.0, 1.0);
    }
    return pow(col, vec3(2.2));
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

    // Analytical black hole shadow-cone occlusion check:
    // Discard particles passing behind the event horizon shadow (impact parameter < b_crit)
    float distToPart = length(rel);
    vec3 rayDir = rel / distToPart;
    float camToBh = length(uCamPos);
    float projBh = dot(-uCamPos, rayDir);

    if (projBh > 0.0 && distToPart > projBh) {
        vec3 closestPt = uCamPos + rayDir * projBh;
        float impactB = length(closestPt);
        // Apparent critical shadow radius b_crit (~5.196 M Schwarzschild, shifted slightly by Kerr spin)
        float progradeSide = dot(closestPt, vec3(uCamRight.x, 0.0, uCamRight.z));
        float bCrit = 5.15 - 0.55 * uSpinA + clamp(progradeSide * 0.22 * uSpinA, -1.65, 1.45);
        // Apply gravitational apparent widening when camera is close to BH
        float gravFactor = sqrt(max(0.25, 1.0 - (2.0 * uHorizonR * 0.5) / max(2.2, camToBh)));
        if (impactB < bCrit * gravFactor * 0.96) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            gl_PointSize = 0.0;
            vColor = vec3(0.0);
            vAlpha = 0.0;
            return;
        }
    }

    // Exact perspective projection matching blackhole.frag camera basis
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

    // Hotter tidal-sheared plasma blazes brightly into the 5-stage Karis HDR bloom pyramid
    float tempNorm = clamp((p.velTemp.w - 3200.0) / 18000.0, 0.0, 1.8);
    vColor = blackbody(obsTemp) * boost * (0.95 + 3.20 * tempNorm);

    // Screen-space sprite size proportional to inverse camera distance
    float pxScale = (uResolution.y * 0.5) / max(0.25, zCam * uTanHalfFov);
    gl_PointSize = clamp(pxScale * (0.068 + 0.048 * tempNorm), 2.2, 15.0);
    vAlpha = clamp(0.18 + 0.22 * tempNorm, 0.10, 0.48);
}
