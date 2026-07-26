# Raytracing — a real-time path tracer for Minecraft Java Edition

A Fabric mod for **Minecraft 26.2** that replaces the rasterized world image with a
GPU **path-traced** one: real ray generation and voxel traversal, physically based
materials, next-event-estimated direct light with soft shadows, specular reflection,
refraction, multi-bounce global illumination, emissive-block lighting, temporal
accumulation and SVGF-style denoising — not a shader pack, and no screen-space fakery.

It runs on **both** render backends of Minecraft 26.2:

| backend | how |
|---|---|
| **OpenGL** | raw GL 4.3 compute shaders + SSBOs, blitted into the main render target |
| **Vulkan** *(experimental)* | compute pipelines on Minecraft's own `VkDevice`, GLSL→SPIR-V via the bundled shaderc, command buffer spliced into the frame's submission |

## Building & running

```
gradlew build      # → build/libs/raytracing-1.0-SNAPSHOT.jar
gradlew runClient  # dev launch
```

Requires Java 25. The mod depends only on fabric-loader.

## Controls & configuration

* **F6** — toggle ray tracing · **F7** — cycle debug views (albedo / normals / depth /
  denoised irradiance) · **F8** — cycle render scale (1.0 / 0.75 / 0.5 / 0.33)
* `config/raytracing.json` — backend selection (`auto | opengl | vulkan | off`),
  bounces, denoiser settings, voxel-grid radius, budgets, exposure…
* `config/raytracing/materials.json` — optional PBR material overrides merged over
  the built-in table.

## Documentation

The project is documented as an engineering effort in [`docs/`](docs/):

1. [Minecraft 26.2 rendering architecture analysis](docs/01-minecraft-rendering-analysis.md)
2. [Technical design document](docs/02-technical-design.md)
3. [Architecture diagrams](docs/03-architecture.md)
4. [Implementation roadmap](docs/04-roadmap.md)
5. [Mathematical foundations](docs/05-math-foundations.md)
6. [PBR material system](docs/06-materials.md)
7. [Development log — decisions, problems, solutions](docs/07-devlog.md)
8. [Progress](docs/progress.md)

## Architecture at a glance

```
mixins (LevelRenderer TAIL, block/chunk hooks)
   → RaytracerCore (orchestration, camera, sun from the 26.2 WorldClock)
   → VoxelWorldStore (camera-anchored section grid → 4³ occupancy masks → 16³ material bricks,
                      incremental updates, worker-thread capture)
   → RtBackend HAL (GlRtBackend | VkRtBackend)
   → shared GLSL kernels: pathtrace → temporal → à-trous ×N → composite → blit
```

Known phase-1 limitations (stairs/slabs as full voxels, palette albedo instead of
atlas textures, vanilla particles overwritten) are tracked honestly in the
[roadmap](docs/04-roadmap.md).
