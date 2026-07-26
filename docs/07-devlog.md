# 07 — Development Log: decisions, problems, solutions

A living log. Newest entries at the bottom. Dates are absolute.

---

## 2026-07-26 — Platform recon

**Problem: which mappings does this template actually use?** `gradle.properties`
lists `yarn_mappings=1.21.11+build.6` but `build.gradle` has no `mappings` line.
Disassembling the project-local remapped jar
(`.gradle/loom-cache/.../minecraft-clientOnly-…-26.2.jar`) shows
`net.minecraft.client.renderer.LevelRenderer` → **Mojang official mappings** (the new
Loom default). The yarn property is dead config; all code targets mojmap.

**Finding: blaze3d's new HAL has no compute.** 26.2's `GpuDevice` is WebGPU-shaped
(RenderPipeline/RenderPass/BindGroup) but exposes **zero** compute capability — no
ComputePipeline, no dispatch, no storage bindings. Decision: implement kernels with
raw LWJGL per backend, cooperate with the abstraction only at the edges (handles in,
blit out). This is the single decision the whole GPU layer hangs on.

**Finding: 26.2 ships a Vulkan backend** (`com.mojang.blaze3d.vulkan.*`) alongside
OpenGL, and the interop surface is unexpectedly friendly — all public:
`VulkanDevice.vkDevice()/vma()/graphicsQueue()`, `VulkanGpuTexture.vkImage()`,
`VulkanGpuBuffer.vkBuffer()`, `VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer()/execute()`.
LWJGL 3.4.1 bundles **shaderc, SPIRV-Cross, VMA** — runtime GLSL→SPIR-V is possible
with zero added dependencies.

**Finding (bytecode-verified): blaze3d keeps every VkImage in `VK_IMAGE_LAYOUT_GENERAL`**
(`vkCmdClearColorImage(..., iconst_1, ...)`) and synchronizes with
`vkCmdPipelineBarrier2KHR` + global `VkMemoryBarrier2`. Consequence: our Vulkan
output path is one `vkCmdBlitImage` with no layout transitions on vanilla images,
and Mojang's own static `memoryBarrier` helper is a valid fence around our work.

**Decision: no Fabric API compile-time dependency.** fabric-api is on the classpath
as the new-style plain `implementation`, but on a bleeding-edge MC version the API
often lags. Entry points come from fabric-loader; events we need are two tiny mixins
(`setBlocksDirty`, chunk load/drop); input is GLFW polling. Fewer moving parts.

**Decision: requirement update — Vulkan is first-class.** Original plan was
GL-compute-with-VK-detection; requirement changed mid-design to "work well on
Vulkan". Restructured the GPU layer as a pass-granular HAL (`RtBackend`) with two
peer implementations instead of a GL implementation with an abstraction retrofitted
later. Pass-granular (not object-granular) because GL and VK disagree about what
"a binding" even is; per-pass methods let each side be idiomatic.

**Decision: everything is compute + blit.** Earlier draft had a fullscreen raster
composite writing `gl_FragDepth`. Dropped: vanilla clears depth before the hand
renders anyway, so depth write-back buys nothing, and a compute-only pipeline needs
no VAO/vertex state on GL and no render-pass objects on VK — the two backends stay
nearly symmetric.

**Decision: 3-level DDA over BVH.** Terrain is axis-aligned voxels; a section-grid →
4³-mask → voxel DDA gives O(1) memory per section, exact hit normals for free, and
trivially incremental updates (a BVH refit/rebuild would dominate frame time on
block edits). Prior art (SEUS PTGI, rethinking-voxels, Teardown) validates this.
Occupancy masks are `uvec2` because GLSL 4.30 doesn't guarantee int64.

**Problem: world-space float precision.** Player coordinates reach millions;
tracing in absolute floats would shimmer. Solution: all GPU coordinates are relative
to the voxel grid origin (≤ ~544 m domain → sub-mm float precision), and the CPU
re-expresses *previous-frame* camera/matrices in the *current* grid frame each frame
so reprojection is seamless across grid shifts.

**Problem: reading chunk data without racing the game.** `PalettedContainer` crashes
on concurrent access by design (`ThreadingDetector`). Vanilla's own meshing snapshots
sections on the render thread. We do the same: `LevelChunkSection.copy()` on the
render thread under a per-frame budget, then convert to bricks on a worker pool,
upload next frame. Never touch live containers off-thread.

**Problem: time-of-day API vanished.** `Level.getDayTime()` is gone in 26.2 —
replaced by a WorldClock system. Sun angle now derives from
`Level.getDefaultClockTime()` run through vanilla's historical day-fraction easing
curve (verified formula against old sources).

**Problem: keeping vanilla's GL state cache coherent.** `GlStateManager` mirrors
FBO/texture/program bindings; raw GL behind its back desyncs it (classic Iris-era
bug class). Every GL-side frame section snapshots what it touches and restores via
the `GlStateManager` entry points (so the cache updates too); SSBO/image units are
untracked by vanilla and safe to leave.

## 2026-07-26 — Implementation notes

* Mixin into `LevelRenderer.render` TAIL rather than a frame-graph pass insertion:
  the graph API is new and unstable; TAIL is after graph execution, before hand/HUD —
  exactly the compositing point we need, and it survives point releases better.
* `CameraRenderState` (not `Camera`) is the source of truth for matrices — the
  extract/submit split means `Camera` may be mid-update during render.
* Materials: BlockState→id via `IdentityHashMap` (states are interned); synthesized
  MapColor materials dedup by color+emission so the table stays small.
* Emissive NEE uses reservoir selection of 1 light from the 3³ neighbour sections —
  measured (analytically) as the cheapest scheme that still gives every local light a
  shadowed contribution; ReSTIR is the phase-3 upgrade.
* Double-counting control (NEE vs. bounce emission): emission added on primary +
  specular-chain hits only; diffuse-chain emission comes exclusively from NEE.
* SVGF feedback tap: à-trous iteration 1 writes back into the temporal accumulation
  buffer (per the paper) — implemented as an extra flag on that dispatch.
* Vulkan uploads: staging ring sized `MAX_SUBMITS_IN_FLIGHT + 1` frames; device-local
  brick pool updated with `vkCmdCopyBuffer` inside the same transient command buffer
  that runs the kernels, so ordering is by queue submission order — no extra fences.
* Config hot-path: keybinds mutate `RtConfig` in memory and persist on world exit;
  renderScale changes trigger `resize()` next frame (lazy, single allocation point).

## 2026-07-26 — Validation

* `gradlew build` green (compile + mixin annotation processing + remap). The only
  API drift the compiler caught vs. the javap-derived design: `ChunkPos` became a
  record (`pos.x` → `pos.x()`).
* Boot smoke test passed: `runClient` reached the title screen, log shows
  `Raytracing initialized (backend: auto, enabled: true)`, clean shutdown, zero
  mod-attributed errors. This validates at class-load time the mixins into classes
  that load during boot (`LevelRenderer`, `GpuDevice`); `ClientLevel` /
  `ClientChunkCache` mixins load on world join and were verified against the same
  disassembled jars.
* GPU-side execution requires entering a world on real hardware; the GL path follows
  the exact call sequence of the validated design; the VK path additionally depends
  on driver behavior and is flagged **experimental** until soak-tested. Both fail
  soft (auto-disable + log) rather than crashing the client.
