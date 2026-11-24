#version 430 core
in vec2 vUv;
out vec4 FragColor;

struct HydroCell {
    int massFixed;
    int momXFixed;
    int momYFixed;
    int momZFixed;
};

layout(std430, binding = 2) readonly buffer HydroGridBuffer {
    HydroCell cells[];
};

uniform vec2 uResolution;
uniform vec3 uCamPos;
uniform vec3 uCamForward;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform float uTanHalfFov;
uniform int uHydroVisMode;   // 1=Density, 2=Pressure, 3=Velocity+Arrows, 4=Shock -div(v), 5=3D Volume Density
uniform int uSlicePlane;     // 0=Equatorial XZ (y=0), 1=Meridional XY (z=0)
uniform float uHorizonR;

const int GRID_DIM = 64;
const float GRID_HALF_EXTENT = 24.0;
const float CELL_SIZE = (2.0 * GRID_HALF_EXTENT) / float(GRID_DIM);
const float INV_CELL_SIZE = float(GRID_DIM) / (2.0 * GRID_HALF_EXTENT);
const float INV_FIXED_SCALE = 1.0 / 1024.0;
const float K_EOS = 0.0000045;

vec3 turboColormap(float t) {
    float x = clamp(t, 0.0, 1.0);
    vec3 c0 = vec3(0.11, 0.08, 0.34);
    vec3 c1 = vec3(0.09, 0.56, 0.78);
    vec3 c2 = vec3(0.55, 0.88, 0.24);
    vec3 c3 = vec3(0.98, 0.55, 0.09);
    vec3 c4 = vec3(0.84, 0.12, 0.14);
    if (x < 0.25) return mix(c0, c1, x * 4.0);
    if (x < 0.50) return mix(c1, c2, (x - 0.25) * 4.0);
    if (x < 0.75) return mix(c2, c3, (x - 0.50) * 4.0);
    return mix(c3, c4, (x - 0.75) * 4.0);
}

void readCell(ivec3 c, out float massVal, out vec3 vBulk) {
    massVal = 0.0;
    vBulk = vec3(0.0);
    if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, ivec3(GRID_DIM)))) {
        return;
    }
    uint flatIdx = uint((c.z * GRID_DIM + c.y) * GRID_DIM + c.x);
    HydroCell hc = cells[flatIdx];
    massVal = float(max(0, hc.massFixed)) * INV_FIXED_SCALE;
    if (massVal > 0.05) {
        vBulk = vec3(hc.momXFixed, hc.momYFixed, hc.momZFixed) * (INV_FIXED_SCALE / massVal);
    }
}

void sampleTrilinear(vec3 worldPos, out float rho, out vec3 vBulk) {
    vec3 gPos = (worldPos + vec3(GRID_HALF_EXTENT)) * INV_CELL_SIZE - vec3(0.5);
    ivec3 c0 = ivec3(floor(gPos));
    vec3 f = fract(gPos);
    rho = 0.0;
    vec3 mom = vec3(0.0);
    for (int dz = 0; dz <= 1; dz++) {
        float wz = (dz == 0) ? (1.0 - f.z) : f.z;
        for (int dy = 0; dy <= 1; dy++) {
            float wy = (dy == 0) ? (1.0 - f.y) : f.y;
            for (int dx = 0; dx <= 1; dx++) {
                float wx = (dx == 0) ? (1.0 - f.x) : f.x;
                float w = wx * wy * wz;
                float m;
                vec3 vb;
                readCell(c0 + ivec3(dx, dy, dz), m, vb);
                rho += w * m;
                mom += w * m * vb;
            }
        }
    }
    vBulk = (rho > 0.05) ? (mom / rho) : vec3(0.0);
}

