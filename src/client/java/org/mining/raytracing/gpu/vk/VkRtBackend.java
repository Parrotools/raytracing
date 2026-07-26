package org.mining.raytracing.gpu.vk;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.mining.raytracing.core.RtConfig;
import org.mining.raytracing.core.RtLog;
import org.mining.raytracing.gpu.FrameData;
import org.mining.raytracing.gpu.RtBackend;
import org.mining.raytracing.gpu.RtLayout;
import org.mining.raytracing.gpu.ShaderPreprocessor;
import org.mining.raytracing.gpu.StorageUpdates;

/**
 * Vulkan compute backend (docs/02 §3.2). Rides Minecraft's own {@link VulkanDevice}:
 * same VkDevice/VMA/queue; kernels are compiled at runtime with the bundled shaderc;
 * our command buffer is spliced into the frame's submission via
 * {@link VulkanCommandEncoder#execute}. blaze3d keeps every image in
 * {@code VK_IMAGE_LAYOUT_GENERAL} (verified from bytecode), so output is a single blit.
 *
 * <p>Status: code-complete, flagged experimental until soak-tested on more drivers.
 */
public final class VkRtBackend implements RtBackend {

    private static final int PASS_PATHTRACE = 0, PASS_TEMPORAL = 1, PASS_ATROUS = 2, PASS_COMPOSITE = 3;
    private static final int[] PASS_SSBOS = {RtLayout.SSBO_COUNT, 1, 1, 1}; // pathtrace sees all, rest just globals
    private static final int[] PASS_IMAGES = {4, 8, 4, 5};

    // logical image indices (match GL backend)
    private static final int T_ALBEDO = 0, T_NORMAL_A = 1, T_NORMAL_B = 2, T_EMISSION = 3,
            T_IRRADIANCE = 4, T_ACCUM_A = 5, T_ACCUM_B = 6, T_MOMENTS_A = 7, T_MOMENTS_B = 8,
            T_ATROUS_A = 9, T_ATROUS_B = 10, T_OUTPUT = 11, T_COUNT = 12;

    private static final int FMT_RGBA8 = VK10.VK_FORMAT_R8G8B8A8_UNORM;
    private static final int FMT_RGBA16F = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int FMT_RG16F = VK10.VK_FORMAT_R16G16_SFLOAT;

    private final VulkanDevice mcDevice;
    private RtConfig config;
    private VkDevice device;
    private long vma;
    private VulkanCommandEncoder encoder;

    private final long[] pipelines = new long[4];
    private final long[] pipelineLayouts = new long[4];
    private final long[] setLayouts = new long[4];
    private long descriptorPool;
    // sets: [pass][variant] — pathtrace/temporal by parity; atrous [parity*2+pingpong]; composite [parity*2+resultBuf]
    private final long[][] sets = {new long[2], new long[2], new long[4], new long[4]};

    private final long[] buf = new long[RtLayout.SSBO_COUNT];
    private final long[] bufAlloc = new long[RtLayout.SSBO_COUNT];
    private final long[] bufSize = new long[RtLayout.SSBO_COUNT];

    private final long[] img = new long[T_COUNT];
    private final long[] imgAlloc = new long[T_COUNT];
    private final long[] imgView = new long[T_COUNT];

    private int rtW, rtH, outW, outH;
    private int parity;
    private boolean firstFrame = true;
    private boolean imagesNeedInit = true;
    private boolean descriptorsDirty = true;
    private int tableSlots;
    private int lightsPerSection;
    private final List<StorageUpdates> pendingUploads = new ArrayList<>();
    private ByteBuffer globalsScratch;

    public VkRtBackend(VulkanDevice mcDevice) {
        this.mcDevice = mcDevice;
    }

    @Override
    public String name() {
        return "Vulkan compute (experimental)";
    }

