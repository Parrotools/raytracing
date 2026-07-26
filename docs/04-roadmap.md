# 04 — Implementation Roadmap

Phases are ordered so that every phase ends in a *runnable, honest* state. Status
reflects this repository.

## Phase 0 — Analysis & platform validation ✅
- [x] Disassemble MC 26.2 rendering stack (blaze3d GpuDevice, GL + Vulkan backends,
      frame graph, extract/submit model) — `01-minecraft-rendering-analysis.md`
- [x] Confirm mappings (Mojang official), hook points, thread model
- [x] Verify Vulkan interop surface (public `vkDevice()/vma()/vkImage()`,
      `GENERAL` image layout, synchronization2, bundled shaderc/VMA in LWJGL 3.4.1)
- [x] Skeleton compiles (`gradlew compileClientJava`)

## Phase 1 — Core path tracer (this deliverable) ✅
- [x] GPU HAL (`RtBackend`) with **GL 4.3 compute** and **Vulkan compute** backends
- [x] Camera-following two-level voxel acceleration structure, incremental updates,
      chunk streaming, budgeted capture with worker-thread conversion
- [x] PBR material system: curated JSON table (~70 block families), rule matching,
      MapColor fallback, emission/metallic/roughness/IOR/transmission
- [x] Path tracing kernel: 3-level DDA, sun NEE with soft shadows, emissive NEE from
      per-section light lists, GGX + Lambert importance sampling, refraction,
      Russian roulette, entity AABB proxies
- [x] SVGF-style post stack: temporal reprojection + moments, à-trous ×N, variance
      guidance, albedo demodulation, ACES tonemap, edge-preserving render-scale upscale
- [x] Integration: mixin frame hook, block-update/chunk hooks, GLFW keybinds,
      JSON config, graceful disable on unsupported context
- [x] `gradlew build` green

## Phase 2 — Visual completeness
- [ ] Texture-atlas albedo: sample block sprites into a per-material 8×8 tile array
      (removes flat-color look; the material id already reserves bits for it)
- [ ] Particles & translucent vanilla layers re-composited over the traced image
      (requires splitting the hook before/after the translucent pass)
- [ ] Sub-voxel geometry classes: slabs/stairs/fences as signed-distance modifiers in
      the leaf intersection; flowers/torch models as cutout billboards
- [ ] Fluid surface heights (flowing water shape) + absorption (Beer–Lambert)
- [ ] Per-biome water/foliage tinting from `BiomeColors`
- [ ] Entity meshes: capsule/skeleton approximation upgrade, or triangle BLAS for
      entities only

## Phase 3 — Performance & quality
- [ ] Temporal upscaling (jittered low-res + history reconstruction, FSR2-like)
- [ ] ReSTIR-style light resampling for many emissives
- [ ] Blue-noise sampling (owen-scrambled Sobol) replacing PCG white noise
- [ ] Adaptive sampling from variance (extra rays only where history is poor)
- [ ] Specular denoiser pass separate from diffuse (roughness-aware)
- [ ] GL: persistent-mapped staging rings; VK: dedicated transfer-queue uploads
- [ ] Benchmark harness + frame-time HUD

## Phase 4 — Ecosystem
- [ ] Sodium/Iris coexistence testing; Iris-style config screen (ClothConfig optional)
- [ ] Resource-pack material overrides (`assets/<ns>/raytracing/materials.json`)
- [ ] Shader hot-reload (F9) for kernel development
- [ ] `VK_KHR_ray_query` path for entity BLAS on RTX/RDNA2+ hardware

## Known limitations (phase 1, by design — see design doc §1)
Stairs/slabs trace as full cubes; no atlas textures; vanilla particles/translucency
overwritten; weather visuals approximate (rain dims sun, no traced raindrops);
Vulkan backend is code-complete but flagged experimental until validated on more
drivers (`backend: "auto"` prefers whichever backend the game itself is running).
