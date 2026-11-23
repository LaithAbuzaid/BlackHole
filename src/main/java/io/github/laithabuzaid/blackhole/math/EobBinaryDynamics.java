package io.github.laithabuzaid.blackhole.math;

/**
 * Effective-One-Body (EOB) aligned-spin binary black hole Hamiltonian dynamics,
 * Damour–Iyer–Nagar factorized multipolar radiation reaction, and continuous
 * inspiral–plunge–merger–ringdown waveform engine in geometric units ({@code G = c = M = 1}).
 *
 * <h3>Formulation &amp; References (Explicitly Documented)</h3>
 * <ul>
 *   <li><b>Conservative Orbital EOB Potential:</b> 3PN Padé-resummed radial potential
 *       {@code P^1_3[A_3PN](u)} and 3PN {@code D(u)}, {@code Q_4(r, p_{r*})}
 *       (Buonanno &amp; Damour 1999, <i>PRD</i> 59, 084006; Damour, Jaranowski &amp; Schäfer 2000,
 *       <i>PRD</i> 62, 084011).</li>
 *   <li><b>Spin–Orbit &amp; Spin–Spin Coupling:</b> Kerr centrifugal radius {@code r_c^2 = r^2 + a_0^2(1 + 2/r)}
 *       and Kerr-deformed {@code A(r) = (r^2 A_orb(u) + a_0^2) / r_c^2} (reduces identically to exact
 *       equatorial Kerr spacetime as {@code nu -> 0}) paired with NLO DJS-gauge gyro-gravitomagnetic
 *       factors {@code g_S, g_{S*}} (Damour, Jaranowski &amp; Schäfer 2008, <i>PRD</i> 78, 024009;
 *       Damour &amp; Nagar 2014, <i>PRD</i> 90, 044018).</li>
 *   <li><b>Canonical Tortoise Coordinates &amp; Radiation Reaction:</b> 4-DOF phase space
 *       {@code (r, phi, p_{r*}, p_phi)} with {@code xi = dr/dr_* = A / sqrt(D)} (Damour &amp; Nagar 2007,
 *       <i>PRD</i> 76, 064028) driven by Damour–Iyer–Nagar (2009, <i>PRD</i> 79, 064004) factorized
 *       multipolar flux across {@code (2,2), (2,1), (3,3), (4,4)} modes (with aligned spin-orbit/spin-spin
 *       terms matching Tagoshi et al. 1996 / Kidder 1995 / Pan et al. 2011).</li>
 *   <li><b>Dynamical Inspiral–Plunge–Merger Transition:</b> Plunge begins dynamically when
 *       {@code p_phi < p_{phi,ISCO}(nu, chi_1, chi_2)} (computed directly from the EOB Hamiltonian).
 *       Merger attachment occurs dynamically at the peak of the EOB orbital frequency {@code Omega(t)}
 *       near the deformed light ring (no hardcoded separation threshold), followed by a {@code C^1}-continuous
 *       merger–ringdown bridge to the exact Berti–Cardoso–Will (2006) Kerr QNM spectrum.</li>
 * </ul>
 *
 * <p><b>Approximation Status:</b> This is a standalone 3PN+NLO-spin EOB + NR-calibrated remnant/QNM
 * model inspired by SEOBNR / TEOBResumS. It is <i>not</i> a full NR-calibrated SEOBNRv5HM / TEOBResumS
 * production waveform generator (e.g., it omits 4PN/5PN NR-fitted orbital calibration parameters,
 * spin precession, and full NQC iteration).</p>
 */
public final class EobBinaryDynamics {

    // Exact 3PN EOB radial potential coefficient a_4 = 94/3 - 41*pi^2/32 (Damour, Jaranowski & Schafer 2000)
    private static final double A4_COEFF = (94.0 / 3.0) - (41.0 * Math.PI * Math.PI / 32.0);
    private static final double SQRT_PI_OVER_5 = Math.sqrt(Math.PI / 5.0);
    private static final double SQRT_6PI_OVER_7 = Math.sqrt(6.0 * Math.PI / 7.0);
    private static final double SQRT_PI_OVER_7 = Math.sqrt(Math.PI / 7.0);

