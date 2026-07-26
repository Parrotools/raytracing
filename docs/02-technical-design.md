# 02 — Technical Design Document

**Project:** true real-time path tracing renderer for Minecraft Java Edition 26.2 (Fabric)
**Status:** implemented (phase 1 feature set), see roadmap for phase 2+

---

## 1. Goals and non-goals

**Goals**

* Replace the rasterized *world* image with a physically based, GPU path-traced image:
  primary rays, next-event-estimated direct light (sun + emissive blocks), soft
  shadows, specular reflections, refractions, multi-bounce diffuse GI, ambient
  occlusion as a natural consequence of path tracing, emissive block lighting.
* Run on **both** render backends of Minecraft 26.2: **OpenGL** (GL 4.3 compute) and
  **Vulkan** (compute queue-stream interop with Mojang's own `VulkanDevice`).
* Real-time: temporal accumulation, SVGF-style denoising, importance sampling,
  render-scale upscaling, incremental acceleration-structure updates.
* Deep world integration: live chunk load/unload, per-block-update re-voxelization,
  fluids, entities (proxy geometry), PBR material system for all vanilla blocks.

**Non-goals (phase 1)** — documented honestly rather than faked:

* No triangle-accurate geometry for non-cubic blocks (stairs/slabs are traced as full
  voxels; sub-voxel decoration like flowers is treated as non-occluding and its light
  emission is preserved as point lights).
* No texture-atlas albedo sampling yet (curated per-block material palette + MapColor
  fallback; atlas sampling is phase 2).
* No hardware RT cores (`VK_KHR_ray_query`); a voxel DDA outperforms a triangle BVH
  for Minecraft terrain and needs no driver extensions. Revisit for entity meshes.
* Particles keep their vanilla look only where they draw before our hook (they are
  overwritten; re-integration is phase 2 via light proxies).

## 2. Top-level architecture

```
                       ┌────────────────────────────────────────────────┐
   Minecraft 26.2      │                integration layer               │
   (Fabric, mojmap)    │  mixins: LevelRenderer.render TAIL (frame)     │
                       │          ClientLevel.setBlocksDirty (updates)  │
                       │          ClientChunkCache load/drop (streaming)│
                       │          GpuDevice/CommandEncoder accessors    │
                       └───────┬───────────────────────────┬────────────┘
                               │ world snapshots           │ per-frame camera state
                               ▼                           ▼
                    ┌──────────────────┐         ┌───────────────────┐
                    │ acceleration     │         │ raytracer-core    │
                    │ structure        │────────►│ orchestrator      │
                    │ (VoxelWorldStore)│ uploads │ (RaytracerCore)   │
                    └──────────────────┘         └────────┬──────────┘
                               ▲                          │ passes
                    ┌──────────┴───────┐                  ▼
                    │ material-system  │         ┌───────────────────┐
                    │ (Materials, JSON)│         │ gpu-backend HAL   │
                    └──────────────────┘         │  RtBackend        │
                                                 │  ├─ GlRtBackend   │──► GL 4.3 compute
                                                 │  └─ VkRtBackend   │──► Vulkan compute
                                                 └────────┬──────────┘
                                                          │ shared GLSL kernels
                                                          ▼
                                    pathtrace → temporal → à-trous ×N → composite → blit
                                    (post-processing stack)
```

The six required modules map to Java packages (single Gradle module keeps Loom simple;
package boundaries are enforced by convention and documented in `03-architecture.md`):

| module | package |
|---|---|
| raytracer-core | `org.mining.raytracing.core` |
| gpu-backend | `org.mining.raytracing.gpu` (+ `gpu.gl`, `gpu.vk`) |
| acceleration-structure | `org.mining.raytracing.accel` |
| material-system | `org.mining.raytracing.materials` |
| minecraft-integration | `org.mining.raytracing.mixin.client`, `org.mining.raytracing.integration` |
| post-processing | GLSL kernels `assets/raytracing/shaders/` + pass scheduling in the backends |

## 3. The GPU HAL: one renderer, two backends

Vanilla's `GpuDevice` abstraction has **no compute support**, so both backends bypass
it for kernels while cooperating with it for output:

`RtBackend` (interface) — pass-granular, not object-granular, so each backend can use
its API idiomatically:

```java
boolean  init(RtConfig cfg)                 // caps check + kernel compilation
void     resize(int rtW, int rtH, int outW, int outH)
void     uploadStorage(StorageUpdates u)    // dirty bricks, section table, materials, lights, entities
void     renderFrame(FrameData f)           // full pass chain + blit into MC's main target
void     close()
```

### 3.1 OpenGL backend (`GlRtBackend`)

* Requires `GL.getCapabilities().OpenGL43` (compute + SSBO + image load/store).
  NVIDIA/AMD/Intel drivers hand Minecraft a ≥4.3-capable core context.
* Kernels: `glDispatchCompute`; resources: SSBOs (`glBindBufferBase`) + immutable
  storage textures (`glTexStorage2D`) bound as image units.
* Output: `glBlitFramebuffer` from our result FBO into the FBO of the main target's
  color texture (`((GlTexture) target.getColorTexture()).glId()`), plus an optional
  depth reconstruction write.
* **State discipline:** vanilla's `GlStateManager` caches FBO/texture/program
  bindings. Every frame we snapshot the bindings we clobber and restore them through
  `GlStateManager._glBindFramebuffer(...)`/raw `glBindVertexArray` so cache == GL
  reality. SSBO and image-unit bindings are not used by vanilla and need no restore.

### 3.2 Vulkan backend (`VkRtBackend`)

Rides Mojang's `VulkanDevice` (all handle getters are public; verified):

* `VulkanDevice.vkDevice()`, `.vma()` (Vulkan Memory Allocator handle),
  `.graphicsQueue()` (`VkQueue` + family). We reach the `VulkanDevice` through a
  one-line `@Accessor` mixin on `GpuDevice.backend`.
* **Shaders:** the same GLSL kernels are compiled at runtime with
  `org.lwjgl.util.shaderc` (bundled with MC 26.2's LWJGL 3.4.1) targeting Vulkan 1.2,
  exactly like Mojang's own `blaze3d.vulkan.glsl.GlslCompiler` does.
* **Resources:** buffers/images allocated with VMA; one descriptor set (7 SSBOs + all
  storage images at fixed bindings; ping-pong selected by a frame-parity push
  constant, so descriptors are written once per resize, never per frame).
* **Command stream:** per frame we record a command buffer via
  `VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer()` and inject it into
  the frame's submission with `encoder.execute(cmd)`; Mojang's static
  `VulkanCommandEncoder.memoryBarrier(...)` (a global `VkMemoryBarrier2`,
  synchronization2) fences us against the frame graph. Between our own dispatches we
  use standard `vkCmdPipelineBarrier` compute→compute barriers.
* **Output:** verified from bytecode that blaze3d keeps *all* images in
  `VK_IMAGE_LAYOUT_GENERAL`, so the final tonemapped RGBA8 image is delivered with a
  single `vkCmdBlitImage` into the main target's `VulkanGpuTexture.vkImage()` — no
  layout transitions on vanilla's images, ever.
* In-flight safety: uploads go through a per-frame staging ring
  (`MAX_SUBMITS_IN_FLIGHT`-deep) with `vkCmdCopyBuffer` into device-local storage.

### 3.3 Shared GLSL, per-backend preamble

Kernels are written once in Vulkan-compatible GLSL (std430 SSBOs, storage images,
explicit bindings, no samplers — history reads use manual `imageLoad` filtering). A
tiny Java preprocessor resolves `#include` and prepends a backend preamble:

| | GL | Vulkan |
|---|---|---|
| version | `#version 430 core` | `#version 450` |
| image binding N | image unit N | set 0, binding 7 + N |
| per-dispatch data | `uniform int` | push constants |

## 4. Acceleration structure: camera-following two-level voxel grid

A BVH is the wrong tool for a voxel world; we use a **3-level DDA** hierarchy:

```
level 0: section grid   16 m cells — (2R+1) × 24 × (2R+1) slots, follows the camera
level 1: 4³ cell masks   4 m cells — 64-bit occupancy per cell (uvec2 in GLSL)
level 2: voxels          1 m       — 16³ material ids (u16), 2-per-uint packed
```

* **Section table** (SSBO): per slot `{brickIndex | AIR | UNLOADED, flags}`. The grid
  is anchored at `gridOrigin` (integer section coords); when the camera crosses a
  section boundary the origin shifts and only newly-entered slots are (re)captured —
  no toroidal ambiguity, no full rebuilds.
* **Brick pool** (SSBO): fixed capacity (config, default 6144 bricks ≈ 51 MB), free-list
  allocated; a brick = 2048 uints (materials) and a parallel pool holds 64 × uvec2
  occupancy masks. Only non-uniform sections consume bricks.
* **Incremental updates:** `ClientLevel.setBlocksDirty` marks one section dirty (plus
  neighbours when the block borders them); chunk load/drop enqueues/frees whole
  columns. A budget (default 128 captures + 24 MB uploads per frame) keeps frame time
  bounded; the initial fill of a 8-section radius completes in well under a second of
  frames.
* **Thread model:** on the render thread we `LevelChunkSection.copy()` the section
  (the same snapshot trick vanilla's meshing uses); a small worker pool converts the
  copy to brick bytes (BlockState → material id via an identity-hash cache);
  the render thread uploads finished bricks. `PalettedContainer` is never touched
  concurrently with the game.
* **Lights:** during conversion, every voxel with `getLightEmission() > 0` is
  collected (up to 16 per section) into a per-slot region of the lights SSBO for
  next-event estimation; sub-voxel emitters (torches) are voxel-skipped but still
  contribute their light entry.
* **Entities** are captured per frame (≤ 64 nearest, AABB + tint) into a small SSBO
  and intersected analytically after the DDA hit (slab test), giving them shadows and
  presence in reflections.

## 5. Coordinate precision

All GPU positions are **grid-local**: relative to `gridOrigin × 16` (metres). The
domain is ≤ ~544 m across, so `float` is exact to ≲ 1/16 mm — no doubles on GPU.
The camera position is uploaded grid-local; for temporal reprojection the *previous*
frame's camera and view-projection are re-expressed in the *current* grid frame every
frame on the CPU, so reprojection never sees a coordinate jump when the grid shifts.

## 6. Per-frame pipeline (all compute, both backends)

```
uploadStorage ─► pathtrace.comp ─► temporal.comp ─► atrous.comp ×N ─► composite.comp ─► blit
                    │ writes                                              │
                    ├─ gAlbedo    rgba8   (albedo, roughness)             ├─ tonemap ACES
                    ├─ gNormal    rgba16f (normal, hitT)                  ├─ albedo remodulation
                    ├─ gEmission  rgba16f (1st-hit emission + sky)        ├─ Catmull-Rom upscale
                    └─ irradiance rgba16f (demodulated radiance)          └─ debug views
```

1. **`pathtrace`** — one thread per render-scale pixel. Primary ray from inverse
   view-projection; 3-level DDA; at each path vertex: sun NEE through a cone (soft
   shadows), emissive-light NEE from the 3³ neighbouring sections' light lists,
   BRDF-importance-sampled continuation (cosine diffuse / GGX specular / Fresnel
   refraction for transmissive materials), Russian roulette after the first bounce.
   Irradiance is stored **demodulated** (divided by first-hit albedo) so the denoiser
   never blurs texture detail; first-hit emission and sky bypass denoising entirely.
2. **`temporal`** — reprojects the previous accumulation buffer using previous
   view-projection and hit distance; history validated by normal/depth similarity
   (4-tap manual bilinear with per-tap rejection); exponential accumulation with
   history clamp; first & second luminance moments → variance estimate (SVGF).
3. **`atrous`** — edge-avoiding à-trous wavelet iterations (default 3; steps 1,2,4)
   with SVGF edge-stopping (depth, normal, variance-normalized luminance); variance
   is filtered alongside in the alpha channel.
4. **`composite`** — remodulate albedo, add emission, exposure + ACES tonemap, gamma;
   when `renderScale < 1` an edge-preserving hybrid resamples to display resolution
   (bilinear for the smooth irradiance signal, nearest for albedo so block edges stay
   crisp; FSR2-style temporal upscaling is roadmap); debug view selector
   (albedo / normals / depth / denoised irradiance).
5. **blit** into Minecraft's main render target — vanilla then draws hand + HUD on
   top untouched.

## 7. Lighting model summary

* **Sun/moon**: directional with configurable angular radius (default 1.5° — visibly
  soft penumbrae); direction derived from the 26.2 `WorldClock`
  (`Level.getDefaultClockTime()`) with vanilla's day-fraction easing curve; radiance
  scaled by rain/thunder levels; sky uses an analytic gradient + sun disc + stars.
* **Emissive blocks**: material emission (lava, glowstone, sea lanterns, torch light
  entries…) sampled by NEE with 1/d² falloff + shadow ray, *and* hit directly by GI
  rays (double-counting avoided by excluding NEE-sampled emitters from bounce
  emission except on primary/specular hits — see math doc §5).
* **Indirect**: N configurable diffuse/specular bounces (default 2).

Full derivations: `05-math-foundations.md`. Material model: `06-materials.md`.

## 8. Configuration & controls

`config/raytracing.json` (auto-created, hot-reload on toggle):
backend `auto|opengl|vulkan|off`, renderScale, bounces, sunAngularRadiusDeg,
denoiseIterations, temporalAlpha/maxHistory, radianceClamp, sectionRadius,
brickPoolSize, budgets, entity settings, exposure, debugView.

Keys (GLFW-polled, no Fabric API dependency): **F6** toggle · **F7** cycle debug
view · **F8** cycle render scale.

## 9. Failure & compatibility policy

* Vulkan backend active but init fails (no shaderc, exotic driver) → log + one-time
  toast, vanilla rendering continues untouched.
* GL context < 4.3 → same graceful disable.
* Every raw-GL/VK frame section is wrapped so an exception disables the renderer
  rather than corrupting vanilla state (defensive `try/finally` restore).
* Sodium/Iris: not targeted in phase 1; we only *read* world data and *write* the
  final target, so co-existence is plausible but untested — documented in roadmap.
