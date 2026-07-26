package org.mining.raytracing.gpu;

import org.mining.raytracing.core.RtConfig;

/**
 * Pass-granular GPU backend contract. Implementations own every GPU object they
 * create and cooperate with Minecraft's active render backend only at the edges:
 * handles in (main render target), one blit out.
 *
 * <p>Threading: every method is called on the render thread only.
 *
 * <p>No Minecraft types may appear in this interface or its data records — the
 * renderer core stays engine-agnostic (see docs/03-architecture.md).
 */
public interface RtBackend {

    /** Human-readable name for logs ("OpenGL 4.3 compute", "Vulkan compute"). */
    String name();

    /**
     * Compile kernels and allocate static resources.
     *
     * @return false if this backend cannot run here (missing caps, compile failure);
     *         the caller logs and disables ray tracing, vanilla keeps rendering.
     */
    boolean init(RtConfig config);

    /** (Re)allocate resolution-dependent resources. rt = internal render scale, out = display. */
    void resize(int rtWidth, int rtHeight, int outWidth, int outHeight);

    /** Upload acceleration-structure and material changes for this frame (may be empty). */
    void uploadStorage(StorageUpdates updates);

    /**
     * Execute the full pass chain (pathtrace → temporal → à-trous ×N → composite)
     * and blit the result into Minecraft's main render target.
     */
    void renderFrame(FrameData frame);

    /** Destroy all GPU objects. Safe to call once, on the render thread. */
    void close();
}