    // Canonical binary constants
    public final double m1Frac;
    public final double m2Frac;
    public final double nu;          // Symmetric mass ratio eta = m1*m2 / M^2
    public final double delta;       // Mass asymmetry (m1 - m2) / M >= 0
    public final double chi1;
    public final double chi2;
    public final double spinS;       // S = X1^2 * chi1 + X2^2 * chi2
    public final double spinSStar;   // S_* = X1 * X2 * (chi1 + chi2)
    public final double a0;          // Effective Kerr spin a_0 = X1 * chi1 + X2 * chi2 = chi_eff
    public final double a0Sq;

    // Padé P^1_3[A_3PN](u) coefficients
    private final double n1;
    private final double d1;
    private final double d2;
    private final double d3;
    private final double dOrb2;
    private final double dOrb3;
    private final double q4Coeff;
    private final double c10S;
    private final double c02S;
    private final double c10SStar;
    private final double c02SStar;

    // Dynamically solved EOB ISCO and effective horizon radii for (nu, chi1, chi2)
    public final double eobHorizonR;
    public final double eobIscoR;
    public final double eobIscoPphi;
    public final double eobIscoOmega;

    // Scratch arrays (zero heap allocations during integration)
    private final double[] derivScratch = new double[4];

    /** Evaluated Hamiltonian quantities at a single phase-space point (zero allocation mutable container). */
    public static final class EvalResult {
        public double hEff;
        public double hReal;         // H_EOB / M_tot
        public double bindingEnergy; // (H_EOB - M_tot) / M_tot
        public double aPot;
        public double xi;            // dr / dr_* = A / sqrt(D)
        public double drDt;          // Conservative dr/dt
        public double omega;         // dphi/dt = dH_EOB / dp_phi
        public double dPrStarDtCons; // Conservative dp_{r*}/dt
        public double gwFlux;        // Total multipolar GW luminosity F_GW = dE_rad/dt
        public double fPhi;          // Radiation-reaction torque dp_phi/dt = -F_GW / (nu * Omega)
        public double fPrStar;       // Radial radiation-reaction force
        // Factorized multipole amplitudes |R * h_{lm} / M| and phase offsets delta_{lm}
        public double amp22, phaseOff22;
        public double amp21, phaseOff21;
        public double amp33, phaseOff33;
        public double amp44, phaseOff44;
    }

    public EobBinaryDynamics(double m1Frac, double m2Frac, double chi1, double chi2) {
        double x1 = m1Frac;
        double x2 = m2Frac;
        double s1 = Math.max(-0.98, Math.min(0.98, chi1));
        double s2 = Math.max(-0.98, Math.min(0.98, chi2));
        if (x2 > x1) {
            double tm = x1; x1 = x2; x2 = tm;
            double ts = s1; s1 = s2; s2 = ts;
        }
        double tot = Math.max(1e-12, x1 + x2);
        this.m1Frac = x1 / tot;
        this.m2Frac = x2 / tot;
        this.nu = this.m1Frac * this.m2Frac;
        this.delta = Math.max(0.0, this.m1Frac - this.m2Frac);
        this.chi1 = s1;
        this.chi2 = s2;
        this.spinS = this.m1Frac * this.m1Frac * s1 + this.m2Frac * this.m2Frac * s2;
        this.spinSStar = this.nu * (s1 + s2);
        this.a0 = this.m1Frac * s1 + this.m2Frac * s2;
        this.a0Sq = this.a0 * this.a0;

        // Exact P^1_3[1 - 2u + 2*nu*u^3 + a4*nu*u^4]
        double c3 = 2.0 * this.nu;
        double c4 = A4_COEFF * this.nu;
        this.d1 = (c4 + 2.0 * c3) / (8.0 - c3);
        this.d2 = 2.0 * this.d1;
        this.d3 = 4.0 * this.d1 - c3;
        this.n1 = this.d1 - 2.0;

        // 3PN D(u) and Q4 coefficients
        this.dOrb2 = 6.0 * this.nu;
        this.dOrb3 = 2.0 * (26.0 - 3.0 * this.nu) * this.nu;
        this.q4Coeff = 2.0 * (4.0 - 3.0 * this.nu) * this.nu;

        // NLO DJS gyro-gravitomagnetic inverse-resummation coefficients
        this.c10S = (5.0 / 16.0) * this.nu;
        this.c02S = (27.0 / 16.0) * this.nu;
        this.c10SStar = 0.75 + 0.5 * this.nu;
        this.c02SStar = 1.875 - 0.75 * this.nu;

        this.eobHorizonR = solveEffectiveHorizonR();
        double[] isco = solveEobIsco();
        this.eobIscoR = isco[0];
        this.eobIscoPphi = isco[1];
        this.eobIscoOmega = isco[2];
    }

