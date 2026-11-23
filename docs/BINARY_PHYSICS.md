# BlackHole 3.0 — Binary Black Hole Physics & Validation Reference

This document specifies the exact mathematical models, numerical integrators, empirical Numerical Relativity (NR) fitting formulas, detector transformations, effective spacetime approximations, and independent validation tests implemented in BlackHole 3.0's Binary Black Hole (BBH) subsystem.

> **Scientific Honesty Standard:** Every component is explicitly classified below as **(E) Exact GR Solution**, **(A) Analytical / Post-Newtonian / EOB Approximation**, **(F) Empirical NR Fit**, or **(V) Visualization Model**.

---

## 1. Units & Conventions

All internal orbital dynamics and GPU ray tracing operate in geometric units $G = c = M_{\text{tot}} = 1$, where:

$$M_{\text{tot}} = M_1 + M_2, \qquad m_1 = \frac{M_1}{M_{\text{tot}}}, \qquad m_2 = \frac{M_2}{M_{\text{tot}}}, \qquad q = \frac{M_2}{M_1} \in [0.05,\, 1.0], \qquad \nu \equiv \eta = \frac{M_1 M_2}{M_{\text{tot}}^2} = \frac{q}{(1+q)^2} \in (0,\, 0.25].$$

Dimensionless aligned component spins satisfy $\chi_i = \mathbf{S}_i \cdot \hat{\mathbf{L}} / M_i^2 \in [-0.98,\, +0.98]$. Conversion to physical SI units uses the IAU 2015 nominal solar mass parameter $GM_\odot = 1.3271244 \times 10^{20}\ \mathrm{m^3\,s^{-2}}$ and $c = 299{,}792{,}458\ \mathrm{m\,s^{-1}}$:

$$t_g = \frac{G M_{\text{tot}}}{c^3}, \qquad r_g = \frac{G M_{\text{tot}}}{c^2}.$$

Source-frame mass $M_{\text{src}}$ and detector-frame (redshifted) mass $M_{\text{det}}$ are related by:

$$M_{\text{det}} = (1 + z)\, M_{\text{src}},$$

where cosmological redshift $z(D_L)$ is solved by Newton inversion of the flat $\Lambda$CDM luminosity distance integral (`BinaryParameters.redshiftFromLuminosityDistanceMpc`) with Planck 2018 parameters ($H_0 = 67.4\ \mathrm{km\,s^{-1}\,Mpc^{-1}}$, $\Omega_m = 0.315$, $\Omega_\Lambda = 0.685$):

$$D_L(z) = (1+z)\,\frac{c}{H_0}\int_0^z \frac{dz'}{\sqrt{\Omega_m(1+z')^3 + \Omega_\Lambda}}.$$

---

## 2. Effective-One-Body (EOB) Orbital Dynamics — **(A)**

Implemented in `math/EobBinaryDynamics.java` and integrated in `core/BinaryMergerPhysics.java` using an adaptive 5th-order embedded **Dormand–Prince 5(4)** ODE solver (`math/DormandPrince54Integrator.java`, $\text{rtol} = 10^{-8}$, $\text{atol} = 10^{-10}$, deterministic frame-rate-independent stepping).

### 2.1 Conservative Spinning EOB Hamiltonian

The relative binary motion is evolved in 4-DOF tortoise-momentum phase space $(r, \phi, p_{r_*}, p_\phi)$ with reduced effective mass $\mu = \nu M_{\text{tot}}$:

$$H_{\text{EOB}} = \frac{1}{\nu}\sqrt{1 + 2\nu(\hat{H}_{\text{eff}} - 1)} - \frac{1}{\nu}, \qquad \hat{H}_{\text{eff}} = \hat{H}_{\text{orb}} + \hat{H}_{\text{SO}}.$$

**Orbital Effective Hamiltonian:**
Using the Kerr centrifugal radius $r_c^2 = r^2 + a_0^2(1 + 2/r)$ (Damour & Nagar 2014) with effective Kerr spin $a_0 = m_1 \chi_1 + m_2 \chi_2$ and $u = 1/r$:

