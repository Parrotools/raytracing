package org.mining.raytracing.gpu.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.mining.raytracing.core.RtConfig;
import org.mining.raytracing.core.RtLog;
import org.mining.raytracing.gpu.FrameData;
import org.mining.raytracing.gpu.RtBackend;
import org.mining.raytracing.gpu.RtLayout;
import org.mining.raytracing.gpu.ShaderPreprocessor;
import org.mining.raytracing.gpu.StorageUpdates;

/**
 * OpenGL 4.3 compute backend (docs/02 §3.1). Raw LWJGL for kernels/SSBOs/images;
 * cooperates with vanilla only through the main target's texture id and a blit.
 * Saves and restores every binding it clobbers so {@code GlStateManager}'s cache
 * stays coherent.
 */
public final class GlRtBackend implements RtBackend {

    // logical texture indices
    private static final int T_ALBEDO = 0, T_NORMAL_A = 1, T_NORMAL_B = 2, T_EMISSION = 3,
            T_IRRADIANCE = 4, T_ACCUM_A = 5, T_ACCUM_B = 6, T_MOMENTS_A = 7, T_MOMENTS_B = 8,
            T_ATROUS_A = 9, T_ATROUS_B = 10, T_OUTPUT = 11, T_COUNT = 12;

    private RtConfig config;
    private int progPathtrace, progTemporal, progAtrous, progComposite;
    private int pcLocAtrous = -1;
    private final int[] ssbo = new int[RtLayout.SSBO_COUNT];
    private final int[] tex = new int[T_COUNT];
    private int rtW, rtH, outW, outH;
    private int parity;
    private boolean firstFrame = true;

    private int tableSlots;         // section table slot count (0 until first full upload)
    private int lightsPerSection;
    private int materialCapacity;
    private int entityCapacity;

    private int blitReadFbo;        // our output texture as read attachment
    private int blitDrawFbo;        // MC's color texture as draw attachment
    private int blitDrawTexId = -1; // texture currently attached to blitDrawFbo

    private ByteBuffer globalsScratch;

    @Override
    public String name() {
        return "OpenGL 4.3 compute";
    }