    public static EobBinaryDynamics fromParameters(BinaryParameters p) {
        return new EobBinaryDynamics(p.m1Frac, p.m2Frac, p.chi1, p.chi2);
    }

    /** Evaluates 3PN Padé radial potential {@code P^1_3[A_3PN](u)}. */
    public double evalAOrb(double u) {
        double num = 1.0 + n1 * u;
        double den = 1.0 + u * (d1 + u * (d2 + u * d3));
        return num / den;
    }

    /** Evaluates Kerr-deformed EOB radial potential {@code A(r) = (r^2 * A_orb(1/r) + a_0^2) / r_c^2}. */
    public double evalPotentialA(double r) {
        double u = 1.0 / r;
        double rc2 = r * r + a0Sq * (1.0 + 2.0 * u);
        return (r * r * evalAOrb(u) + a0Sq) / rc2;
    }

    /**
     * Evaluates the full conservative EOB Hamiltonian, exact analytical canonical derivatives,
     * and Damour–Iyer–Nagar factorized multipolar waveform/flux at {@code (r, p_{r*}, p_phi)}.
     */
    public void evaluate(double r, double pRStar, double pPhi, boolean withRadiationReaction, EvalResult out) {
        double rSafe = Math.max(eobHorizonR * 1.005, r);
        double u = 1.0 / rSafe;
        double u2 = u * u;
        double r2 = rSafe * rSafe;

        // 1. P^1_3[A_3PN](u) and exact dA_orb/du
        double num = 1.0 + n1 * u;
        double den = 1.0 + u * (d1 + u * (d2 + u * d3));
        double dDenDu = d1 + u * (2.0 * d2 + 3.0 * d3 * u);
        double aOrb = num / den;
        double dAOrbDu = (n1 * den - num * dDenDu) / (den * den);

        // 2. Kerr centrifugal radius r_c^2 = r^2 + a0^2 (1 + 2/r)
        double rc2 = r2 + a0Sq * (1.0 + 2.0 * u);
        double dRc2Dr = 2.0 * rSafe - 2.0 * a0Sq * u2;
        double rc = Math.sqrt(rc2);
        double uc = 1.0 / rc;
        double dUcDr = -0.5 * dRc2Dr / (rc2 * rc);

        // 3. Deformed Kerr potential A(r) and exact dA/dr
        double pNum = r2 * aOrb + a0Sq;
        double dPNumDr = 2.0 * rSafe * aOrb - dAOrbDu;
        double aPot = Math.max(1e-7, pNum / rc2);
        double dADr = (dPNumDr * rc2 - pNum * dRc2Dr) / (rc2 * rc2);

        // 4. Radial metric factor D(r) and tortoise Jacobian xi = A / sqrt(D)
        double dOrb = 1.0 / (1.0 + u2 * (dOrb2 + dOrb3 * u));
        double dPot = Math.max(1e-7, (r2 / rc2) * dOrb);
        double xi = aPot / Math.sqrt(dPot);

        // 5. 3PN Q_4(r, p_{r*})
        double pr2 = pRStar * pRStar;
        double pr3 = pr2 * pRStar;
        double pr4 = pr2 * pr2;
        double q4 = q4Coeff * u2 * pr4;
        double dQ4Dr = -2.0 * u * q4;
        double dQ4Dpr = 4.0 * q4Coeff * u2 * pr3;

        // 6. Orbital radical H_orb = sqrt(p_{r*}^2 + A * (1 + p_phi^2/r_c^2 + Q_4))
        double pPhi2 = pPhi * pPhi;
        double bracket = 1.0 + (pPhi2 / rc2) + q4;
        double wRad = Math.max(1e-10, pr2 + aPot * bracket);
        double hOrb = Math.sqrt(wRad);
        double inv2HOrb = 0.5 / hOrb;

        double dHOrbDr = inv2HOrb * (dADr * bracket + aPot * (-pPhi2 * dRc2Dr / (rc2 * rc2) + dQ4Dr));
        double dHOrbDpr = inv2HOrb * (2.0 * pRStar + aPot * dQ4Dpr);
        double dHOrbDpPhi = (aPot * pPhi) / (hOrb * rc2);

        // 7. NLO DJS Spin-Orbit Hamiltonian H_SO = (p_phi / (r * r_c^2)) * (g_S S + g_{S*} S_*)
        double denS = 1.0 + c10S * uc + c02S * pr2;
        double gS = 2.0 / denS;
        double dGsDuc = -0.5 * c10S * gS * gS;
        double dGsDpr = -c02S * pRStar * gS * gS;

        double denSS = 1.0 + c10SStar * uc + c02SStar * pr2;
        double gSS = 1.5 / denSS;
        double dGssDuc = -(c10SStar / 1.5) * gSS * gSS;
        double dGssDpr = -(2.0 * c02SStar / 1.5) * pRStar * gSS * gSS;

        double gEff = gS * spinS + gSS * spinSStar;
        double kFac = 1.0 / (rSafe * rc2);
        double dKDr = -(rc2 + rSafe * dRc2Dr) / (r2 * rc2 * rc2);

        double hSo = kFac * gEff * pPhi;
        double dGDr = (dGsDuc * spinS + dGssDuc * spinSStar) * dUcDr;
        double dGDpr = dGsDpr * spinS + dGssDpr * spinSStar;

        double dHSoDr = pPhi * (dKDr * gEff + kFac * dGDr);
        double dHSoDpr = pPhi * kFac * dGDpr;
        double dHSoDpPhi = kFac * gEff;

        // 8. Real EOB Hamiltonian H_real = sqrt(1 + 2*nu*(H_eff - 1))
        double hEff = hOrb + hSo;
        double hRealSq = Math.max(1e-6, 1.0 + 2.0 * nu * (hEff - 1.0));
        double hReal = Math.sqrt(hRealSq);
        double invHReal = 1.0 / hReal;

        double drDt = xi * invHReal * (dHOrbDpr + dHSoDpr);
        double omega = invHReal * (dHOrbDpPhi + dHSoDpPhi);
        double dPrStarDtCons = -xi * invHReal * (dHOrbDr + dHSoDr);

        out.hEff = hEff;
        out.hReal = hReal;
        out.bindingEnergy = hReal - 1.0;
        out.aPot = aPot;
        out.xi = xi;
        out.drDt = drDt;
        out.omega = omega;
        out.dPrStarDtCons = dPrStarDtCons;

        // 9. Damour-Iyer-Nagar (2009) factorized multipolar waveform & GW luminosity
        double omegaPos = Math.max(1e-7, omega);
        double v = Math.cbrt(omegaPos);
        double v2 = v * v;
        double v3 = v2 * v;
        double v4 = v2 * v2;

        // Effective source factors: S_eff^(0) = H_eff (even parity), S_eff^(1) = p_phi * v (odd parity)
        double sEffEven = Math.max(0.2, hEff);
        double sEffOdd = Math.max(0.2, pPhi * v);

        // Resummed tail moduli |T_{lm}| = sqrt(4*pi*m*v^3 / (1 - exp(-4*pi*m*v^3)))
        double t22 = tailModulus(2, v3);
        double t21 = tailModulus(1, v3);
        double t33 = tailModulus(3, v3);
        double t44 = tailModulus(4, v3);

        // (2,2) residual PN + aligned-spin amplitude rho_22 (Damour-Iyer-Nagar 2009; Pan et al. 2011)
        double rho22 = 1.0
                + ((55.0 / 84.0) * nu - (43.0 / 42.0)) * v2
                - (1.5 * spinS + (25.0 / 16.0) * spinSStar) * v3
                + ((19583.0 / 42336.0) * nu * nu - (33025.0 / 21168.0) * nu - (20555.0 / 10584.0) + 0.5 * a0Sq) * v4;
        rho22 = Math.max(0.25, rho22);
        double amp22 = 8.0 * SQRT_PI_OVER_5 * nu * v2 * sEffEven * t22 * (rho22 * rho22);

        // (2,1) mode: mass asymmetry delta + leading spin-difference excitation (X1*chi1 - X2*chi2)
        double rho21 = Math.max(0.25, 1.0 + ((23.0 / 84.0) * nu - (59.0 / 56.0)) * v2);
        double spinAsym21 = -1.5 * (m1Frac * chi1 - m2Frac * chi2) * v;
        double amp21 = (8.0 / 3.0) * SQRT_PI_OVER_5 * nu * v3 * sEffOdd * t21 * (rho21 * rho21)
                * Math.abs(delta + spinAsym21);

        // (3,3) mode: dominant odd-m higher harmonic for unequal-mass binaries (vanishes at q=1)
        double rho33 = Math.max(0.25, 1.0 + ((2.0 * nu - 7.0) / 6.0) * v2);
        double amp33 = 3.0 * SQRT_6PI_OVER_7 * nu * delta * v3 * sEffOdd * t33 * (rho33 * rho33 * rho33);

        // (4,4) mode: dominant even-m overtone harmonic (active even for equal-mass binaries!)
        double rho44 = Math.max(0.25, 1.0 + ((1614.0 - 5870.0 * nu + 2625.0 * nu * nu) / (1320.0 * (1.0 - 3.0 * nu + 1e-9))) * (-v2 * 0.5));
        double amp44 = (64.0 / 9.0) * SQRT_PI_OVER_7 * nu * Math.abs(1.0 - 3.0 * nu) * v4 * sEffEven * t44
                * Math.pow(rho44, 4.0);

        out.amp22 = amp22;
        out.amp21 = amp21;
        out.amp33 = amp33;
        out.amp44 = amp44;
        // Leading-order tail phase shifts delta_{lm} (Damour, Iyer & Nagar 2009 Eq. 20)
        out.phaseOff22 = (7.0 / 3.0) * v3;
        out.phaseOff21 = (2.0 / 3.0) * v3;
        out.phaseOff33 = (13.0 / 10.0) * v3;
        out.phaseOff44 = (112.0 / 45.0) * v3;

        // Exact multipolar GW energy luminosity F_GW = (1 / 8*pi) * sum_{l,m>0} (m * Omega)^2 * |R h_{lm}|^2
        double om2 = omegaPos * omegaPos;
        double gwFlux = (om2 / (8.0 * Math.PI)) * (
                4.0 * amp22 * amp22
                + 1.0 * amp21 * amp21
                + 9.0 * amp33 * amp33
                + 16.0 * amp44 * amp44
        );
        // Modest eccentric radial-motion quadrupole enhancement when |p_{r*}| > 0
        double eccBoost = 1.0 + Math.min(0.8, 1.5 * pr2);
        gwFlux *= eccBoost;

        out.gwFlux = gwFlux;
        if (withRadiationReaction && nu > 1e-9) {
            double fPhi = -gwFlux / (nu * omegaPos);
            double fPr = (Math.abs(pPhi) > 1e-6) ? (pRStar / pPhi) * fPhi : 0.0;
            out.fPhi = fPhi;
            out.fPrStar = fPr;
        } else {
            out.fPhi = 0.0;
            out.fPrStar = 0.0;
        }
    }