$$\hat{H}_{\text{orb}} = \sqrt{A(r)\left(1 + \frac{p_\phi^2}{r_c^2} + 2\nu(4 - 3\nu)\,u^2 p_{r_*}^4\right) + p_{r_*}^2},$$

where the 3PN orbital radial potential is Padé-resummed via $P^1_3[A_{\text{3PN}}](u)$ (Damour, Jaranowski & Schäfer 2000):

$$A_{\text{orb}}(u) = P^1_3\Big[1 - 2u + 2\nu u^3 + \nu\Big(\tfrac{94}{3} - \tfrac{41}{32}\pi^2\Big)u^4\Big], \qquad A(r) = \frac{r^2 A_{\text{orb}}(u) + a_0^2}{r_c^2}.$$

In the test-particle limit ($\nu \to 0$), $A_{\text{orb}}(u) = 1 - 2u$ and $A(r)$ reduces **identically** to the exact equatorial Kerr radial potential $\Delta / (r^2 + a_0^2 + 2 a_0^2 / r)$.

The 3PN metric potential $D(r)$ is Padé-resummed as $D^{-1}(u) = 1 + 6\nu u^2 + 2\nu(26 - 3\nu) u^3$, yielding the tortoise radial factor:

$$\xi(r) = \frac{dr}{dr_*} = \frac{r^2}{r_c^2}\, A(r)\sqrt{D(r)}.$$

**Spin–Orbit Coupling (NLO DJS Gauge):**
Following Damour, Jaranowski & Schäfer (2008) and Damour & Nagar (2014), with $S = m_1^2 \chi_1 + m_2^2 \chi_2$, $S^* = m_1 m_2 (\chi_1 + \chi_2)$, and $u_c = 1/r_c$:

$$\hat{H}_{\text{SO}} = p_\phi\, u_c^3 \Big( g_S(u_c)\, S + g_{S^*}(u_c)\, S^* \Big),$$

$$g_S = 2\left(1 + \frac{3}{8}\nu u_c\right)^{-1}, \qquad g_{S^*} = \frac{3}{2}\left(1 + \frac{5}{4}u_c + \frac{1}{2}\nu u_c\right)^{-1}.$$

**Canonical Hamilton Equations with Radiation Reaction:**

$$\frac{dr}{dt} = \xi(r)\,\frac{\partial H_{\text{EOB}}}{\partial p_{r_*}}, \qquad \frac{d\phi}{dt} = \frac{\partial H_{\text{EOB}}}{\partial p_\phi}, \qquad \frac{dp_{r_*}}{dt} = -\xi(r)\,\frac{\partial H_{\text{EOB}}}{\partial r} + \hat{\mathcal{F}}_{r_*}, \qquad \frac{dp_\phi}{dt} = \hat{\mathcal{F}}_\phi.$$

All partial derivatives $\partial H_{\text{EOB}} / \partial (r, p_{r_*}, p_\phi)$ are evaluated **analytically** via exact chain rules (no finite-difference noise).

### 2.2 Initial Conditions, Dynamical Plunge & Merger Attachment

- **Quasi-circular ($e_0 = 0$):** Post-adiabatic initial state solving $\partial H_{\text{EOB}}/\partial r = 0$ for $p_{\phi,0}(r_0)$ via bisection, followed by the analytical radial inspiral drift $p_{r_*,0} = (\hat{\mathcal{F}}_\phi / (\xi\, dp_\phi/dr))\, (\partial H_{\text{EOB}}/\partial p_{r_*})^{-1}$.
- **Eccentric ($0 < e_0 \le 0.45$):** Apastron release at $r_{\text{apo}} = r_0$, $p_{r_*,0} = 0$, with $p_{\phi,0} = p_{\phi,\text{circ}}(r_0)\sqrt{1 - e_0}$ (clamped above the dynamical plunge separatrix).
- **No Arbitrary Separation Thresholds:**
  - The **EOB ISCO** $r_{\text{ISCO}}$ is solved dynamically for the current $(\nu, \chi_1, \chi_2)$ by locating the inflection point of the circular-orbit effective potential $d p_{\phi,\text{circ}}/dr = 0$. Crossing $r < r_{\text{ISCO}}$ marks the physical **Inspiral → Plunge** transition.
  - **Merger / Coalescence Attachment ($t_{\text{merger}}$):** Determined dynamically during the plunge when the orbital angular velocity $\Omega(t) = d\phi/dt$ attains its strong-field peak ($d\Omega/dt$ changes sign from positive to negative, corresponding to the EOB light-ring / photon-sphere peak), or if the binary reaches the dynamical EOB horizon root $r_H$ where $A(r_H) = 0$.

