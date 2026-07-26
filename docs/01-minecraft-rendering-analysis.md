# 01 — Minecraft Rendering Architecture Analysis (MC 26.2, Fabric)

This document records the findings of the codebase analysis that this project's design is
built on. All class names below are **Mojang official mappings**, verified with `javap`
against the remapped Minecraft 26.2 jars produced by Fabric Loom for this workspace
(`minecraft-clientonly-deobf-26.2.jar` / `minecraft-common-deobf-26.2.jar`).

---

## 1. The 26.2 rendering stack: blaze3d's GPU abstraction

Minecraft 26.2 no longer issues OpenGL calls from game code. Rendering goes through a
WebGPU-shaped hardware abstraction layer in `com.mojang.blaze3d`:

```
Game code (LevelRenderer, GuiRenderer, …)
        │  RenderPipeline / RenderPass / GpuBuffer / GpuTexture
        ▼
com.mojang.blaze3d.systems.GpuDevice ── wraps ──► GpuDeviceBackend
                                                     ├── com.mojang.blaze3d.opengl.GlDevice   (OpenGL backend)
                                                     └── com.mojang.blaze3d.vulkan.*          (Vulkan backend, new in 26.x)
```

Key observations (verified by disassembly):

* **`GpuDevice` is a concrete class** wrapping a `GpuDeviceBackend`; obtained via
  `RenderSystem.getDevice()`. It creates `GpuTexture`, `GpuTextureView`, `GpuBuffer`,
  `GpuSampler`, `CommandEncoder`, and compiles `RenderPipeline`s.
* **Two backends ship in 26.2**: `com.mojang.blaze3d.opengl.GlDevice` and a Vulkan
  implementation under `com.mojang.blaze3d.vulkan` (`VulkanCommandEncoder`,
  `VulkanGpuBuffer`, `VulkanGpuTextureView`, …).
* **The abstraction has no compute support.** There is no `ComputePipeline`, no
  `dispatch()` on `CommandEncoder`, no storage-buffer bind point in
  `BindGroupLayout`. Vanilla only rasterizes. This is the single most important
  constraint for a ray tracer: *any GPU compute must bypass the abstraction*.
* On the GL backend, `com.mojang.blaze3d.opengl.GlTexture extends GpuTexture` exposes
  **`public int glId()`** and `GlStateManager` still exists as the state cache
  (tracking FBO bindings via `readFbo`/`writeFbo`, per-unit texture bindings,
  blend/depth/cull state). This is our interop escape hatch: we can find the raw GL
  texture id of any vanilla render target and keep the state cache coherent.
* `RenderTarget` (abstract, with `MainTarget`/`TextureTarget`) holds
  `GpuTexture colorTexture / depthTexture` plus public `width`/`height`.
  `GameRenderer.mainRenderTarget()` returns the main framebuffer wrapper.

### Frame graph

`LevelRenderer` builds a **frame graph** per frame (`com.mojang.blaze3d.framegraph.FrameGraphBuilder`)
with passes: sky → main (opaque + cutout sections) → entities/block entities (via a
*submit node* system, `SubmitNodeCollector`/`SubmitNodeStorage`) → particles →
clouds → weather → late debug. Translucency can go through a `PostChain`
("transparency" fabulous mode). The graph is executed inside:

```java
public void render(GraphicsResourceAllocator, DeltaTracker, boolean,
                   CameraRenderState, Matrix4fc, GpuBufferSlice fog,
                   Vector4f clearColor, boolean)
```

By the time `render(...)` **returns**, the frame graph has been executed and the level
image (color + depth) is complete in the main render target. The hand
(`ItemInHandRenderer`), screen effects and GUI are drawn afterwards by
`GameRenderer.render(...)`. **Therefore the tail of `LevelRenderer.render` is the
correct injection point to replace the world image while keeping hand + HUD.**

### Camera and matrices

Rendering state is now *extracted* into dumb state objects (a Sodium-like
extract/submit split). The camera arrives at `LevelRenderer.render` as
`net.minecraft.client.renderer.state.level.CameraRenderState` with public fields:

| field | type | use for ray tracing |
|---|---|---|
| `pos` | `Vec3` | ray origin (world space) |
| `orientation` | `Quaternionf` | camera basis |
| `projectionMatrix` | `Matrix4f` | inverse-project NDC → view rays; depth write-back |
| `viewRotationMatrix` | `Matrix4f` | rotation-only view matrix (translation is carried separately as camera-relative rendering) |
| `fogData`, `fogType` | | fog match with vanilla look |

Minecraft renders **camera-relative** (geometry translated by `-pos`), which we mirror:
all rays start at the origin of a camera-centred world and voxel data is addressed in
absolute world coordinates with `pos` added back in shader-side doubles avoided by
using region-relative integer offsets (see design doc §5).

## 2. World data model (what we voxelize)

* `Minecraft.getInstance().level` → `ClientLevel`.
* `ClientLevel.getChunkSource()` → `ClientChunkCache`, with
  `LevelChunk getChunk(int cx, int cz, ChunkStatus, boolean)`.