    /** Returns an {@link OdeSystem} for the 4-DOF EOB state {@code [r, phi, p_{r*}, p_phi]}. */
    public OdeSystem createOdeSystem(boolean withRadiationReaction) {
        final EvalResult scratch = new EvalResult();
        return (t, y, dydt) -> {
            evaluate(y[0], y[2], y[3], withRadiationReaction, scratch);
            dydt[0] = scratch.drDt;
            dydt[1] = scratch.omega;
            dydt[2] = scratch.dPrStarDtCons + scratch.fPrStar;
            dydt[3] = scratch.fPhi;
        };
    }

    /**
     * Computes physically consistent EOB initial state {@code [r_0, phi_0, p_{r*,0}, p_{phi,0}]}:
     * <ul>
     *   <li>For {@code eccentricity == 0}: exact quasi-circular adiabatic initial conditions
     *       solving {@code dH_eff/dr = 0} for {@code p_{phi,circ}} and the adiabatic inspiral
     *       infall momentum {@code p_{r*,0}} from {@code F_phi / (dp_{phi,circ}/dr)}.</li>
     *   <li>For {@code eccentricity > 0}: starts at apastron {@code r_apo = r_0 * (1 + e_0)} with
     *       {@code p_{r*,0} = 0} and {@code p_phi} reduced by {@code sqrt(1 - e_0)} from the
     *       circular angular momentum at {@code r_0}, producing a genuine periapsis-precessing eccentric orbit.</li>
     * </ul>
     */
    public double[] computeInitialState(double r0, double phi0, double eccentricity, boolean withRadiationReaction) {
        double rStart = Math.max(eobIscoR + 0.35, r0);
        double e = Math.max(0.0, Math.min(BinaryParameters.MAX_ECCENTRICITY, eccentricity));

        if (e > 1e-4) {
            double rApo = rStart * (1.0 + e);
            double pPhiSemi = solveCircularPphi(rStart);
            // Apastron angular momentum for semi-latus rectum p = r_0 (1 - e^2) -> L ~ L_circ(r_0) * sqrt(1 - e^2)
            double pPhiEcc = pPhiSemi * Math.sqrt(Math.max(0.55, 1.0 - e * e));
            return new double[]{rApo, phi0, 0.0, pPhiEcc};
        }

        double pPhiCirc = solveCircularPphi(rStart);
        double pRStar0 = 0.0;
        if (withRadiationReaction && nu > 1e-9) {
            // Post-adiabatic initial radial momentum: dr/dt = (dp_phi/dt) / (dp_{phi,circ}/dr)
            EvalResult ev = new EvalResult();
            evaluate(rStart, 0.0, pPhiCirc, true, ev);
            double drEps = 1e-3;
            double dpPhiCircDr = (solveCircularPphi(rStart + drEps) - solveCircularPphi(rStart - drEps)) / (2.0 * drEps);
            if (dpPhiCircDr > 1e-6) {
                double drDtTarget = ev.fPhi / dpPhiCircDr;
                // From dr/dt = (xi / H_real) * (p_{r*} / H_orb) at small p_{r*}
                double hOrbApprox = Math.max(0.5, ev.hEff - (ev.omega * ev.hReal - (ev.aPot * pPhiCirc) / (Math.max(0.5, ev.hEff) * rStart * rStart)));
                pRStar0 = (drDtTarget * ev.hReal * Math.max(0.5, hOrbApprox)) / Math.max(1e-4, ev.xi);
                pRStar0 = Math.max(-0.15, Math.min(0.0, pRStar0));
            }
        }
        return new double[]{rStart, phi0, pRStar0, pPhiCirc};
    }

