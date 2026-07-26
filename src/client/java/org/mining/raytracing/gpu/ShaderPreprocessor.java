package org.mining.raytracing.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal shader source loader: resolves {@code #include "file"} against
 * {@code assets/raytracing/shaders/} on the mod classpath and prepends a
 * per-backend preamble. Kernels are written once in Vulkan-compatible GLSL;
 * the preamble maps binding syntax per backend (docs/02 §3.3).
 */
public final class ShaderPreprocessor {
    private static final String ROOT = "/assets/raytracing/shaders/";

    public static final String GL_PREAMBLE = """
            #version 430 core
            #define BACKEND_GL 1
            #define SSBO(n) layout(std430, binding = n)
            #define IMG(n, fmt) layout(binding = n, fmt)
            #define PUSH_CONSTANTS uniform ivec4 u_pc;
            #define pc u_pc
            """;

    public static final String VK_PREAMBLE = """
            #version 450
            #define BACKEND_VK 1
            #define SSBO(n) layout(std430, set = 0, binding = n)
            #define IMG(n, fmt) layout(set = 0, binding = 7 + n, fmt)
            #define PUSH_CONSTANTS layout(push_constant) uniform PcBlock { ivec4 pc; };
            """;

    public static String load(String file, String preamble) {
        StringBuilder sb = new StringBuilder(preamble);
        sb.append("#line 1\n");
        appendResolved(sb, file, new HashSet<>());
        return sb.toString();
    }

    private static void appendResolved(StringBuilder out, String file, Set<String> seen) {
        if (!seen.add(file)) return; // include-once
        String src = readResource(ROOT + file);
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#include")) {
                int a = t.indexOf('"'), b = t.lastIndexOf('"');
                if (a < 0 || b <= a) throw new IllegalStateException("bad #include in " + file + ": " + t);
                appendResolved(out, t.substring(a + 1, b), seen);
            } else {
                out.append(line).append('\n');
            }
        }
    }

    private static String readResource(String path) {
        try (InputStream in = ShaderPreprocessor.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing shader resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed reading shader " + path, e);
        }
    }

    private ShaderPreprocessor() {}
}