---

## 3. Multipolar Gravitational Waveform & Radiation Reaction — **(A)+(F)**

Implemented in `math/EobBinaryDynamics.java`, `core/BinaryMergerPhysics.java`, and `math/GravitationalWaveDetector.java`.

### 3.1 Factorized Inspiral–Plunge Multipoles (Damour, Iyer & Nagar 2009)

For each mode $(\ell, m) \in \{(2,2), (2,1), (3,3), (4,4)\}$, the dimensionless strain mode $h_{\ell m}$ (normalized by $D_L / M_{\text{tot}}$) is constructed in factorized form using $v_\Omega = \Omega^{1/3}$ and $x = v_\Omega^2$:

$$h_{\ell m} = h_{\ell m}^{(N,\epsilon)}\, \hat{S}_{\text{eff}}^{(\epsilon)}\, |T_{\ell m}|\, \left(\rho_{\ell m}\right)^\ell e^{-i m \phi},$$

with:
- Leading Newtonian prefactor $h_{\ell m}^{(N,\epsilon)}$ proportional to $\nu$ (and asymmetric mass-difference factor $\delta = m_1 - m_2$ for odd-$m$ modes $(2,1)$ and $(3,3)$, vanishing identically for equal-mass non-spinning binaries by symmetry).
- Effective source $\hat{S}_{\text{eff}} = \hat{H}_{\text{eff}}$ (even parity) or $\hat{S}_{\text{eff}} = p_\phi v_\Omega / r$ (odd parity).
- Tail resummation modulus $|T_{\ell m}| = \sqrt{\frac{2\pi m \hat{k}}{1 - e^{-2\pi m \hat{k}}}}$, $\hat{k} = H_{\text{EOB}}\Omega$.
- 3PN orbital + NLO aligned-spin residual amplitudes $\rho_{\ell m}(x; \nu, \chi_S, \chi_A)$ (Damour, Iyer & Nagar 2009; Pan et al. 2011).

The azimuthal radiation-reaction flux sums all active multipoles (accounting for $\pm m$ symmetry):

$$\mathcal{F}_\phi = -\frac{1}{8\pi \Omega} \sum_{(\ell,m)} (m\Omega)^2\, |h_{\ell m}|^2, \qquad \hat{\mathcal{F}}_\phi = \frac{\mathcal{F}_\phi}{\nu}, \qquad \hat{\mathcal{F}}_{r_*} = \hat{\mathcal{F}}_\phi \frac{p_{r_*}}{p_\phi}.$$

### 3.2 $C^1$-Continuous Merger–Ringdown Bridge — **(A)+(F)**

At $t = t_{\text{merger}}$, the $(2,2)$ mode transitions continuously in both amplitude ($C^1$) and frequency ($C^1$) to the damped fundamental Kerr QNM $(\ell=2, m=2, n=0)$ over geometric time $\Delta t = t - t_{\text{merger}} \ge 0$:

$$\omega_{22}(\Delta t) = \omega_{220} + (\omega_{\text{peak}} - \omega_{220})\, e^{-\Delta t / \tau_\omega}, \qquad \Phi_{22}(\Delta t) = \Phi_{\text{peak}} + \omega_{220}\Delta t + (\omega_{\text{peak}} - \omega_{220})\tau_\omega\left(1 - e^{-\Delta t / \tau_\omega}\right),$$