void main() {
    if (uHydroVisMode <= 0) {
        discard;
    }

    vec2 ndc = vUv * 2.0 - 1.0;
    float aspect = uResolution.x / max(1.0, uResolution.y);
    vec3 rayDir = normalize(uCamForward
                          + ndc.x * aspect * uTanHalfFov * uCamRight
                          + ndc.y * uTanHalfFov * uCamUp);

    // Mode 5: 3D Volumetric Density Raymarch across the 64^3 Eulerian box [-24M, +24M]^3
    if (uHydroVisMode == 5) {
        vec3 boxMin = vec3(-GRID_HALF_EXTENT);
        vec3 boxMax = vec3(GRID_HALF_EXTENT);
        vec3 invDir = 1.0 / max(abs(rayDir), vec3(1e-5)) * sign(rayDir);
        vec3 t0s = (boxMin - uCamPos) * invDir;
        vec3 t1s = (boxMax - uCamPos) * invDir;
        vec3 tSm = min(t0s, t1s);
        vec3 tLg = max(t0s, t1s);
        float tNear = max(0.25, max(tSm.x, max(tSm.y, tSm.z)));
        float tFar = min(110.0, min(tLg.x, min(tLg.y, tLg.z)));
        if (tFar <= tNear) {
            discard;
        }
        const int STEPS = 44;
        float dt = (tFar - tNear) / float(STEPS);
        vec3 accum = vec3(0.0);
        float alphaAcc = 0.0;
        for (int i = 0; i < STEPS; i++) {
            float t = tNear + (float(i) + 0.5) * dt;
            vec3 p = uCamPos + rayDir * t;
            if (length(p) <= uHorizonR * 1.05) {
                continue;
            }
            vec3 gPos = (p + vec3(GRID_HALF_EXTENT)) * INV_CELL_SIZE;
            ivec3 c = ivec3(floor(gPos));
            float m;
            vec3 vb;
            readCell(c, m, vb);
            if (m > 0.25) {
                float normRho = clamp(log(1.0 + m) / log(1.0 + 180.0), 0.0, 1.0);
                vec3 col = turboColormap(normRho);
                float aStep = clamp(normRho * dt * 0.065, 0.0, 0.35);
                accum += (1.0 - alphaAcc) * col * aStep * 1.45;
                alphaAcc += (1.0 - alphaAcc) * aStep;
                if (alphaAcc > 0.92) break;
            }
        }
        if (alphaAcc < 0.004) {
            discard;
        }
        FragColor = vec4(accum / max(1e-3, alphaAcc), alphaAcc * 0.72);
        return;
    }

    // Modes 1..4: Quantitative Eulerian 64^3 Grid Cross-Section Slice (XZ y=0 or XY z=0)
    float denom = (uSlicePlane == 1) ? rayDir.z : rayDir.y;
    if (abs(denom) < 1e-4) {
        discard;
    }
    float tHit = (uSlicePlane == 1) ? (-uCamPos.z / denom) : (-uCamPos.y / denom);
    if (tHit <= 0.2) {
        discard;
    }
    vec3 hitPos = uCamPos + rayDir * tHit;
    if (any(greaterThan(abs(hitPos), vec3(GRID_HALF_EXTENT))) || length(hitPos) <= uHorizonR * 1.05) {
        discard;
    }

    float rho;
    vec3 vBulk;
    sampleTrilinear(hitPos, rho, vBulk);

    vec3 gPos = (hitPos + vec3(GRID_HALF_EXTENT)) * INV_CELL_SIZE;
    ivec3 c = ivec3(floor(gPos));
    vec3 fCell = fract(gPos) - vec3(0.5);

    // Subtle voxel boundaries showing actual 64^3 Eulerian discretization (dx = 0.75M)
    vec2 planeF = (uSlicePlane == 1) ? fCell.xy : fCell.xz;
    float edgeDist = 0.5 - max(abs(planeF.x), abs(planeF.y));
    float gridLine = 1.0 - smoothstep(0.015, 0.045, edgeDist);

    float rawMass;
    vec3 rawV;
    readCell(c, rawMass, rawV);

    vec3 col = vec3(0.0);
    float alpha = 0.0;

    if (uHydroVisMode == 1) {
        // 1. Mass Density rho (log-scaled over 0..250 code units)
        float normRho = clamp(log(1.0 + rho) / log(1.0 + 250.0), 0.0, 1.0);
        col = turboColormap(normRho);
        alpha = smoothstep(0.03, 0.22, normRho) * 0.68;
    } else if (uHydroVisMode == 2) {
        // 2. Polytropic Pressure P = K * rho^(5/3)
        float press = K_EOS * pow(max(0.0, rho), 1.6667);
        float normP = clamp(log(1.0 + press * 4500.0) / log(1.0 + 180.0), 0.0, 1.0);
        col = turboColormap(normP);
        // Isobar contour lines
        float iso = 1.0 - smoothstep(0.0, 0.06, abs(fract(normP * 8.0) - 0.5));
        col += vec3(0.35) * iso * smoothstep(0.05, 0.25, normP);
        alpha = smoothstep(0.02, 0.18, normP) * 0.70;
    } else if (uHydroVisMode == 3) {
        // 3. Bulk Velocity |v| + directional cell velocity arrows
        float speed = length(vBulk);
        float normV = clamp(speed / 0.80, 0.0, 1.0);
        col = turboColormap(normV);
        alpha = (rho > 0.15) ? (0.28 + 0.42 * normV) : 0.0;

        vec2 v2 = (uSlicePlane == 1) ? vBulk.xy : vBulk.xz;
        float v2Len = length(v2);
        if (rawMass > 0.20 && v2Len > 0.02) {
            vec2 dir2 = v2 / v2Len;
            vec2 perp2 = vec2(-dir2.y, dir2.x);
            float along = dot(planeF, dir2);
            float across = abs(dot(planeF, perp2));
            float shaft = (along > -0.32 && along < 0.30) ? (1.0 - smoothstep(0.04, 0.08, across)) : 0.0;
            float head = (along > 0.05 && along < 0.34)
                       ? (1.0 - smoothstep(0.02, 0.06, abs(across - (0.34 - along) * 0.65)))
                       : 0.0;
            float arrowMask = max(shaft, head);
            col = mix(col, vec3(1.10, 1.12, 1.18), arrowMask * 0.88);
            alpha = max(alpha, arrowMask * 0.84);
        }
    } else if (uHydroVisMode == 4) {
        // 4. Stream-intersection shock convergence: -div(v_bulk) across adjacent Eulerian cells
        float mXp, mXm, mYp, mYm, mZp, mZm;
        vec3 vXp, vXm, vYp, vYm, vZp, vZm;
        readCell(c + ivec3(1, 0, 0), mXp, vXp);
        readCell(c - ivec3(1, 0, 0), mXm, vXm);
        readCell(c + ivec3(0, 1, 0), mYp, vYp);
        readCell(c - ivec3(0, 1, 0), mYm, vYm);
        readCell(c + ivec3(0, 0, 1), mZp, vZp);
        readCell(c - ivec3(0, 0, 1), mZm, vZm);

        float divV = ((vXp.x - vXm.x) + (vYp.y - vYm.y) + (vZp.z - vZm.z)) / (2.0 * CELL_SIZE);
        float conv = max(0.0, -divV);
        float shockNorm = clamp(conv * 1.45, 0.0, 1.0);
        float maskRho = smoothstep(0.25, 2.5, rho);
        col = turboColormap(shockNorm) * (0.45 + 0.85 * shockNorm);
        alpha = maskRho * (0.18 + 0.64 * shockNorm);
    }

    if (alpha > 0.01) {
        col = mix(col, col + vec3(0.18, 0.24, 0.32), gridLine * 0.35);
    }
    FragColor = vec4(col, clamp(alpha, 0.0, 0.85));
}
