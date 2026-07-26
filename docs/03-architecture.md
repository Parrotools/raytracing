# 03 — Architecture Diagram & Module Map

## System diagram

```mermaid
flowchart TB
    subgraph MC["Minecraft 26.2 client (mojmap)"]
        LR["LevelRenderer.render(...)"]
        CL["ClientLevel"]
        CC["ClientChunkCache"]
        GD["RenderSystem.getDevice() : GpuDevice"]
        MT["GameRenderer.mainRenderTarget()"]
    end

    subgraph INT["minecraft-integration  (mixin.client / integration)"]
        MX1["LevelRendererMixin<br/>@Inject TAIL render"]
        MX2["ClientLevelMixin<br/>setBlocksDirty"]
        MX3["ClientChunkCacheMixin<br/>replaceWithPacketData / drop"]
        ACC["GpuDeviceAccessor<br/>CommandEncoderAccessor"]
        KEYS["Keybinds (GLFW poll)"]
        ECAP["EntityCapture (AABB proxies)"]
    end

    subgraph CORE["raytracer-core  (core)"]
        RC["RaytracerCore<br/>lifecycle · frame orchestration"]
        CFG["RtConfig (JSON)"]
        FU["FrameData<br/>camera · sun · matrices · prev-frame state"]
    end

    subgraph ACCEL["acceleration-structure  (accel)"]
        VWS["VoxelWorldStore<br/>camera-anchored section grid"]
        SC["SectionCapture<br/>section.copy() → worker convert"]
        BA["brick free-list allocator"]
    end

    subgraph MATS["material-system  (materials)"]
        MREG["Materials registry<br/>BlockState → material id cache"]
        MJSON["materials.json<br/>curated PBR table + rules"]
    end

    subgraph HAL["gpu-backend  (gpu)"]
        RB["RtBackend (interface)"]
        GLB["GlRtBackend<br/>GL 4.3 compute + SSBO + blit"]
        VKB["VkRtBackend<br/>VMA · shaderc · transient cmd buffer"]
        SH["ShaderPreprocessor<br/>#include + per-backend preamble"]
    end

    subgraph KERNELS["shared GLSL kernels + post-processing"]
        K1["pathtrace.comp<br/>DDA · NEE · GGX · RR"]
        K2["temporal.comp<br/>reprojection · moments"]
        K3["atrous.comp ×N<br/>SVGF wavelet"]
        K4["composite.comp<br/>tonemap · upscale · debug"]
    end

    LR --> MX1 --> RC
    CL --> MX2 --> VWS
    CC --> MX3 --> VWS
    GD --> ACC --> VKB
    MT -->|"GlTexture.glId() / VulkanGpuTexture.vkImage()"| RB
    KEYS --> RC
    ECAP --> RC
    CFG --> RC
    RC --> FU --> RB
    RC --> VWS
    VWS --> SC --> MREG
    MREG --> MJSON
    VWS -->|"StorageUpdates (bricks · table · lights · materials)"| RB
    RB --> GLB
    RB --> VKB
    SH --> GLB
    SH --> VKB
    GLB --> KERNELS
    VKB --> KERNELS
    K1 --> K2 --> K3 --> K4
    K4 -->|blit| MT
```

## Frame sequence

```mermaid
sequenceDiagram
    participant MC as LevelRenderer
    participant Core as RaytracerCore
    participant Store as VoxelWorldStore
    participant BE as RtBackend (GL/VK)

    MC->>Core: onLevelRendered(CameraRenderState) [mixin TAIL]
    Core->>Core: level change? → reset · keybinds · config
    Core->>Store: tick(cameraSection)  — shift grid, drain dirty queues
    Store->>Store: copy sections (budget) → workers convert
    Store-->>Core: StorageUpdates (ready bricks, table patches, lights)
    Core->>BE: uploadStorage(updates)
    Core->>Core: build FrameData (matrices, sun from WorldClock, prev-frame re-expressed in current grid)
    Core->>BE: renderFrame(FrameData)
    BE->>BE: pathtrace → temporal → atrous×N → composite
    BE->>MC: blit into mainRenderTarget (color)
    Note over MC: vanilla then draws hand + HUD on top
```

## Module responsibilities & dependency rules

| package | responsibility | may depend on |
|---|---|---|
| `core` | lifecycle, orchestration, config, frame data | `gpu` (interface only), `accel`, `materials` |
| `gpu` | `RtBackend` HAL, shader preprocessing | nothing above it |
| `gpu.gl` | GL 4.3 implementation | `gpu`, LWJGL GL, `GlStateManager` interop |
| `gpu.vk` | Vulkan implementation | `gpu`, LWJGL VK/VMA/shaderc, blaze3d vulkan handles |
| `accel` | world→GPU voxel store, budgets, threading | `materials` |
| `materials` | PBR table, BlockState mapping | Minecraft registries only |
| `mixin.client` + `integration` | hooks, capture, input | `core` |

Rule of thumb enforced in review: **Minecraft types never cross into `gpu`**
(`FrameData`/`StorageUpdates` are plain records of primitives and NIO buffers), so the
renderer core stays engine-portable and unit-testable without a game instance.