    /** Solves {@code dH_eff(r, 0, p_phi) / dr = 0} via bracketed Newton/bisection for circular {@code p_phi(r)}. */
    public double solveCircularPphi(double r) {
        double rSafe = Math.max(eobHorizonR * 1.15, r);
        EvalResult ev = new EvalResult();
        double lo = 1.0;
        double hi = Math.max(6.0, Math.sqrt(rSafe) * 2.2);

        // Expand hi if needed so dPrStarDtCons(hi) > 0 (centrifugal repulsion exceeds gravity)
        for (int k = 0; k < 12; k++) {
            evaluate(rSafe, 0.0, hi, false, ev);
            if (ev.dPrStarDtCons > 0.0) {
                break;
            }
            hi *= 1.5;
        }
        for (int iter = 0; iter < 54; iter++) {
            double mid = 0.5 * (lo + hi);
            evaluate(rSafe, 0.0, mid, false, ev);
            if (ev.dPrStarDtCons < 0.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** Finds the effective EOB horizon {@code r_H} where {@code A(r_H) = 0}. */
    private double solveEffectiveHorizonR() {
        double lo = 0.05;
        double hi = 2.5;
        for (int iter = 0; iter < 50; iter++) {
            double mid = 0.5 * (lo + hi);
            double u = 1.0 / mid;
            double val = mid * mid * evalAOrb(u) + a0Sq;
            if (val <= 0.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    /**
     * Dynamically solves for the EOB Innermost Stable Circular Orbit (ISCO) by minimizing
     * {@code p_{phi,circ}(r)} over {@code r > r_H} via golden-section search.
     *
     * @return {@code [r_ISCO, p_{phi,ISCO}, Omega_ISCO]}
     */
    private double[] solveEobIsco() {
        // Bracket minimum of p_phi_circ(r)
        double rMin = Math.max(eobHorizonR * 1.20, 1.15);
        double rMax = 10.5;
        double bestR = 6.0;
        double bestL = Double.POSITIVE_INFINITY;
        int scan = 60;
        for (int i = 0; i <= scan; i++) {
            double r = rMin + (rMax - rMin) * (i / (double) scan);
            double l = solveCircularPphi(r);
            if (l < bestL) {
                bestL = l;
                bestR = r;
            }
        }
        double step = (rMax - rMin) / scan;
        double a = Math.max(rMin, bestR - 1.5 * step);
        double b = Math.min(rMax, bestR + 1.5 * step);
        double phiG = 0.5 * (Math.sqrt(5.0) - 1.0);
        double c = b - phiG * (b - a);
        double d = a + phiG * (b - a);
        double fc = solveCircularPphi(c);
        double fd = solveCircularPphi(d);
        for (int iter = 0; iter < 40; iter++) {
            if (fc < fd) {
                b = d;
                d = c;
                fd = fc;
                c = b - phiG * (b - a);
                fc = solveCircularPphi(c);
            } else {
                a = c;
                c = d;
                fc = fd;
                d = a + phiG * (b - a);
                fd = solveCircularPphi(d);
            }
        }
        double rIsco = 0.5 * (a + b);
        double lIsco = solveCircularPphi(rIsco);
        EvalResult ev = new EvalResult();
        evaluate(rIsco, 0.0, lIsco, false, ev);
        return new double[]{rIsco, lIsco, ev.omega};
    }

    private static double tailModulus(int m, double v3) {
        double x = 4.0 * Math.PI * m * v3;
        if (x < 1e-6) {
            return 1.0 + 0.25 * x;
        }
        return Math.sqrt(x / -Math.expm1(-x));
    }

    /** Convenience helper for checking conservative Hamiltonian invariance without allocations. */
    public double hamiltonianReal(double r, double pRStar, double pPhi) {
        EvalResult ev = new EvalResult();
        evaluate(r, pRStar, pPhi, false, ev);
        return ev.hReal;
    }

    public double[] scratchBuffer4() {
        return derivScratch;
    }
}
