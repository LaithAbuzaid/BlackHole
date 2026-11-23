package io.github.laithabuzaid.blackhole.math;

/**
 * Double-precision CPU mirror of the analytical effective Binary Black Hole spacetime
 * integrated on the GPU in {@code binary_merger.frag}: a superposed spinning Kerr–Schild
 * metric with 1PN gravitomagnetic orbital frame-dragging and smooth transition to the
 * spinning Kerr–Schild remnant with {@code (l=2, m=2)} QNM perturbation.
 *
 * <h3>Physical Formulation &amp; Explicit Validity Boundaries</h3>
 * <pre>
 *   H_bin(x, p; pt) = 0.5 * [ -pt^2 + |p|^2
 *       - 2 * (1 - w_m) * sum_{i=1,2} H_i(x_i) * ( (-pt + l_i . p)^2 + 2 * pt * (v_i . p) )
 *       - 2 * w_m * H_rem(x) * (-pt + l_rem . p)^2 ] = 0
 * </pre>
 * <ul>
 *   <li><b>Exact Isolated Single-Body Limit:</b> When {@code m2 = 0} and {@code v1 = 0},
 *       the Hamiltonian reduces identically to the exact spinning Kerr–Schild null geodesic
 *       metric of a Kerr black hole with physical spin {@code a_1 = m_1 * chi_1} and outer
 *       horizon {@code r_{+,1} = m_1 (1 + sqrt(1 - chi_1^2))}.</li>
 *   <li><b>Frozen-Slice Horizon Regularity &amp; Co-Moving Frame Dragging:</b> Retaining the
 *       linear 1PN gravitomagnetic orbital shift ({@code -2 H_i pt v_i} in {@code dx/dlambda},
 *       which yields forward-in-time ZAMO dragging {@code dx/dt = +2 H_i v_i / (1 + 2 H_i)}
 *       along {@code +v_i}) while keeping the static-slice {@code g^{00}} structure avoids the
 *       artificial frozen-slice Killing-horizon wall at {@code r = 2m (1+v)/(1-v)} that occurs
 *       if a full time-dependent Lorentz boost is evaluated at fixed {@code pt = const}.</li>
 *   <li><b>Exact Post-Merger Limit:</b> When {@code mergeBlend = 1} and {@code ringdownEnv -> 0},
 *       it reduces identically to the exact spinning Kerr–Schild remnant metric with physical
 *       spin parameter {@code a_f = M_f * chi_f}.</li>
 *   <li><b>Approximation in Binary Overlap:</b> Linear superposition of two Kerr–Schild
 *       inverse-metric perturbations is an <i>analytical effective spacetime</i>, NOT a 3+1
 *       Numerical Relativity solution of the nonlinear vacuum Einstein field equations in the
 *       strong-field overlap region.</li>
 *   <li><b>Exact Canonical Conservation:</b> All derivatives are analytical gradients of
 *       {@code H_bin}, guaranteeing {@code d(H_bin)/d(lambda) == 0} identically.</li>
 * </ul>
 */
public final class BinarySpacetimeMetric {

    /** Snapshot of binary spacetime uniforms shared identically between CPU validation and GPU shader. */
    public static final class SpacetimeSnapshot {
        public double pos1X, pos1Y, pos1Z;
        public double pos2X, pos2Y, pos2Z;
        public double vel1X, vel1Y, vel1Z;
        public double vel2X, vel2Y, vel2Z;
        public double mass1 = 0.5;
        public double mass2 = 0.5;
        public double chi1 = 0.0;
        public double chi2 = 0.0;
        public double remnantMass = 0.9517;
        public double remnantSpin = 0.6865; // Signed dimensionless chi_f; physical Kerr spin a_f = M_f * chi_f
        public double mergeBlend = 0.0;
        public double ringdownEnv = 0.0;
        public double gwPhase = 0.0;
        // Orthonormal orbital-frame basis (e1, lHat, e3); default horizontal XZ plane (lHat = +Y)
        public double e1X = 1.0, e1Y = 0.0, e1Z = 0.0;
        public double lHatX = 0.0, lHatY = 1.0, lHatZ = 0.0;
        public double e3X = 0.0, e3Y = 0.0, e3Z = 1.0;

