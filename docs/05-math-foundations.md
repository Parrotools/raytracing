# 05 — Mathematical Foundations

This document derives every estimator implemented in the kernels, so the shader code
can be reviewed against the math line by line.

## 1. The rendering equation and its path-traced estimator

Outgoing radiance at surface point $x$ toward direction $\omega_o$:

$$
L_o(x,\omega_o) = L_e(x,\omega_o) + \int_{\Omega} f_r(x,\omega_i,\omega_o)\,
L_i(x,\omega_i)\,\langle n\cdot\omega_i\rangle \,\mathrm{d}\omega_i
$$

We estimate the integral by Monte Carlo with importance sampling; for one sample
direction $\omega_i \sim p(\omega_i)$:

$$
\hat L_o = L_e + \frac{f_r(\omega_i,\omega_o)\,\langle n\cdot\omega_i\rangle}
{p(\omega_i)}\, \hat L_i(\omega_i)
$$

The path is extended recursively (iteratively in the kernel, carrying a throughput
$\beta_k = \prod_j \frac{f_r \langle n\cdot\omega\rangle}{p}$). **Russian roulette**
after bounce 1: survive with $q=\min(1, \max(\beta_r,\beta_g,\beta_b))$ and divide
$\beta$ by $q$ — unbiased termination.

## 2. Direct lighting: next-event estimation (NEE)

At every diffuse/rough path vertex we explicitly sample the two light families and
add their unshadowed-probability-weighted contribution. The BRDF bounce then must
**not** re-add radiance from those same lights when hit by chance, or energy is
counted twice; we implement the standard split:

* Sun and NEE-sampled emissive voxels contribute **only** via NEE on diffuse
  vertices.
* Bounce rays add emission only when the vertex was reached by a **specular/refracted**
  continuation (delta-ish lobes can't be NEE'd) or on the **primary** hit (so you can
  *see* lava/glowstone glow directly).

This is MIS-by-partition (a valid, cheaper alternative to balance-heuristic MIS for
mostly-diffuse scenes; full MIS is roadmap).

### 2.1 Sun with angular radius (soft shadows)

The sun is a disc of angular radius $\theta_s$ (config, default 1.5°) around
direction $\omega_s$. Uniform solid-angle cone sampling:

$$
\cos\theta = 1 - \xi_1 (1-\cos\theta_s),\qquad \phi = 2\pi\xi_2
$$

pdf $= \frac{1}{2\pi(1-\cos\theta_s)}$. Because we treat the sun's *average radiance*
$L_{sun}$ as constant over the cone, the estimator collapses to
$f_r\,\langle n\cdot\omega\rangle\,L_{sun}\cdot V(\omega)$ with $V$ the shadow-ray
visibility — the penumbra emerges from the Monte Carlo average of $V$ over the cone,
temporally accumulated.

### 2.2 Emissive voxels (per-section light lists)

For light $k$ at position $p_k$ (voxel centre) with radiant intensity $I_k$ (RGB),
sampled with probability $P_k$ from the candidate set (the 3³ neighbouring sections'
lists), the point-light approximation of a small emitter gives:

$$
L_{dir} = \frac{f_r\,\langle n\cdot\omega_k\rangle\, I_k\, V(x\!\to\!p_k)}
{P_k\, \|p_k - x\|^2}
$$

$P_k$ is importance-proportional: $P_k \propto \frac{\max(I_k)}{\|p_k-x\|^2}$,
normalized over the candidate set (weighted reservoir selection of 1 light, then one
shadow ray). Distance is clamped below by the voxel radius (0.5 m) to bound the
$1/r^2$ singularity.

## 3. BRDF model and importance sampling

A metalness-workflow BRDF (Cook–Torrance GGX + Lambert):

$$
f_r = (1-m)\,\frac{\rho}{\pi}\,(1-F) \;+\; \frac{D(h)\,G(\omega_i,\omega_o)\,F(h)}
{4\,\langle n\cdot\omega_i\rangle\langle n\cdot\omega_o\rangle}
$$

* $D$: GGX/Trowbridge-Reitz with $\alpha = r^2$ (perceptual roughness $r$).
* $G$: Smith height-correlated (Heitz 2014 form, implemented via the visibility
  function $V = \frac{G}{4\langle n\cdot\omega_i\rangle\langle n\cdot\omega_o\rangle}$).
* $F$: Schlick, $F_0 = \mathrm{mix}(0.04,\ \rho,\ m)$ (dielectric 4% ↔ metal albedo).

**Lobe selection** per vertex: specular with probability
$P_{spec} = \mathrm{luma}(F(\langle n\cdot\omega_o\rangle))$ (clamped to [0.05, 0.9]
for non-metals, 1 for metals with low roughness), else diffuse; throughput divided by
the chosen lobe probability.

* Diffuse: cosine-weighted hemisphere, $p = \frac{\langle n\cdot\omega\rangle}{\pi}$,
  so $\frac{f_r\langle n\cdot\omega\rangle}{p} = \rho\, (1-F)(1-m)$ — the classic
  albedo-only throughput multiply.
* Specular: sample the GGX half-vector via the VNDF (Heitz 2018):
  $\frac{f_r\langle n\cdot\omega\rangle}{p_{VNDF}} = F\cdot
  \frac{G_2}{G_1}$ — implemented exactly in `brdf.glsl`.