* `ChunkAccess.getSections()` → `LevelChunkSection[]`, one per 16³ **section**;
  vertical span from `LevelHeightAccessor.getMinY()`/`getHeight()`
  (overworld: −64…320 ⇒ 24 sections), `getMinSectionY()`, `getSectionsCount()`.
* `LevelChunkSection.hasOnlyAir()` — cheap emptiness test;
  `getStates()` → `PalettedContainer<BlockState>` with `acquire()/release()`
  (thread-guard) and `get(x,y,z)`.
* `BlockState` (`BlockBehaviour.BlockStateBase`) exposes everything the material
  system needs: `isAir()`, `canOcclude()`, `getLightEmission()` (0–15),
  `getFluidState()`, `getMapColor(BlockGetter,BlockPos).col` (fallback albedo),
  `getBlock()` + `BuiltInRegistries.BLOCK.getKey(block)` → `Identifier`
  (note: `ResourceLocation` was renamed to `Identifier` in this version).
* Block updates funnel through **`ClientLevel.setBlocksDirty(BlockPos, BlockState, BlockState)`** —
  our incremental-update hook.
* Chunk lifecycle: `ClientChunkCache.replaceWithPacketData(...)` (load/replace) and
  `ClientChunkCache.drop(ChunkPos)` (unload). 26.2 even tracks
  `addedLoadedChunks()/removedLoadedChunks()` sets internally, but those are consumed
  by vanilla's extractor, so we hook the two methods directly instead.
* Time: the old `getDayTime()` is gone; 26.2 has a **WorldClock** system —
  `Level.getDefaultClockTime()` returns the tick clock we derive the sun angle from.
  Weather via `getRainLevel(float)` / `getThunderLevel(float)`.
* Entities: `ClientLevel.entitiesForRendering()` → `Iterable<Entity>` with
  `getBoundingBox()` — enough for proxy geometry.

## 3. Fabric layer

* Loom 1.17, **Mojang mappings** (verified: the remapped jar contains
  `net.minecraft.client.renderer.LevelRenderer`, not yarn's `WorldRenderer`).
* Split source sets (`src/main` + `src/client`), client mixins in
  `raytracing.client.mixins.json`, `compatibilityLevel JAVA_25`.
* Fabric API 0.155.2+26.2 is declared as a plain `implementation` dependency (new
  Loom style). We deliberately keep the mod's *compile-time* surface free of Fabric
  API classes (entry points come from fabric-loader itself) and do all event wiring
  with our own mixins — fewer moving parts on a bleeding-edge MC version and it keeps
  us working even when the API lags behind a new game version.

## 4. Prior art survey

| project | approach | lessons taken |
|---|---|---|
| **Sodium** | replaces the section renderer with its own extract/compile/draw pipeline; meshes on worker threads; persistent-mapped staging | copy: budgets + worker-thread world capture, render-thread-only GL |
| **Iris** | injects a shader-pack pipeline around vanilla passes; deferred G-buffers; maintains its own framebuffers and state manager coherence | copy: state save/restore discipline around foreign GL code; composite passes as fullscreen raster |
| **SEUS PTGI / rethinking-voxels** | path tracing *inside the shader-pack model*: voxelizes the world into a 3D texture from the gbuffer/geometry shaders, then screen-space + voxel DDA GI | validates: **voxel DDA is the right acceleration structure for Minecraft**; a triangle BVH is unnecessary for terrain |
| **Minecraft RTX (Bedrock)** | full path tracer on DXR; per-block PBR ("MER" textures); SVGF-family denoising + upscaling | copy: material model (albedo/metallic/emissive/roughness), irradiance demodulation, temporal accumulation + à-trous denoise |
| **VulkanMod / Nvidium** | full backend replacement | rejected: enormous surface area, fragile across versions; 26.2's own Vulkan backend still has no compute abstraction we could ride |

## 5. Consequences for our design

1. **Compute must be raw LWJGL GL 4.3+** (`glDispatchCompute`, SSBOs, image
   load/store). It can only run when the **OpenGL backend** is active. We detect the
   backend at runtime (`getColorTexture() instanceof GlTexture`) and cleanly disable
   ourselves (with a log + toast message) under the Vulkan backend. A blaze3d-Vulkan
   interop backend is a roadmap item (§ roadmap doc); the renderer core is
   backend-agnostic by design so a future VK backend slots under the same interfaces.
2. **Injection point**: mixin `@Inject(at = @At("TAIL"))` into `LevelRenderer.render`.
   We overwrite the main target's color (and depth, reconstructed from ray hit
   distance) so vanilla hand/HUD composite naturally on top.
3. **World capture** happens on the render thread in budgeted slices using
   `PalettedContainer.acquire()/release()`, converted to compact GPU bricks
   (design doc §4); no per-frame full scans, ever.
4. **The GL state cache must stay coherent**: every raw-GL pass snapshots the
   bindings it clobbers and restores them, using `GlStateManager._glBindFramebuffer`
   etc. where the cache exists, raw `glGet` + rebind where it doesn't (VAO, SSBO,
   image units — vanilla never touches SSBO/image units, so those are free).