        /** Sets the orbital plane inclination (rotation about X axis by {@code incDeg}). */
        public void setOrbitalInclinationDeg(double incDeg) {
            double rad = Math.toRadians(incDeg);
            double c = Math.cos(rad);
            double s = Math.sin(rad);
            e1X = 1.0; e1Y = 0.0; e1Z = 0.0;
            lHatX = 0.0; lHatY = c; lHatZ = s;
            e3X = 0.0; e3Y = -s; e3Z = c;
        }
    }

    private BinarySpacetimeMetric() {
    }

    /** Exact oblate Kerr spheroidal radius {@code r} for orbital-frame coordinates {@code (lx, ly, lz)} and spin {@code a}. */
    public static double kerrRadius(double lx, double ly, double lz, double a2) {
        double r2 = lx * lx + ly * ly + lz * lz;
        if (a2 <= 1e-14) {
            return Math.sqrt(Math.max(1e-10, r2));
        }
        double b = r2 - a2;
        double disc = Math.max(0.0, b * b + 4.0 * a2 * ly * ly);
        return Math.sqrt(Math.max(1e-8, 0.5 * (b + Math.sqrt(disc))));
    }

    /** Exact outer Kerr horizon radius {@code r_+ = m * (1 + sqrt(1 - chi^2))}. */
    public static double componentHorizonRadius(double mass, double chi) {
        double c = Math.max(-0.998, Math.min(0.998, chi));
        return mass * (1.0 + Math.sqrt(Math.max(0.0, 1.0 - c * c)));
    }

    /** Evaluates the spinning Kerr–Schild spatial null vector {@code l_i} (satisfies {@code |l_i| == 1} identically). */
    public static void evalSpinningNullVector(
            double x, double y, double z,
            double bx, double by, double bz,
            double mass, double chi,
            SpacetimeSnapshot s,
            double[] outLxyz
    ) {
        double dx = x - bx, dy = y - by, dz = z - bz;
        double lx = s.e1X * dx + s.e1Y * dy + s.e1Z * dz;
        double ly = s.lHatX * dx + s.lHatY * dy + s.lHatZ * dz;
        double lz = s.e3X * dx + s.e3Y * dy + s.e3Z * dz;

        double a = mass * chi;
        double a2 = a * a;
        double r = kerrRadius(lx, ly, lz, a2);
        double invS = 1.0 / Math.max(1e-10, r * r + a2);
        double invR = 1.0 / Math.max(1e-10, r);

        double ltx = (r * lx + a * lz) * invS;
        double lty = ly * invR;
        double ltz = (r * lz - a * lx) * invS;

        outLxyz[0] = s.e1X * ltx + s.lHatX * lty + s.e3X * ltz;
        outLxyz[1] = s.e1Y * ltx + s.lHatY * lty + s.e3Y * ltz;
        outLxyz[2] = s.e1Z * ltx + s.lHatZ * lty + s.e3Z * ltz;
    }

    /** Evaluates exact binary effective null Hamiltonian {@code H_bin = 0.5 * g^{mu nu} p_mu p_nu}. */
    public static double hamiltonian(double x, double y, double z,
                                     double px, double py, double pz, double pt,
                                     SpacetimeSnapshot s) {
        double val = -pt * pt + px * px + py * py + pz * pz;
        double wBin = 1.0 - s.mergeBlend;
        if (wBin > 1e-6) {
            val -= 2.0 * evalBodyScalarContrib(x, y, z, px, py, pz, pt,
                    s.pos1X, s.pos1Y, s.pos1Z, s.vel1X, s.vel1Y, s.vel1Z,
                    s.mass1, s.chi1, wBin, s);
            val -= 2.0 * evalBodyScalarContrib(x, y, z, px, py, pz, pt,
                    s.pos2X, s.pos2Y, s.pos2Z, s.vel2X, s.vel2Y, s.vel2Z,
                    s.mass2, s.chi2, wBin, s);
        }
        if (s.mergeBlend > 1e-6) {
            val -= 2.0 * evalRemnantScalarContrib(x, y, z, px, py, pz, pt, s.mergeBlend, s);
        }
        return 0.5 * val;
    }