$$A_{22}(\Delta t) = A_{\text{peak}}\, \frac{1 + c_1 \Delta t}{1 + \alpha_{\text{damp}} \Delta t + \dots} \;\longrightarrow\; A_{\text{peak}}\, e^{-\Delta t / \tau_{220}}\, \left(1 + \frac{\Delta t}{\tau_{220}} \text{ blended smoothly with } \dot{A}(0^+)=0\right),$$

specifically using the $C^1$ smooth envelope $E(\Delta t) = e^{-\Delta t/\tau_{220}} \left(1 + \frac{\Delta t}{\tau_{220}} e^{-\Delta t / (1.5 \tau_{220})}\right)$ which satisfies $E(0) = 1$, $E'(0) = 0$, and asymptotically decays as $e^{-\Delta t/\tau_{220}}$ with exact Berti–Cardoso–Will damping time $\tau_{220}$. Higher modes $(2,1), (3,3), (4,4)$ decay at their respective fundamental QNM frequencies $\omega_{\ell m 0}$ and damping times $\tau_{\ell m 0}$.

---

## 4. Remnant Black Hole NR Fitting Formulas — **(F)**

Implemented in `math/BinaryRemnantModel.java`. **Never uses non-spinning fits for spinning binaries.**

### 4.1 Final Dimensionless Spin $\chi_f$ (Barausse & Rezzolla 2009)
*Reference:* Barausse & Rezzolla, *ApJ* **704**, L40 (2009), Eqs. (6)–(10).  
*Calibration domain:* $\nu \in (0, 0.25]$, aligned/anti-aligned spins $\chi_1, \chi_2 \in [-1, 1]$.

$$\chi_f = \tilde{a} + \nu\left(\tilde{a}\,(t_2 \nu + t_3 \nu^2) + s_4 \tilde{a}^2 + s_5 \nu \tilde{a} + 2\sqrt{3} + t_0 \nu\right), \qquad \tilde{a} = \frac{\chi_1 + q^2 \chi_2}{(1+q)^2},$$

with published NR-calibrated coefficients $s_4 = -0.1229$, $s_5 = 0.4537$, $t_0 = -2.8904$, $t_2 = -3.5171$, $t_3 = 2.5763$.

### 4.2 Radiated Energy $E_{\text{rad}}/M$ & Final Mass $M_f$ (Barausse, Morozova & Rezzolla 2012)
*Reference:* Barausse, Morozova & Rezzolla, *ApJ* **758**, 63 (2012), Eqs. (18)–(19).  
*Calibration domain:* Quasi-circular binaries, $\nu \in (0, 0.25]$, aligned/anti-aligned spins $\chi_1, \chi_2 \in [-1, 1]$.

$$\frac{E_{\text{rad}}}{M} = \Big[1 - \tilde{E}_{\text{ISCO}}(\tilde{a})\Big]\nu + 4\nu^2\left[4 p_0 + 16 p_1 \tilde{a}(\tilde{a} + 1) + \tilde{E}_{\text{ISCO}}(\tilde{a}) - 1\right],$$

where $p_0 = 0.04827$, $p_1 = 0.01707$, and $\tilde{E}_{\text{ISCO}}(\tilde{a}) = \sqrt{1 - \frac{2}{3 r_{\text{ISCO}}(\tilde{a})}}$ uses the **exact** Bardeen–Press–Teukolsky (1972) Kerr ISCO radius $r_{\text{ISCO}}(\tilde{a})$ (prograde for $\tilde{a} \ge 0$, retrograde for $\tilde{a} < 0$). Final mass is $M_f = M_{\text{tot}} - E_{\text{rad}}$, and radiated angular momentum is $J_{\text{rad}} = L_{\text{init}} + S_1 + S_2 - M_f^2 \chi_f$.

