# Project Progress

_Last updated: 2026-07-26_

## Completed

**Phase 0 — analysis & platform validation**
- Disassembled the MC 26.2 client (Mojang mappings) and documented the new blaze3d
  GPU abstraction: `GpuDevice` with **OpenGL and Vulkan backends**, frame graph,
  extract/submit render states — `docs/01-minecraft-rendering-analysis.md`.
- Key findings that shaped the design: blaze3d has **no compute support**;
  `GlTexture.glId()` / `VulkanGpuTexture.vkImage()` / `VulkanDevice.vkDevice()/vma()`
  are public; blaze3d keeps all Vulkan images in `VK_IMAGE_LAYOUT_GENERAL`
  (bytecode-verified); LWJGL 3.4.1 bundles shaderc + VMA; `ChunkPos` is a record now;
  time-of-day moved to a WorldClock system.

**Documentation set** (`docs/01…07` + this file): rendering analysis, technical
design, architecture diagrams (mermaid), roadmap, math foundations (estimators,
GGX/VNDF, DDA, SVGF), material spec, devlog with decisions/problems/solutions.

**Phase 1 — the renderer (all code written and compiling)**
- `gpu` — backend-agnostic HAL (`RtBackend`, `FrameData`, `StorageUpdates`,
  `RtLayout` byte layouts, `#include` shader preprocessor with per-backend preambles).
- `gpu.gl` — **GlRtBackend**: GL 4.3 compute, SSBOs, storage textures, ping-pong
  history, blit into MC's target, GlStateManager-coherent state save/restore.
- `gpu.vk` — **VkRtBackend** (experimental): compute pipelines on Minecraft's own
  VkDevice, shaderc-compiled SPIR-V, VMA buffers/images, per-pass descriptor-set
  variants, `vkCmdUpdateBuffer` uploads, transient command buffer spliced into the
  frame submission via `VulkanCommandEncoder.execute`, single `vkCmdBlitImage` out.
- `accel` — **VoxelWorldStore**: camera-anchored section grid with wrapped slot
  addressing (survives origin shifts), 16³ material bricks + 4³ occupancy masks,
  brick free-list, budgeted captures, worker-thread conversion from
  `LevelChunkSection.copy()` snapshots, per-section emissive light lists,
  incremental updates from block/chunk hooks.
- `materials` — JSON-driven PBR table (~90 curated blocks + wildcard rules +
  MapColor synthesis fallback), BlockState→id concurrent cache, user overrides.
- GLSL kernels — `pathtrace` (3-level DDA, sun-cone + reservoir emissive NEE,
  GGX/VNDF + cosine importance sampling, refraction, Russian roulette, entity AABBs),
  `temporal` (validated reprojection + moments/variance), `atrous` (SVGF ×N with
  feedback tap), `composite` (demodulation, ACES, upscale, debug views).
- Integration — mixins (`LevelRenderer.render` TAIL, `ClientLevel.setBlocksDirty`,
  `ClientChunkCache` load/drop, `GpuDevice` accessor), GLFW keybinds (F6/F7/F8),
  JSON config, entity capture, graceful auto-disable on failure.

**Validation**
- `gradlew build` **green** — mod jar produced (`build/libs/raytracing-1.0-SNAPSHOT.jar`).
- Only compile fix needed along the way: `ChunkPos.x/z` → `x()/z()` (record).
- Boot smoke test **passed**: `runClient` reached the title screen
  (`Raytracing initialized (backend: auto, enabled: true)`), boot-time mixins
  (`LevelRenderer`, `GpuDevice`) applied cleanly, no mod-attributed errors, clean
  shutdown. World-join mixins (`ClientLevel`, `ClientChunkCache`) were verified
  against the disassembled 26.2 jars but only class-load on entering a world.

## Current task
- Phase 1 complete. Next hands-on step is the first in-world run on real hardware
  (see "Next steps").

## Unfinished problems / risks (honest list)
- **GPU-side execution is untested in a world.** Compile + boot validation only;
  first in-world run may surface GLSL driver quirks or a wrong assumption in the
  camera math (e.g. `camForward` sign — would show as an inverted image; single-line
  fix in `RaytracerCore.buildFrameData`).
- **Vulkan backend assumptions** that only a live run can confirm:
  `VulkanDevice.createCommandEncoder()` returning the singleton encoder; submission
  ordering of `execute()` relative to the frame graph; `vulkanFlipY` orientation
  (config toggle exists if the image is upside down).
- **Emission double-counting compromise**: diffuse-chain emission pickup is scaled
  0.25 for NEE-listed emitters (docs/07) — slight over-brightening possible near
  glowstone walls; correct MIS is a phase-3 item.
- Uniform-emissive sections (lava lakes) light only via bounce pickup, not NEE —
  noisier lava GI than torch GI until ReSTIR lands.
- `vkCmdUpdateBuffer` for brick uploads is correctness-first; heavy initial fill on
  VK may hitch — staging-ring upload path is the planned optimization.
- Vanilla particles/translucency draw before the hook and are overwritten (phase 2).

## Next steps
1. In-world run on real hardware (GL first): verify image orientation, DDA hits,
   sun cycle; then Vulkan backend soak (`config backend = "vulkan"`).
2. Phase 2 (docs/04): texture-atlas albedo, sub-voxel geometry (slabs/stairs SDF),
   fluid surfaces, biome tinting, particle re-integration.
3. Phase 3: ReSTIR light resampling, blue-noise sampling, separate specular
   denoise, temporal upscaling, staging-ring uploads, benchmark HUD.
4. Ecosystem: Sodium/Iris coexistence testing, resource-pack material overrides,
   shader hot-reload, `VK_KHR_ray_query` entity BLAS.