    private static double evalBodyScalarContrib(
            double x, double y, double z, double px, double py, double pz, double pt,
            double bx, double by, double bz, double vx, double vy, double vz,
            double mass, double chi, double weight, SpacetimeSnapshot s) {
        double mw = mass * weight;
        if (mw <= 1e-12) {
            return 0.0;
        }
        double dx = x - bx, dy = y - by, dz = z - bz;
        double lx = s.e1X * dx + s.e1Y * dy + s.e1Z * dz;
        double ly = s.lHatX * dx + s.lHatY * dy + s.lHatZ * dz;
        double lz = s.e3X * dx + s.e3Y * dy + s.e3Z * dz;

        double prx = s.e1X * px + s.e1Y * py + s.e1Z * pz;
        double pry = s.lHatX * px + s.lHatY * py + s.lHatZ * pz;
        double prz = s.e3X * px + s.e3Y * py + s.e3Z * pz;

        double a = mass * chi;
        double a2 = a * a;
        double r = kerrRadius(lx, ly, lz, a2);
        double r2 = r * r;
        double bigD = Math.max(1e-10, r2 * r2 + a2 * ly * ly);
        double hVal = (mw * r2 * r) / bigD;

        double invS = 1.0 / Math.max(1e-10, r2 + a2);
        double invR = 1.0 / Math.max(1e-10, r);
        double ltx = (r * lx + a * lz) * invS;
        double lty = ly * invR;
        double ltz = (r * lz - a * lx) * invS;

        double q = -pt + ltx * prx + lty * pry + ltz * prz;
        double vp = vx * px + vy * py + vz * pz;
        return hVal * (q * q + 2.0 * pt * vp);
    }

    private static double evalRemnantScalarContrib(
            double x, double y, double z, double px, double py, double pz, double pt,
            double weight, SpacetimeSnapshot s) {
        double lx = s.e1X * x + s.e1Y * y + s.e1Z * z;
        double ly = s.lHatX * x + s.lHatY * y + s.lHatZ * z;
        double lz = s.e3X * x + s.e3Y * y + s.e3Z * z;

        double prx = s.e1X * px + s.e1Y * py + s.e1Z * pz;
        double pry = s.lHatX * px + s.lHatY * py + s.lHatZ * pz;
        double prz = s.e3X * px + s.e3Y * py + s.e3Z * pz;

        double a = s.remnantMass * s.remnantSpin;
        double a2 = a * a;
        double r = kerrRadius(lx, ly, lz, a2);
        double r2 = r * r;
        double bigD = Math.max(1e-10, r2 * r2 + a2 * ly * ly);
        double hBase = (s.remnantMass * weight * r2 * r) / bigD;
        double hRem = hBase * evalRemnantQnmMod(lx, ly, lz, r, s);

        double invS = 1.0 / Math.max(1e-10, r2 + a2);
        double invR = 1.0 / Math.max(1e-10, r);
        double ltx = (r * lx + a * lz) * invS;
        double lty = ly * invR;
        double ltz = (r * lz - a * lx) * invS;

        double q = -pt + ltx * prx + lty * pry + ltz * prz;
        return hRem * q * q;
    }

    private static double evalRemnantQnmMod(double lx, double ly, double lz, double rRem, SpacetimeSnapshot s) {
        if (s.ringdownEnv <= 1e-4) {
            return 1.0;
        }
        double rho2 = lx * lx + lz * lz;
        double bigR2 = Math.max(1e-8, rho2 + ly * ly);
        double sin2Th = rho2 / bigR2;
        double cos2Phi = (rho2 > 1e-8) ? ((lx * lx - lz * lz) / rho2) : 1.0;
        double sin2Phi = (rho2 > 1e-8) ? ((2.0 * lx * lz) / rho2) : 0.0;
        double cosPhase = Math.cos(s.gwPhase) * cos2Phi + Math.sin(s.gwPhase) * sin2Phi;
        double radialDecay = Math.exp(-Math.max(0.0, rRem - 1.5) * 0.45);
        return 1.0 + 0.065 * s.ringdownEnv * radialDecay * sin2Th * cosPhase;
    }