### 4.3 Fundamental Kerr QNM Frequencies & Damping Times (Berti, Cardoso & Will 2006)
*Reference:* Berti, Cardoso & Will, *Phys. Rev. D* **73**, 064030 (2006), Table VIII.  
*Calibration domain:* $|\chi_f| \in [0, 0.998]$, fundamental overtone $n = 0$.

$$M_f \omega_{\ell m 0} = f_1 + f_2 (1 - |\chi_f|)^{f_3}, \qquad Q_{\ell m 0} = q_1 + q_2 (1 - |\chi_f|)^{q_3}, \qquad \frac{\tau_{\ell m 0}}{M_f} = \frac{2 Q_{\ell m 0}}{M_f \omega_{\ell m 0}},$$

implemented for $(2,2,0)$, $(2,1,0)$, $(3,3,0)$, and $(4,4,0)$ using exact Table VIII coefficients.

---

## 5. Real Gravitational-Wave Detector Signal — **(E)+(A)**

Implemented in `math/GravitationalWaveDetector.java` — strictly separated from normalized visualization amplitudes (`uStrainAmp`).

### 5.1 Spin-Weighted Spherical Harmonics ${}_{-2}Y_{\ell m}(\iota_{\text{obs}}, 0)$ — **(E)**
Exact closed-form spin-weight $s = -2$ spherical harmonics combine the complex modes $h_{\ell m}$ (including conjugate $\pm m$ symmetry $h_{\ell,-m} = (-1)^\ell h_{\ell m}^*$ for non-precessing binaries):

$$h_+ - i h_\times = \frac{G M_{\text{det}}}{c^2 D_L} \sum_{\ell, m} \Big( h_{\ell m}\,{}_{-2}Y_{\ell m}(\iota_{\text{obs}}, 0) + (-1)^\ell h_{\ell m}^*\,{}_{-2}Y_{\ell,-m}(\iota_{\text{obs}}, 0) \Big).$$

### 5.2 Interferometer Antenna Response — **(E)**
Following Jaranowski, Królak & Schutz (*PRD* **58**, 063001, 1998) for sky position $(\theta, \phi)$ and polarization angle $\psi$:

$$F_+(\theta, \phi, \psi) = \frac{1}{2}(1 + \cos^2\theta)\cos 2\phi \cos 2\psi - \cos\theta \sin 2\phi \sin 2\psi,$$
$$F_\times(\theta, \phi, \psi) = \frac{1}{2}(1 + \cos^2\theta)\cos 2\phi \sin 2\psi + \cos\theta \sin 2\phi \cos 2\psi,$$
$$h_{\text{det}}(t) = F_+ h_+(t) + F_\times h_\times(t).$$

Supported detector presets: `OPTIMAL` ($F_+=1, F_\times=0$), `LIGO H1`, `LIGO L1`, and `VIRGO V1`.

---

## 6. Binary Spacetime & GPU Ray Tracing — **(A)+(V)**

Implemented identically in double precision on the CPU (`math/BinarySpacetimeMetric.java`) and single precision on the GPU (`shaders/simulation/binary_merger.frag`, compiled as specialized `RK4` and `DOPRI5(4)` OpenGL 4.3 programs).

### 6.1 Effective Binary Null Hamiltonian — **(A)**

In the orthonormal orbital frame $(\hat{\mathbf{e}}_1, \hat{\mathbf{L}}, \hat{\mathbf{e}}_3)$ (pre-rotated once per pixel outside the ray loop):

$$\mathcal{H}_{\text{bin}}(\mathbf{x}, \mathbf{p}; p_t) = \frac{1}{2}\Bigg[-p_t^2 + |\mathbf{p}|^2 - 2(1-w_m)\sum_{i=1}^2 H_i(\mathbf{x}_i)\Big((-p_t + \mathbf{l}_i\cdot\mathbf{p})^2 + 2 p_t (\mathbf{v}_i\cdot\mathbf{p})\Big) - 2 w_m H_{\text{rem}}(\mathbf{x})(-p_t + \mathbf{l}_{\text{rem}}\cdot\mathbf{p})^2\Bigg] = 0,$$