    @Override
    public boolean init(RtConfig config) {
        this.config = config;
        if (!GL.getCapabilities().OpenGL43) {
            RtLog.LOG.warn("OpenGL 4.3 not available (compute shaders required) — ray tracing disabled");
            return false;
        }
        try {
            progPathtrace = compile("pathtrace.comp");
            progTemporal = compile("temporal.comp");
            progAtrous = compile("atrous.comp");
            progComposite = compile("composite.comp");
            pcLocAtrous = GL20.glGetUniformLocation(progAtrous, "u_pc");
        } catch (Exception e) {
            RtLog.LOG.error("Kernel compilation failed — ray tracing disabled", e);
            return false;
        }
        for (int i = 0; i < ssbo.length; i++) ssbo[i] = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_GLOBALS]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, RtLayout.GLOBALS_BYTES, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_BRICK_MATS]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) config.brickPoolSize * RtLayout.BRICK_MAT_BYTES, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_BRICK_OCC]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) config.brickPoolSize * RtLayout.BRICK_OCC_BYTES, GL15.GL_DYNAMIC_DRAW);
        entityCapacity = Math.max(config.maxEntities, 1);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_ENTITIES]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) entityCapacity * RtLayout.ENTITY_STRIDE_BYTES, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        blitReadFbo = GL30.glGenFramebuffers();
        blitDrawFbo = GL30.glGenFramebuffers();
        globalsScratch = ByteBuffer.allocateDirect(RtLayout.GLOBALS_BYTES).order(ByteOrder.nativeOrder());
        lightsPerSection = config.maxLightsPerSection;
        RtLog.LOG.info("GL backend initialized: {}", GL11.glGetString(GL11.GL_RENDERER));
        return true;
    }

    private int compile(String file) {
        String src = ShaderPreprocessor.load(file, ShaderPreprocessor.GL_PREAMBLE);
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader, 16384);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("compile " + file + ":\n" + log);
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, shader);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(prog, 16384);
            GL20.glDeleteProgram(prog);
            throw new IllegalStateException("link " + file + ":\n" + log);
        }
        return prog;
    }

    @Override
    public void resize(int rtWidth, int rtHeight, int outWidth, int outHeight) {
        this.rtW = rtWidth; this.rtH = rtHeight; this.outW = outWidth; this.outH = outHeight;
        for (int i = 0; i < T_COUNT; i++) {
            if (tex[i] != 0) GL11.glDeleteTextures(tex[i]);
            tex[i] = GL11.glGenTextures();
        }
        allocTex(tex[T_ALBEDO], GL11.GL_RGBA8, rtW, rtH);
        allocTex(tex[T_NORMAL_A], GL30.GL_RGBA16F, rtW, rtH);
        allocTex(tex[T_NORMAL_B], GL30.GL_RGBA16F, rtW, rtH);
        allocTex(tex[T_EMISSION], GL30.GL_RGBA16F, rtW, rtH);
        allocTex(tex[T_IRRADIANCE], GL30.GL_RGBA16F, rtW, rtH);
        allocTex(tex[T_ACCUM_A], GL30.GL_RGBA16F, rtW, rtH);
        allocTex(tex[T_ACCUM_B], GL30.GL_RGBA16F, rtW, rtH);
        allocTex(tex[T_MOMENTS_A], GL30.GL_RG16F, rtW, rtH);
        allocTex(tex[T_MOMENTS_B], GL30.GL_RG16F, rtW, rtH);
        allocTex(tex[T_ATROUS_A], GL30.GL_RGBA16F, rtW, rtH);
        allocTex(tex[T_ATROUS_B], GL30.GL_RGBA16F, rtW, rtH);
        allocTex(tex[T_OUTPUT], GL11.GL_RGBA8, outW, outH);
        // rebind our read-blit FBO to the new output texture (raw binds, restored below by caller state save)
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, blitReadFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex[T_OUTPUT], 0);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        blitDrawTexId = -1;
        firstFrame = true;
        RtLog.LOG.info("GL targets resized: {}x{} (render) -> {}x{} (display)", rtW, rtH, outW, outH);
    }

    private void allocTex(int id, int format, int w, int h) {
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, format, w, h);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
    }

    @Override
    public void uploadStorage(StorageUpdates u) {
        if (u.isEmpty()) return;
        if (u.fullSectionTable != null) {
            tableSlots = u.fullSectionTable.remaining() / (RtLayout.TABLE_STRIDE_INTS * 4);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_SECTION_TABLE]);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, u.fullSectionTable, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_LIGHTS]);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,
                    (long) Math.max(tableSlots * Math.max(lightsPerSection, 1), 1) * RtLayout.LIGHT_STRIDE_BYTES,
                    GL15.GL_DYNAMIC_DRAW);
        }
        if (u.materialTable != null) {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_MATERIALS]);
            if (u.materialCount > materialCapacity) {
                materialCapacity = Math.max(u.materialCount * 2, 512);
                GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,
                        (long) materialCapacity * RtLayout.MATERIAL_STRIDE_BYTES, GL15.GL_DYNAMIC_DRAW);
            }
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, u.materialTable);
        }
        if (!u.bricks.isEmpty()) {
            for (StorageUpdates.BrickUpload b : u.bricks) {
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_BRICK_MATS]);
                GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) b.brickIndex() * RtLayout.BRICK_MAT_BYTES, b.materials());
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_BRICK_OCC]);
                GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) b.brickIndex() * RtLayout.BRICK_OCC_BYTES, b.occupancy());
            }
        }
        if (!u.patches.isEmpty() && tableSlots > 0) {
            ByteBuffer two = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
            for (StorageUpdates.SlotPatch p : u.patches) {
                two.clear();
                two.putInt(p.tableEntry()).putInt(p.lightCount()).flip();
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_SECTION_TABLE]);
                GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) p.slotIndex() * 8, two);
                if (p.lights() != null && lightsPerSection > 0) {
                    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_LIGHTS]);
                    GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,
                            (long) p.slotIndex() * lightsPerSection * RtLayout.LIGHT_STRIDE_BYTES, p.lights());
                }
            }
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    @Override
    public void renderFrame(FrameData f) {
        if (tableSlots == 0 || f.targetGlTexture <= 0) return;

        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevProg = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            f.resetHistory |= firstFrame;
            firstFrame = false;

            // per-frame uploads
            ByteBuffer packed = RtLayout.packGlobals(f, lightsPerSection, globalsScratch);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_GLOBALS]);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, packed);
            if (f.entityCount > 0 && f.entities != null) {
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo[RtLayout.SSBO_ENTITIES]);
                GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, f.entities);
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            for (int i = 0; i < RtLayout.SSBO_COUNT; i++) {
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, ssbo[i]);
            }

            int p = parity;
            int nrm = p == 0 ? T_NORMAL_A : T_NORMAL_B;
            int nrmPrev = p == 0 ? T_NORMAL_B : T_NORMAL_A;
            int accum = p == 0 ? T_ACCUM_A : T_ACCUM_B;
            int accumPrev = p == 0 ? T_ACCUM_B : T_ACCUM_A;
            int mom = p == 0 ? T_MOMENTS_A : T_MOMENTS_B;
            int momPrev = p == 0 ? T_MOMENTS_B : T_MOMENTS_A;

            // 1. path trace
            GL20.glUseProgram(progPathtrace);
            bindImg(0, tex[T_ALBEDO], GL11.GL_RGBA8);
            bindImg(1, tex[nrm], GL30.GL_RGBA16F);
            bindImg(2, tex[T_EMISSION], GL30.GL_RGBA16F);
            bindImg(3, tex[T_IRRADIANCE], GL30.GL_RGBA16F);
            dispatch(rtW, rtH);
            barrier();

            // 2. temporal accumulation
            GL20.glUseProgram(progTemporal);
            bindImg(0, tex[T_IRRADIANCE], GL30.GL_RGBA16F);
            bindImg(1, tex[nrm], GL30.GL_RGBA16F);
            bindImg(2, tex[nrmPrev], GL30.GL_RGBA16F);
            bindImg(3, tex[accumPrev], GL30.GL_RGBA16F);
            bindImg(4, tex[accum], GL30.GL_RGBA16F);
            bindImg(5, tex[momPrev], GL30.GL_RG16F);
            bindImg(6, tex[mom], GL30.GL_RG16F);
            bindImg(7, tex[T_ATROUS_A], GL30.GL_RGBA16F);
            dispatch(rtW, rtH);
            barrier();

            // 3. à-trous iterations
            int iters = f.denoiseIterations;
            GL20.glUseProgram(progAtrous);
            for (int i = 0; i < iters; i++) {
                int in = (i & 1) == 0 ? T_ATROUS_A : T_ATROUS_B;
                int out = (i & 1) == 0 ? T_ATROUS_B : T_ATROUS_A;
                GL20.glUniform4i(pcLocAtrous, 1 << i, i == 0 ? 1 : 0, 0, 0);
                bindImg(0, tex[nrm], GL30.GL_RGBA16F);
                bindImg(1, tex[in], GL30.GL_RGBA16F);
                bindImg(2, tex[out], GL30.GL_RGBA16F);
                bindImg(3, tex[accum], GL30.GL_RGBA16F);
                dispatch(rtW, rtH);
                barrier();
            }
            int result = iters == 0 ? T_ATROUS_A : ((iters & 1) == 1 ? T_ATROUS_B : T_ATROUS_A);

            // 4. composite at display resolution
            GL20.glUseProgram(progComposite);
            bindImg(0, tex[result], GL30.GL_RGBA16F);
            bindImg(1, tex[T_ALBEDO], GL11.GL_RGBA8);
            bindImg(2, tex[T_EMISSION], GL30.GL_RGBA16F);
            bindImg(3, tex[nrm], GL30.GL_RGBA16F);
            bindImg(4, tex[T_OUTPUT], GL11.GL_RGBA8);
            dispatch(outW, outH);
            GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);

            // 5. blit into MC's main target
            if (f.targetGlTexture != blitDrawTexId) {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, blitDrawFbo);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, f.targetGlTexture, 0);
                blitDrawTexId = f.targetGlTexture;
            } else {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, blitDrawFbo);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, blitReadFbo);
            GL30.glBlitFramebuffer(0, 0, outW, outH, 0, 0, outW, outH,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            parity ^= 1;
        } finally {
            // restore through GlStateManager so vanilla's cache matches GL reality
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GlStateManager._glUseProgram(prevProg);
        }
    }

    private void bindImg(int unit, int texId, int format) {
        GL42.glBindImageTexture(unit, texId, 0, false, 0, GL15.GL_READ_WRITE, format);
    }

    private void dispatch(int w, int h) {
        GL43.glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    }

    private void barrier() {
        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    @Override
    public void close() {
        for (int prog : new int[]{progPathtrace, progTemporal, progAtrous, progComposite}) {
            if (prog != 0) GL20.glDeleteProgram(prog);
        }
        for (int b : ssbo) if (b != 0) GL15.glDeleteBuffers(b);
        for (int t : tex) if (t != 0) GL11.glDeleteTextures(t);
        if (blitReadFbo != 0) GL30.glDeleteFramebuffers(blitReadFbo);
        if (blitDrawFbo != 0) GL30.glDeleteFramebuffers(blitDrawFbo);
    }
}