    /** Exact canonical Hamiltonian derivatives {@code [dx, dy, dz, dpx, dpy, dpz]} for {@code H_bin}. */
    public static void canonicalDerivatives(double x, double y, double z,
                                            double px, double py, double pz, double pt,
                                            SpacetimeSnapshot s,
                                            double[] out6) {
        out6[0] = px; out6[1] = py; out6[2] = pz;
        out6[3] = 0.0; out6[4] = 0.0; out6[5] = 0.0;

        double wBin = 1.0 - s.mergeBlend;
        if (wBin > 1e-6) {
            addSpinningBodyDeriv(x, y, z, px, py, pz, pt,
                    s.pos1X, s.pos1Y, s.pos1Z, s.vel1X, s.vel1Y, s.vel1Z,
                    s.mass1, s.chi1, wBin, s, out6);
            addSpinningBodyDeriv(x, y, z, px, py, pz, pt,
                    s.pos2X, s.pos2Y, s.pos2Z, s.vel2X, s.vel2Y, s.vel2Z,
                    s.mass2, s.chi2, wBin, s, out6);
        }
        if (s.mergeBlend > 1e-6) {
            addRemnantDeriv(x, y, z, px, py, pz, pt, s.mergeBlend, s, out6);
        }
    }

    private static void addSpinningBodyDeriv(
            double x, double y, double z, double px, double py, double pz, double pt,
            double bx, double by, double bz, double vx, double vy, double vz,
            double mass, double chi, double weight, SpacetimeSnapshot s, double[] out6) {
        double mw = mass * weight;
        if (mw <= 1e-12) {
            return;
        }
        double dx = x - bx, dy = y - by, dz = z - bz;
        double lx = s.e1X * dx + s.e1Y * dy + s.e1Z * dz;
        double ly = s.lHatX * dx + s.lHatY * dy + s.lHatZ * dz;
        double lz = s.e3X * dx + s.e3Y * dy + s.e3Z * dz;

        double prx = s.e1X * px + s.e1Y * py + s.e1Z * pz;
        double pry = s.lHatX * px + s.lHatY * py + s.lHatZ * pz;
        double prz = s.e3X * px + s.e3Y * py + s.e3Z * pz;

        double a = mass * chi;
        double a2 = a * a;
        double r = kerrRadius(lx, ly, lz, a2);
        double r2 = r * r;
        double invR = 1.0 / Math.max(1e-10, r);
        double bigD = Math.max(1e-10, r2 * r2 + a2 * ly * ly);
        double invD = 1.0 / bigD;
        double bigS = Math.max(1e-10, r2 + a2);
        double invS = 1.0 / bigS;

        double hVal = (mw * r2 * r) * invD;
        double gradRx = (r * invD) * (r2 * lx);
        double gradRy = (r * invD) * (bigS * ly);
        double gradRz = (r * invD) * (r2 * lz);

        double hGradFac = mw * r2 * (3.0 * a2 * ly * ly - r2 * r2) * (invD * invD);
        double gradHx = hGradFac * gradRx;
        double gradHy = hGradFac * gradRy - mw * (2.0 * a2 * r2 * r * ly) * (invD * invD);
        double gradHz = hGradFac * gradRz;

        double ltx = (r * lx + a * lz) * invS;
        double lty = ly * invR;
        double ltz = (r * lz - a * lx) * invS;

        double q = -pt + ltx * prx + lty * pry + ltz * prz;
        double vp = vx * px + vy * py + vz * pz;

        // Canonical dx/dlambda = dH/dp = -2*H*q*l - 2*H*pt*v (forward-in-time ZAMO dragged along +v)
        double dxScale = -2.0 * hVal * q;
        double shiftScale = -2.0 * hVal * pt;
        out6[0] += dxScale * (s.e1X * ltx + s.lHatX * lty + s.e3X * ltz) + shiftScale * vx;
        out6[1] += dxScale * (s.e1Y * ltx + s.lHatY * lty + s.e3Y * ltz) + shiftScale * vy;
        out6[2] += dxScale * (s.e1Z * ltx + s.lHatZ * lty + s.e3Z * ltz) + shiftScale * vz;

        double xpZp = lx * prx + lz * prz;
        double zpxXpz = lz * prx - lx * prz;
        double dlpDr = ((a2 - r2) * xpZp - 2.0 * a * r * zpxXpz) * (invS * invS)
                     - (ly * pry) * (invR * invR);
        double glpx = (r * prx - a * prz) * invS + dlpDr * gradRx;
        double glpy = pry * invR + dlpDr * gradRy;
        double glpz = (r * prz + a * prx) * invS + dlpDr * gradRz;

        // Canonical dp/dlambda = -dH/dx = (q^2 + 2*pt*(v.p))*gradH + 2*H*q*grad(l.p)
        double hCoeff = q * q + 2.0 * pt * vp;
        double twoHq = 2.0 * hVal * q;
        double gtx = hCoeff * gradHx + twoHq * glpx;
        double gty = hCoeff * gradHy + twoHq * glpy;
        double gtz = hCoeff * gradHz + twoHq * glpz;

        out6[3] += s.e1X * gtx + s.lHatX * gty + s.e3X * gtz;
        out6[4] += s.e1Y * gtx + s.lHatY * gty + s.e3Y * gtz;
        out6[5] += s.e1Z * gtx + s.lHatZ * gty + s.e3Z * gtz;
    }