where each body $i \in \{1,2\}$ carries its **exact spinning Kerr–Schild spheroidal radius** $r_i$ (physical spin $a_i = m_i \chi_i$), exact Kerr scalar $H_i = m_i r_i^3 / (r_i^4 + a_i^2 y_i^2)$, unit null vector $|\mathbf{l}_i| = 1$, and linear 1PN gravitomagnetic orbital shift $-2 H_i p_t \mathbf{v}_i$ in $d\mathbf{x}/d\lambda$ (dragging forward-in-time ZAMOs along $+\mathbf{v}_i$). The remnant uses exact physical spin $a_f = M_f \chi_f$ and an $(\ell=2, m=2)$ QNM perturbation with exact analytical spatial gradient.

### 6.2 Explicit Validity Boundaries & Classification

| Property | Classification | Status |
|---|---|---|
| Isolated single-body limit ($m_2 = 0, \mathbf{v}_1 = 0$) | **(E) Exact GR** | Reduces identically ($<10^{-13}$) to exact spinning Kerr–Schild metric |
| Post-merger stationary limit ($w_m = 1$, ringdown decayed) | **(E) Exact GR** | Reduces identically ($<10^{-13}$) to exact Kerr–Schild remnant ($M_f, a_f = M_f \chi_f$) |
| Canonical null conservation $d\mathcal{H}_{\text{bin}}/d\lambda = 0$ | **(E) Exact Identity** | Analytical metric & QNM gradients; verified $|\mathcal{H}_{\text{bin}}| < 10^{-10}$ in double precision |
| Component horizons $r_{+,i} = m_i(1 + \sqrt{1-\chi_i^2})$ | **(A) Frozen-Slice Regular** | Linear 1PN shift avoids unphysical frozen-slice boost Killing walls |
| Strong-field binary overlap ($r_{12} \lesssim 5M$) | **(A) Analytical Effective Metric** | **NOT** a 3+1 Numerical Relativity solution of $R_{\mu\nu} = 0$; omits nonlinear binding cross-terms & retarded strong-field metric back-reaction |
| Circumbinary disk, mini-disks, tidal bridge, `[G]` GW ripples | **(V) Visualization Model** | Procedural relativistic plasma radiative transfer & retarded quadrupole visual overlay; clearly distinct from SI detector strain $h_{\text{det}}$ |

---

## 7. Independent Scientific Validation (`mvn -B test`)

49 headless JUnit 5 tests validate all physics subsystems, including 14 dedicated Binary v3.0 tests (`BinaryMergerValidationTest` & `BinarySpacetimeValidationTest`):

1. **SXS Numerical Relativity Catalog Comparison (`BinaryMergerValidationTest`):**
   Validates `BinaryRemnantModel` final spin $\chi_f$ and final mass $M_f$ against 7 published SXS waveforms (`SXS:BBH:0180`, `0152`, `0157`, `0154`, `0169`, `0168`, `0305` — covering $q = 1, 0.82, 0.33, 0.25$ and spins $\chi \in [-0.80, +0.80]$), agreeing to $|\Delta \chi_f| \lesssim 0.012$ and $|\Delta M_f/M| \lesssim 0.008$.
2. **Exact Chandrasekhar / Leaver (1985) Schwarzschild QNM Verification:**
   Verifies fundamental QNM frequencies and damping rates for $(2,2,0)$, $(2,1,0)$, $(3,3,0)$, $(4,4,0)$ against exact perturbation-theory benchmarks ($M\omega_{220} = 0.37367$, $M/\tau_{220} = 0.08896$).
3. **EOB Orbital Physics & Conservation:**
   Tests equal-mass symmetry, unequal-mass COM balance ($m_1 \mathbf{x}_1 + m_2 \mathbf{x}_2 = 0$), exact Kerr ISCO test-particle limit ($r_{\text{ISCO}} \to 6M$ as $\nu \to 0, \chi = 0$), spin-induced orbital hang-up (aligned $\chi = +0.75$ spirals tighter & lasts longer than anti-aligned $\chi = -0.75$), monotonic energy & angular-momentum radiation-reaction balance ($dE/dt < 0$, $dL/dt < 0$), and monotonic DOPRI5(4) tolerance convergence ($10^{-3} \to 10^{-6} \to 10^{-9}$).