    @Override
    public boolean init(RtConfig config) {
        this.config = config;
        this.lightsPerSection = config.maxLightsPerSection;
        try {
            device = mcDevice.vkDevice();
            vma = mcDevice.vma();
            encoder = mcDevice.createCommandEncoder(); // singleton accessor, not a new object
            compilePipelines();
            createStaticBuffers();
            createDescriptorPool();
            globalsScratch = ByteBuffer.allocateDirect(RtLayout.GLOBALS_BYTES).order(ByteOrder.nativeOrder());
            RtLog.LOG.info("Vulkan backend initialized on Minecraft's VkDevice (queue family {})",
                    mcDevice.graphicsQueue().queueFamilyIndex());
            return true;
        } catch (Throwable t) {
            RtLog.LOG.error("Vulkan backend init failed — ray tracing disabled", t);
            closeQuiet();
            return false;
        }
    }

    // ------------------------------------------------------------------ pipelines

    private void compilePipelines() {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long opts = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(opts,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
        Shaderc.shaderc_compile_options_set_optimization_level(opts,
                Shaderc.shaderc_optimization_level_performance);
        try {
            String[] files = {"pathtrace.comp", "temporal.comp", "atrous.comp", "composite.comp"};
            for (int pass = 0; pass < 4; pass++) {
                long module = compileModule(compiler, opts, files[pass]);
                createPassObjects(pass, module);
                VK10.vkDestroyShaderModule(device, module, null);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(opts);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private long compileModule(long compiler, long opts, String file) {
        String src = ShaderPreprocessor.load(file, ShaderPreprocessor.VK_PREAMBLE);
        long res = Shaderc.shaderc_compile_into_spv(compiler, src,
                Shaderc.shaderc_compute_shader, file, "main", opts);
        try {
            if (Shaderc.shaderc_result_get_compilation_status(res)
                    != Shaderc.shaderc_compilation_status_success) {
                throw new IllegalStateException("shaderc " + file + ": "
                        + Shaderc.shaderc_result_get_error_message(res));
            }
            ByteBuffer spv = Shaderc.shaderc_result_get_bytes(res);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default().pCode(spv);
                LongBuffer p = stack.mallocLong(1);
                check(VK10.vkCreateShaderModule(device, info, null, p), "vkCreateShaderModule " + file);
                return p.get(0);
            }
        } finally {
            Shaderc.shaderc_result_release(res);
        }
    }

    private void createPassObjects(int pass, long module) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int nS = PASS_SSBOS[pass], nI = PASS_IMAGES[pass];
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(nS + nI, stack);
            for (int i = 0; i < nS; i++) {
                bindings.get(i).binding(i)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            for (int i = 0; i < nI; i++) {
                bindings.get(nS + i).binding(7 + i)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo li = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(device, li, null, p), "vkCreateDescriptorSetLayout");
            setLayouts[pass] = p.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo pli = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(setLayouts[pass]))
                    .pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(device, pli, null, p), "vkCreatePipelineLayout");
            pipelineLayouts[pass] = p.get(0);

            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default().layout(pipelineLayouts[pass]);
            ci.get(0).stage().sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module)
                    .pName(stack.UTF8("main"));
            check(VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, ci, null, p),
                    "vkCreateComputePipelines");
            pipelines[pass] = p.get(0);
        }
    }

    // ------------------------------------------------------------------ resources

    private void createStaticBuffers() {
        createBuffer(RtLayout.SSBO_GLOBALS, RtLayout.GLOBALS_BYTES);
        createBuffer(RtLayout.SSBO_BRICK_MATS, (long) config.brickPoolSize * RtLayout.BRICK_MAT_BYTES);
        createBuffer(RtLayout.SSBO_BRICK_OCC, (long) config.brickPoolSize * RtLayout.BRICK_OCC_BYTES);
        createBuffer(RtLayout.SSBO_MATERIALS, 512L * RtLayout.MATERIAL_STRIDE_BYTES);
        createBuffer(RtLayout.SSBO_ENTITIES, (long) Math.max(config.maxEntities, 1) * RtLayout.ENTITY_STRIDE_BYTES);
        // section table + lights created lazily once the slot count is known
    }

    private void createBuffer(int idx, long size) {
        destroyBuffer(idx);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size)
                    .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pBuf = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            check(Vma.vmaCreateBuffer(vma, bci, aci, pBuf, pAlloc, null), "vmaCreateBuffer");
            buf[idx] = pBuf.get(0);
            bufAlloc[idx] = pAlloc.get(0);
            bufSize[idx] = size;
        }
        descriptorsDirty = true;
    }

    private void destroyBuffer(int idx) {
        if (buf[idx] != 0) {
            Vma.vmaDestroyBuffer(vma, buf[idx], bufAlloc[idx]);
            buf[idx] = 0;
            bufAlloc[idx] = 0;
        }
    }

    private void createImage(int idx, int format, int w, int h) {
        destroyImage(idx);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().set(w, h, 1);
            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pImg = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(vma, ici, aci, pImg, pAlloc, null), "vmaCreateImage");
            img[idx] = pImg.get(0);
            imgAlloc[idx] = pAlloc.get(0);

            VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(img[idx])
                    .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            vci.subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(VK10.vkCreateImageView(device, vci, null, pView), "vkCreateImageView");
            imgView[idx] = pView.get(0);
        }
    }

    private void destroyImage(int idx) {
        if (imgView[idx] != 0) {
            VK10.vkDestroyImageView(device, imgView[idx], null);
            imgView[idx] = 0;
        }
        if (img[idx] != 0) {
            Vma.vmaDestroyImage(vma, img[idx], imgAlloc[idx]);
            img[idx] = 0;
            imgAlloc[idx] = 0;
        }
    }

    @Override
    public void resize(int rtWidth, int rtHeight, int outWidth, int outHeight) {
        mcDevice.graphicsQueue().waitIdle(); // images may be in flight
        this.rtW = rtWidth; this.rtH = rtHeight; this.outW = outWidth; this.outH = outHeight;
        createImage(T_ALBEDO, FMT_RGBA8, rtW, rtH);
        createImage(T_NORMAL_A, FMT_RGBA16F, rtW, rtH);
        createImage(T_NORMAL_B, FMT_RGBA16F, rtW, rtH);
        createImage(T_EMISSION, FMT_RGBA16F, rtW, rtH);
        createImage(T_IRRADIANCE, FMT_RGBA16F, rtW, rtH);
        createImage(T_ACCUM_A, FMT_RGBA16F, rtW, rtH);
        createImage(T_ACCUM_B, FMT_RGBA16F, rtW, rtH);
        createImage(T_MOMENTS_A, FMT_RG16F, rtW, rtH);
        createImage(T_MOMENTS_B, FMT_RG16F, rtW, rtH);
        createImage(T_ATROUS_A, FMT_RGBA16F, rtW, rtH);
        createImage(T_ATROUS_B, FMT_RGBA16F, rtW, rtH);
        createImage(T_OUTPUT, FMT_RGBA8, outW, outH);
        imagesNeedInit = true;
        descriptorsDirty = true;
        firstFrame = true;
        RtLog.LOG.info("VK targets resized: {}x{} (render) -> {}x{} (display)", rtW, rtH, outW, outH);
    }

    // ------------------------------------------------------------------ descriptors

    private void createDescriptorPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(64);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(96);
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(16).pPoolSizes(sizes);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(device, info, null, p), "vkCreateDescriptorPool");
            descriptorPool = p.get(0);
        }
        for (int pass = 0; pass < 4; pass++) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int n = sets[pass].length;
                LongBuffer layouts = stack.mallocLong(n);
                for (int i = 0; i < n; i++) layouts.put(i, setLayouts[pass]);
                VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(descriptorPool)
                        .pSetLayouts(layouts);
                LongBuffer p = stack.mallocLong(n);
                check(VK10.vkAllocateDescriptorSets(device, ai, p), "vkAllocateDescriptorSets");
                for (int i = 0; i < n; i++) sets[pass][i] = p.get(i);
            }
        }
    }

    /** Per-pass image plans for each variant — mirrors the GL backend's bind order. */
    private int[] imagePlan(int pass, int variant) {
        int p = (pass == PASS_ATROUS || pass == PASS_COMPOSITE) ? variant >> 1 : variant;
        int nrm = p == 0 ? T_NORMAL_A : T_NORMAL_B;
        int nrmPrev = p == 0 ? T_NORMAL_B : T_NORMAL_A;
        int accum = p == 0 ? T_ACCUM_A : T_ACCUM_B;
        int accumPrev = p == 0 ? T_ACCUM_B : T_ACCUM_A;
        int mom = p == 0 ? T_MOMENTS_A : T_MOMENTS_B;
        int momPrev = p == 0 ? T_MOMENTS_B : T_MOMENTS_A;
        return switch (pass) {
            case PASS_PATHTRACE -> new int[]{T_ALBEDO, nrm, T_EMISSION, T_IRRADIANCE};
            case PASS_TEMPORAL -> new int[]{T_IRRADIANCE, nrm, nrmPrev, accumPrev, accum, momPrev, mom, T_ATROUS_A};
            case PASS_ATROUS -> {
                boolean ping = (variant & 1) == 0;
                yield new int[]{nrm, ping ? T_ATROUS_A : T_ATROUS_B, ping ? T_ATROUS_B : T_ATROUS_A, accum};
            }
            default -> { // composite: variant bit0 selects the final à-trous buffer
                int result = (variant & 1) == 0 ? T_ATROUS_A : T_ATROUS_B;
                yield new int[]{result, T_ALBEDO, T_EMISSION, nrm, T_OUTPUT};
            }
        };
    }

    private void writeAllDescriptors() {
        for (int pass = 0; pass < 4; pass++) {
            for (int variant = 0; variant < sets[pass].length; variant++) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    int nS = PASS_SSBOS[pass];
                    int[] plan = imagePlan(pass, variant);
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(nS + plan.length, stack);
                    for (int i = 0; i < nS; i++) {
                        VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack);
                        bi.get(0).buffer(buf[i]).offset(0).range(VK10.VK_WHOLE_SIZE);
                        writes.get(i).sType$Default()
                                .dstSet(sets[pass][variant]).dstBinding(i)
                                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .descriptorCount(1)
                                .pBufferInfo(bi);
                    }
                    for (int i = 0; i < plan.length; i++) {
                        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack);
                        ii.get(0).imageView(imgView[plan[i]]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        writes.get(nS + i).sType$Default()
                                .dstSet(sets[pass][variant]).dstBinding(7 + i)
                                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .descriptorCount(1)
                                .pImageInfo(ii);
                    }
                    VK10.vkUpdateDescriptorSets(device, writes, null);
                }
            }
        }
        descriptorsDirty = false;
    }

    // ------------------------------------------------------------------ uploads

    @Override
    public void uploadStorage(StorageUpdates u) {
        if (u.isEmpty()) return;
        if (u.fullSectionTable != null) {
            int slots = u.fullSectionTable.remaining() / (RtLayout.TABLE_STRIDE_INTS * 4);
            if (slots != tableSlots || buf[RtLayout.SSBO_SECTION_TABLE] == 0) {
                mcDevice.graphicsQueue().waitIdle();
                tableSlots = slots;
                createBuffer(RtLayout.SSBO_SECTION_TABLE, (long) slots * RtLayout.TABLE_STRIDE_INTS * 4);
                createBuffer(RtLayout.SSBO_LIGHTS,
                        (long) Math.max(slots * Math.max(lightsPerSection, 1), 1) * RtLayout.LIGHT_STRIDE_BYTES);
            }
        }
        // uploads are recorded into the frame's command buffer in renderFrame; the
        // producer allocates fresh buffers per capture, so retaining them is safe
        pendingUploads.add(u);
    }

    private void recordUploads(VkCommandBuffer cmd) {
        for (StorageUpdates u : pendingUploads) {
            if (u.fullSectionTable != null) {
                updateChunked(cmd, buf[RtLayout.SSBO_SECTION_TABLE], 0, u.fullSectionTable);
            }
            if (u.materialTable != null) {
                long need = (long) u.materialCount * RtLayout.MATERIAL_STRIDE_BYTES;
                if (need > bufSize[RtLayout.SSBO_MATERIALS]) {
                    // can't grow mid-recording; defer this batch to next frame
                    mcDevice.graphicsQueue().waitIdle();
                    createBuffer(RtLayout.SSBO_MATERIALS, need * 2);
                }
                updateChunked(cmd, buf[RtLayout.SSBO_MATERIALS], 0, u.materialTable);
            }
            for (StorageUpdates.BrickUpload b : u.bricks) {
                updateChunked(cmd, buf[RtLayout.SSBO_BRICK_MATS],
                        (long) b.brickIndex() * RtLayout.BRICK_MAT_BYTES, b.materials());
                updateChunked(cmd, buf[RtLayout.SSBO_BRICK_OCC],
                        (long) b.brickIndex() * RtLayout.BRICK_OCC_BYTES, b.occupancy());
            }
            for (StorageUpdates.SlotPatch p : u.patches) {
                ByteBuffer two = MemoryUtil.memAlloc(8).order(ByteOrder.nativeOrder());
                two.putInt(p.tableEntry()).putInt(p.lightCount()).flip();
                VK10.vkCmdUpdateBuffer(cmd, buf[RtLayout.SSBO_SECTION_TABLE], (long) p.slotIndex() * 8, two);
                MemoryUtil.memFree(two); // vkCmdUpdateBuffer copies inline at record time
                if (p.lights() != null && lightsPerSection > 0) {
                    updateChunked(cmd, buf[RtLayout.SSBO_LIGHTS],
                            (long) p.slotIndex() * lightsPerSection * RtLayout.LIGHT_STRIDE_BYTES, p.lights());
                }
            }
        }
        pendingUploads.clear();
    }

    /** vkCmdUpdateBuffer caps at 64 KiB per call; chunk larger uploads. */
    private void updateChunked(VkCommandBuffer cmd, long buffer, long offset, ByteBuffer data) {
        int pos = data.position(), lim = data.limit();
        int total = lim - pos;
        int done = 0;
        while (done < total) {
            int n = Math.min(65536, total - done);
            ByteBuffer slice = data.duplicate().order(data.order());
            slice.position(pos + done).limit(pos + done + n);
            VK10.vkCmdUpdateBuffer(cmd, buffer, offset + done, slice);
            done += n;
        }
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void renderFrame(FrameData f) {
        if (tableSlots == 0 || f.targetVkImage == 0) return;
        if (descriptorsDirty) writeAllDescriptors();

        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // fence against everything vanilla recorded before us
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            if (imagesNeedInit) {
                transitionAllImagesToGeneral(cmd, stack);
                imagesNeedInit = false;
            }

            f.resetHistory |= firstFrame;
            firstFrame = false;
            ByteBuffer packed = RtLayout.packGlobals(f, lightsPerSection, globalsScratch);
            VK10.vkCmdUpdateBuffer(cmd, buf[RtLayout.SSBO_GLOBALS], 0, packed);
            if (f.entityCount > 0 && f.entities != null) {
                updateChunked(cmd, buf[RtLayout.SSBO_ENTITIES], 0, f.entities);
            }
            recordUploads(cmd);
            barrier(cmd, stack,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);

            int p = parity;
            ByteBuffer pc = stack.malloc(16);

            dispatchPass(cmd, PASS_PATHTRACE, sets[PASS_PATHTRACE][p], rtW, rtH, pc, 0, 0);
            computeBarrier(cmd, stack);
            dispatchPass(cmd, PASS_TEMPORAL, sets[PASS_TEMPORAL][p], rtW, rtH, pc, 0, 0);
            computeBarrier(cmd, stack);

            int iters = f.denoiseIterations;
            for (int i = 0; i < iters; i++) {
                dispatchPass(cmd, PASS_ATROUS, sets[PASS_ATROUS][p * 2 + (i & 1)], rtW, rtH,
                        pc, 1 << i, i == 0 ? 1 : 0);
                computeBarrier(cmd, stack);
            }
            int resultVariant = iters == 0 ? 0 : ((iters & 1) == 1 ? 1 : 0);
            dispatchPass(cmd, PASS_COMPOSITE, sets[PASS_COMPOSITE][p * 2 + resultVariant], outW, outH, pc, 0, 0);

            barrier(cmd, stack,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT);

            // blit into MC's main target (both images live in GENERAL layout)
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            blit.get(0).dstSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            blit.get(0).srcOffsets(0).set(0, 0, 0);
            blit.get(0).srcOffsets(1).set(outW, outH, 1);
            if (config.vulkanFlipY) {
                blit.get(0).dstOffsets(0).set(0, outH, 0);
                blit.get(0).dstOffsets(1).set(outW, 0, 1);
            } else {
                blit.get(0).dstOffsets(0).set(0, 0, 0);
                blit.get(0).dstOffsets(1).set(outW, outH, 1);
            }
            VK10.vkCmdBlitImage(cmd, img[T_OUTPUT], VK10.VK_IMAGE_LAYOUT_GENERAL,
                    f.targetVkImage, VK10.VK_IMAGE_LAYOUT_GENERAL, blit, VK10.VK_FILTER_NEAREST);

            // make our writes visible to everything vanilla records after us
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        check(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
        encoder.execute(cmd);
        parity ^= 1;
    }

    private void dispatchPass(VkCommandBuffer cmd, int pass, long set, int w, int h,
                              ByteBuffer pc, int pcX, int pcY) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[pass]);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayouts[pass], 0, stack.longs(set), null);
        }
        pc.clear();
        pc.order(ByteOrder.nativeOrder()).putInt(0, pcX).putInt(4, pcY).putInt(8, 0).putInt(12, 0);
        VK10.vkCmdPushConstants(cmd, pipelineLayouts[pass], VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
        VK10.vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
    }

    private void computeBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        barrier(cmd, stack,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private void barrier(VkCommandBuffer cmd, MemoryStack stack,
                         int srcStage, int srcAccess, int dstStage, int dstAccess) {
        VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack);
        mb.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, mb, null, null);
    }

    private void transitionAllImagesToGeneral(VkCommandBuffer cmd, MemoryStack stack) {
        VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(T_COUNT, stack);
        for (int i = 0; i < T_COUNT; i++) {
            barriers.get(i).sType$Default()
                    .srcAccessMask(0)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(img[i]);
            barriers.get(i).subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
        }
        VK10.vkCmdPipelineBarrier(cmd,
                VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, barriers);
    }

    // ------------------------------------------------------------------ teardown

    @Override
    public void close() {
        try {
            mcDevice.graphicsQueue().waitIdle();
        } catch (Throwable ignored) {
        }
        closeQuiet();
    }

    private void closeQuiet() {
        if (device == null) return;
        for (int i = 0; i < 4; i++) {
            if (pipelines[i] != 0) VK10.vkDestroyPipeline(device, pipelines[i], null);
            if (pipelineLayouts[i] != 0) VK10.vkDestroyPipelineLayout(device, pipelineLayouts[i], null);
            if (setLayouts[i] != 0) VK10.vkDestroyDescriptorSetLayout(device, setLayouts[i], null);
            pipelines[i] = pipelineLayouts[i] = setLayouts[i] = 0;
        }
        if (descriptorPool != 0) {
            VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = 0;
        }
        for (int i = 0; i < T_COUNT; i++) destroyImage(i);
        for (int i = 0; i < RtLayout.SSBO_COUNT; i++) destroyBuffer(i);
    }

    private static void check(int vkResult, String what) {
        if (vkResult != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult " + vkResult);
        }
    }
}