    private static void addRemnantDeriv(
            double x, double y, double z, double px, double py, double pz, double pt,
            double weight, SpacetimeSnapshot s, double[] out6) {
        double lx = s.e1X * x + s.e1Y * y + s.e1Z * z;
        double ly = s.lHatX * x + s.lHatY * y + s.lHatZ * z;
        double lz = s.e3X * x + s.e3Y * y + s.e3Z * z;

        double prx = s.e1X * px + s.e1Y * py + s.e1Z * pz;
        double pry = s.lHatX * px + s.lHatY * py + s.lHatZ * pz;
        double prz = s.e3X * px + s.e3Y * py + s.e3Z * pz;

        double a = s.remnantMass * s.remnantSpin;
        double a2 = a * a;
        double r = kerrRadius(lx, ly, lz, a2);
        double r2 = r * r;
        double invR = 1.0 / Math.max(1e-10, r);
        double bigD = Math.max(1e-10, r2 * r2 + a2 * ly * ly);
        double invD = 1.0 / bigD;
        double bigS = Math.max(1e-10, r2 + a2);
        double invS = 1.0 / bigS;

        double mw = s.remnantMass * weight;
        double hBase = (mw * r2 * r) * invD;
        double gradRx = (r * invD) * (r2 * lx);
        double gradRy = (r * invD) * (bigS * ly);
        double gradRz = (r * invD) * (r2 * lz);

        double hGradFac = mw * r2 * (3.0 * a2 * ly * ly - r2 * r2) * (invD * invD);
        double gradHBx = hGradFac * gradRx;
        double gradHBy = hGradFac * gradRy - mw * (2.0 * a2 * r2 * r * ly) * (invD * invD);
        double gradHBz = hGradFac * gradRz;

        double qnmMod = 1.0;
        double gqx = 0.0, gqy = 0.0, gqz = 0.0;
        if (s.ringdownEnv > 1e-4) {
            double rho2 = lx * lx + lz * lz;
            double bigR2 = Math.max(1e-8, rho2 + ly * ly);
            double invR2 = 1.0 / bigR2;
            double sin2Th = rho2 * invR2;
            double invRho2 = 1.0 / Math.max(1e-8, rho2);
            double cos2Phi = (rho2 > 1e-8) ? ((lx * lx - lz * lz) * invRho2) : 1.0;
            double sin2Phi = (rho2 > 1e-8) ? ((2.0 * lx * lz) * invRho2) : 0.0;
            double cGw = Math.cos(s.gwPhase);
            double sGw = Math.sin(s.gwPhase);
            double cosPhase = cGw * cos2Phi + sGw * sin2Phi;
            double sinPhase = sGw * cos2Phi - cGw * sin2Phi;
            double radialDecay = Math.exp(-Math.max(0.0, r - 1.5) * 0.45);
            double amp = 0.065 * s.ringdownEnv * radialDecay;
            qnmMod = 1.0 + amp * sin2Th * cosPhase;

            double gStx = 2.0 * invR2 * lx * (1.0 - sin2Th);
            double gSty = -2.0 * invR2 * ly * sin2Th;
            double gStz = 2.0 * invR2 * lz * (1.0 - sin2Th);
            double gPhix = (rho2 > 1e-8) ? (-lz * invRho2) : 0.0;
            double gPhiz = (rho2 > 1e-8) ? (lx * invRho2) : 0.0;
            double dDecayDr = (r > 1.5) ? (-0.45 * radialDecay) : 0.0;
            double drTerm = 0.065 * s.ringdownEnv * sin2Th * cosPhase * dDecayDr;

            gqx = (amp * cosPhase) * gStx + (amp * sin2Th) * (2.0 * sinPhase * gPhix) + drTerm * gradRx;
            gqy = (amp * cosPhase) * gSty + drTerm * gradRy;
            gqz = (amp * cosPhase) * gStz + (amp * sin2Th) * (2.0 * sinPhase * gPhiz) + drTerm * gradRz;
        }

        double hRem = hBase * qnmMod;
        double gradHx = gradHBx * qnmMod + hBase * gqx;
        double gradHy = gradHBy * qnmMod + hBase * gqy;
        double gradHz = gradHBz * qnmMod + hBase * gqz;

        double ltx = (r * lx + a * lz) * invS;
        double lty = ly * invR;
        double ltz = (r * lz - a * lx) * invS;

        double q = -pt + ltx * prx + lty * pry + ltz * prz;
        double dxScale = -2.0 * hRem * q;
        out6[0] += dxScale * (s.e1X * ltx + s.lHatX * lty + s.e3X * ltz);
        out6[1] += dxScale * (s.e1Y * ltx + s.lHatY * lty + s.e3Y * ltz);
        out6[2] += dxScale * (s.e1Z * ltx + s.lHatZ * lty + s.e3Z * ltz);

        double xpZp = lx * prx + lz * prz;
        double zpxXpz = lz * prx - lx * prz;
        double dlpDr = ((a2 - r2) * xpZp - 2.0 * a * r * zpxXpz) * (invS * invS)
                     - (ly * pry) * (invR * invR);
        double glpx = (r * prx - a * prz) * invS + dlpDr * gradRx;
        double glpy = pry * invR + dlpDr * gradRy;
        double glpz = (r * prz + a * prx) * invS + dlpDr * gradRz;

        double q2 = q * q;
        double twoHq = 2.0 * hRem * q;
        double gtx = q2 * gradHx + twoHq * glpx;
        double gty = q2 * gradHy + twoHq * glpy;
        double gtz = q2 * gradHz + twoHq * glpz;

        out6[3] += s.e1X * gtx + s.lHatX * gty + s.e3X * gtz;
        out6[4] += s.e1Y * gtx + s.lHatY * gty + s.e3Y * gtz;
        out6[5] += s.e1Z * gtx + s.lHatZ * gty + s.e3Z * gtz;
    }