4. **Waveform Continuity, Polarizations & GW150914 Physical Detector Check:**
   Verifies $C^1$ phase & amplitude continuity across merger attachment, $(2,2)$ vs higher-mode symmetry (odd-$m$ modes vanish for equal-mass non-spinning binaries and activate for unequal masses / asymmetric spins), face-on circular polarization ($|h_+| = |h_\times|$) vs edge-on linear polarization ($h_\times = 0$ for $(2,2)$), and GW150914 SI detector strain ($\sim 10^{-21}$) and redshifted ringdown frequency ($\approx 246\ \mathrm{Hz}$ vs Abbott et al. 2016 $251\ \mathrm{Hz}$).
5. **GPU-Mirrored Binary Spacetime & Convergence (`BinarySpacetimeValidationTest`):**
   Verifies $|\mathbf{l}_i|^2 = 1$ across spins and orbital inclinations, forward-in-time ZAMO gravitomagnetic frame dragging along $+\mathbf{v}_i$, exact $<10^{-13}$ reduction to `KerrMetric`, $|\mathcal{H}_{\text{bin}}| < 10^{-10}$ during active spinning inspiral & QNM ringdown, exact 4th-order RK4 convergence ($\approx 16\times$ error reduction on halving $h$), spinning horizon capture, and `binary_merger.frag` uniform contract.

---

## 8. Key Scientific References

1. Buonanno, A. & Damour, T. (1999), *Effective one-body approach to general relativistic two-body dynamics*, Phys. Rev. D **59**, 084006.
2. Damour, T., Jaranowski, P. & Schäfer, G. (2000), *Determination of the last stable orbit for circular general relativistic binaries at the third post-Newtonian approximation*, Phys. Rev. D **62**, 084011.
3. Damour, T., Jaranowski, P. & Schäfer, G. (2008), *Effective one body approach to the dynamics of two spinning black holes with next-to-leading order spin-orbit coupling*, Phys. Rev. D **78**, 024009.
4. Damour, T., Iyer, B. R. & Nagar, A. (2009), *Improved resummation of post-Newtonian multipolar waveforms from circularized compact binaries*, Phys. Rev. D **79**, 064004.
5. Damour, T. & Nagar, A. (2014), *New effective-one-body gravitational waveform model for coalescing, compact, equivalent-mass binaries*, Phys. Rev. D **90**, 044018.
6. Barausse, E. & Rezzolla, L. (2009), *Predicting the direction of the final spin from the coalescence of two black holes*, ApJ **704**, L40.
7. Barausse, E., Morozova, V. & Rezzolla, L. (2012), *On the mass radiated by coalescing black-hole binaries*, ApJ **758**, 63.
8. Berti, E., Cardoso, V. & Will, C. M. (2006), *On gravitational-wave spectroscopy of massive black holes with the space interferometer LISA*, Phys. Rev. D **73**, 064030.
9. Bardeen, J. M., Press, W. H. & Teukolsky, S. A. (1972), *Rotating Black Holes: Locally Nonrotating Frames, Energy Extraction, and Scalar Synchrotron Radiation*, ApJ **178**, 347.
10. Jaranowski, P., Królak, A. & Schutz, B. F. (1998), *Data analysis of gravitational-wave signals from spinning neutron stars*, Phys. Rev. D **58**, 063001.
11. Abbott, B. P. et al. (LIGO Scientific & Virgo Collaborations) (2016), *Observation of Gravitational Waves from a Binary Black Hole Merger* (GW150914), Phys. Rev. Lett. **116**, 061102.
12. Boyle, M. et al. (SXS Collaboration) (2019), *The SXS Collaboration catalog of binary black hole simulations*, Class. Quant. Grav. **36**, 195006.