### 3.1 Refraction

Transmissive materials (water, glass, ice) use Fresnel-weighted selection between
reflection and refraction (Snell's law with material IOR $\eta$; total internal
reflection handled by the discriminant). Throughput is multiplied by the material
tint. Radiance carried across media boundaries uses the convention that we do **not**
apply the $\eta^2$ radiance scaling (symmetric transport, camera measures radiance in
its own medium — standard for whitted-style game tracers).

## 4. Voxel DDA traversal (Amanatides & Woo, three levels)

For a ray $o + t d$ in a unit grid, per axis: $t_{max,a}$ = distance to the first
boundary crossing of axis $a$, $\Delta t_a = \frac{1}{|d_a|}$. Step the axis with the
smallest $t_{max}$; on each step the crossed-boundary distance gives exact hit $t$
and the face normal is the negated step axis.

Hierarchy: section level (cell = 16 m) consults the section table; inside a
non-empty section, cell level (4 m) tests the 64-bit occupancy mask word
(`uvec2`, since GLSL 4.3 lacks guaranteed int64); only occupied 4³ cells descend to
voxel level. Empty space advances at 16 m or 4 m per iteration, so typical terrain
frames traverse < 40 iterations per primary ray at 8-section radius.

Ray–AABB entry uses the slab test; rays are clipped to the grid bounds, and
UNLOADED slots terminate into the sky (documented approximation).

## 5. Demodulated irradiance and why the denoiser needs it

Filtered quantity: $\hat I = \frac{L - L_e^{(1)}}{\max(\rho^{(1)},\varepsilon)}$
(first-hit emission removed, first-hit albedo divided out). Albedo is a
high-frequency *deterministic* signal — filtering it would smear block borders;
demodulation lets the filter smooth only the *stochastic* lighting signal, and
composite re-multiplies: $L_{final}=\hat I_{filtered}\cdot \rho^{(1)} + L_e^{(1)}$.

## 6. Temporal accumulation (reprojection)

World-space hit point from the G-buffer: $P = c + t\,d$ (grid-local). Previous-frame
NDC: $q = \mathrm{VP}_{prev}\,(P - \Delta g)$ where $\Delta g$ re-expresses $P$ in
the previous grid frame (CPU supplies $\mathrm{VP}_{prev}$ already shifted, §5 of the
design doc). History is fetched with manual bilinear taps; each tap is **validated**:

$$
w_{tap} = w_{bilin}\cdot
\big[\,|z_{prev} - z_{exp}| < \sigma_z\, z_{exp}\,\big]\cdot
\big[\, n_{prev}\!\cdot\! n > 0.8 \,\big]
$$

If $\sum w = 0$ → disocclusion → history length resets. Exponential moving average
with $\alpha = \max(\alpha_{min}, \frac{1}{h+1})$, history length $h$ clamped
(default 32). Luminance moments $\mu_1,\mu_2$ accumulate identically; variance
$\sigma^2 = \mu_2 - \mu_1^2$, inflated for short histories (SVGF §4.2).

## 7. À-trous wavelet filtering (SVGF)

$N$ iterations (default 3) of the edge-avoiding à-trous transform with kernel
$(\frac{1}{16},\frac{1}{4},\frac{3}{8},\frac{1}{4},\frac{1}{16})$ per axis and step
$2^{i}$. Weight between centre $p$ and tap $q$:

$$
w(p,q) = \underbrace{e^{-\frac{|z_p - z_q|}{\sigma_z\,s}}}_{depth}\cdot
\underbrace{\max(0, n_p\!\cdot\!n_q)^{\sigma_n}}_{normal}\cdot
\underbrace{e^{-\frac{|l_p - l_q|}{\sigma_l\sqrt{\mathrm{Var}_p}+\varepsilon}}}_{luminance}
$$

($\sigma_z{=}1$, $\sigma_n{=}128$, $\sigma_l{=}4$.) Variance is filtered with the
squared weights, giving the next iteration its own noise estimate. The first
iteration's output also feeds back as the temporal history for the next frame
(SVGF's "feedback tap") — implemented by writing iteration 1 to the accumulation
buffer.

## 8. Tonemapping & sRGB

ACES filmic fit (Narkowicz 2015):
$T(x) = \frac{x(2.51x + 0.03)}{x(2.43x+0.59)+0.14}$ applied per channel after
exposure; then sRGB OETF. Output is written to an RGBA8 UNORM image and blitted.

## 9. Random numbers

Per-pixel, per-frame decorrelated PCG hash (`pcg3d`, Jarzynski & Olano 2020) seeded
by (pixel, frameIndex); dimensions drawn sequentially. White noise + heavy temporal
accumulation is acceptable at 1 spp; blue-noise/Sobol is a roadmap upgrade with
measured benefit mainly at very low accumulation.

## 10. Sky model

Analytic, cheap, plausible (full Hosek-Wilkie is roadmap):
zenith/horizon gradient with sun-elevation-driven Rayleigh tint, Mie-like forward
glow $\propto (1+\cos\theta)^8$ around the sun, disc limb, night-side moon disc +
hash-grid stars masked by rain. All radiances in linear HDR units where sun peak ≈ 60,
sky zenith ≈ 1.2, full moon ≈ 0.02 — ratios matter (they drive exposure), absolute
scale is arbitrary.