    /** Returns an {@link OdeSystem} for 6-state null geodesic {@code [x, y, z, px, py, pz]} at fixed {@code pt}. */
    public static OdeSystem nullGeodesicOde(SpacetimeSnapshot snapshot, double pt) {
        return (lambda, y, dydl) -> canonicalDerivatives(
                y[0], y[1], y[2], y[3], y[4], y[5], pt, snapshot, dydl);
    }

    /** Initializes a strictly null covariant momentum {@code (px, py, pz)} solving {@code H_bin(x, p; pt=1) == 0}. */
    public static double[] initializeNullRay(double x, double y, double z,
                                             double dirX, double dirY, double dirZ,
                                             SpacetimeSnapshot s) {
        double len = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        double px = dirX / len;
        double py = dirY / len;
        double pz = dirZ / len;
        double pt = 1.0;
        double[] d6 = new double[6];
        for (int iter = 0; iter < 12; iter++) {
            double f = hamiltonian(x, y, z, px, py, pz, pt, s);
            if (Math.abs(f) < 1e-14) {
                break;
            }
            canonicalDerivatives(x, y, z, px, py, pz, pt, s, d6);
            double denom = d6[0] * px + d6[1] * py + d6[2] * pz;
            if (Math.abs(denom) > 1e-9) {
                double scale = Math.max(0.5, Math.min(1.5, 1.0 - f / denom));
                px *= scale; py *= scale; pz *= scale;
            }
        }
        return new double[]{x, y, z, px, py, pz};
    }
}
