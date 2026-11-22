package io.github.laithabuzaid.blackhole.graphics;

import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallback;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.GL_INVALID_FRAMEBUFFER_OPERATION;
import static org.lwjgl.opengl.GL43.GL_COMPUTE_SHADER;
import static org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT;
import static org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS;
import static org.lwjgl.opengl.GL43.GL_DEBUG_SEVERITY_NOTIFICATION;

/**
 * Loads GLSL graphics and OpenGL 4.3 compute shaders from classpath resources
 * under {@code /shaders/}, compiles and links them with detailed diagnostics,
 * leak-free error handling, idempotent cleanup, and optional {@code GL_KHR_debug} hooks.
 */
public final class ShaderProgram implements AutoCloseable {

    private final int programId;
    private final String label;
    private boolean closed = false;

    private ShaderProgram(int programId, String label) {
        this.programId = programId;
        this.label = label;
    }

    public static ShaderProgram fromResources(String vertexResource, String fragmentResource) {
        return fromResourcesWithDefines(vertexResource, fragmentResource, null);
    }

    /**
     * Compiles a vertex + fragment shader pair, optionally injecting preprocessor directives
     * (e.g. {@code "#define USE_DOPRI54 1\n"}) immediately after the fragment shader's {@code #version} line.
     */
    public static ShaderProgram fromResourcesWithDefines(String vertexResource, String fragmentResource,
                                                         String fragmentDefines) {
        String vertSrc = readResource(vertexResource);
        String fragSrc = injectDefines(readResource(fragmentResource), fragmentDefines);
        int vs = 0;
        int fs = 0;
        int program = 0;
        boolean ok = false;
        try {
            vs = compileStage(GL_VERTEX_SHADER, vertSrc, vertexResource);
            fs = compileStage(GL_FRAGMENT_SHADER, fragSrc, fragmentResource);

            program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);

            int linkStatus = glGetProgrami(program, GL_LINK_STATUS);
            String linkLog = glGetProgramInfoLog(program);
            glDetachShader(program, vs);
            glDetachShader(program, fs);
            glDeleteShader(vs);
            glDeleteShader(fs);
            vs = 0;
            fs = 0;

            if (linkStatus == GL_FALSE) {
                throw new RuntimeException("Shader link error [" + vertexResource + " + " + fragmentResource + "]:\n" + linkLog);
            }
            if (linkLog != null && !linkLog.isBlank()) {
                System.err.println("[GLSL Link Info] " + vertexResource + " + " + fragmentResource + ":\n" + linkLog.trim());
            }

            checkGlError("ShaderProgram.link(" + fragmentResource + ")");
            ok = true;
            return new ShaderProgram(program, fragmentResource);
        } finally {
            if (!ok) {
                if (vs != 0) glDeleteShader(vs);
                if (fs != 0) glDeleteShader(fs);
                if (program != 0) glDeleteProgram(program);
            }
        }
    }

    private static String injectDefines(String source, String defines) {
        if (defines == null || defines.isBlank()) {
            return source;
        }
        int nl = source.indexOf('\n');
        if (nl < 0) {
            return source + "\n" + defines;
        }
        return source.substring(0, nl + 1) + defines + "\n" + source.substring(nl + 1);
    }

    public static ShaderProgram fromComputeResource(String computeResource) {
        String compSrc = readResource(computeResource);
        int cs = 0;
        int program = 0;
        boolean ok = false;
        try {
            cs = compileStage(GL_COMPUTE_SHADER, compSrc, computeResource);
            program = glCreateProgram();
            glAttachShader(program, cs);
            glLinkProgram(program);

            int linkStatus = glGetProgrami(program, GL_LINK_STATUS);
            String linkLog = glGetProgramInfoLog(program);
            glDetachShader(program, cs);
            glDeleteShader(cs);
            cs = 0;

            if (linkStatus == GL_FALSE) {
                throw new RuntimeException("Compute shader link error [" + computeResource + "]:\n" + linkLog);
            }
            if (linkLog != null && !linkLog.isBlank()) {
                System.err.println("[GLSL Compute Link Info] " + computeResource + ":\n" + linkLog.trim());
            }

            checkGlError("ShaderProgram.linkCompute(" + computeResource + ")");
            ok = true;
            return new ShaderProgram(program, computeResource);
        } finally {
            if (!ok) {
                if (cs != 0) glDeleteShader(cs);
                if (program != 0) glDeleteProgram(program);
            }
        }
    }

    /** Optionally enables OpenGL 4.3 core debug callbacks when {@code -Dblackhole.glDebug=true} is set. */
    public static void installDebugCallbackIfEnabled() {
        if (!Boolean.getBoolean("blackhole.glDebug")) {
            return;
        }
        try {
            glEnable(GL_DEBUG_OUTPUT);
            glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
            GL43.glDebugMessageCallback((source, type, id, severity, length, message, userParam) -> {
                if (severity == GL_DEBUG_SEVERITY_NOTIFICATION) {
                    return;
                }
                String text = GLDebugMessageCallback.getMessage(length, message);
                System.err.println("[GL_KHR_debug] id=" + id + " severity=0x"
                        + Integer.toHexString(severity) + ": " + text);
            }, 0L);
        } catch (Throwable t) {
            System.err.println("[GL_KHR_debug] Unable to install debug callback: " + t.getMessage());
        }
    }

    public int id() {
        return programId;
    }

    public void use() {
        glUseProgram(programId);
    }

    public int uniformLocation(String name) {
        return glGetUniformLocation(programId, name);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (programId != 0) {
            glDeleteProgram(programId);
        }
    }

    @Override
    public String toString() {
        return "ShaderProgram[" + label + ", id=" + programId + "]";
    }

    /** Verifies no OpenGL errors occurred and decodes standard enum names cleanly. */
    public static void checkGlError(String stage) {
        int err;
        boolean found = false;
        StringBuilder sb = null;
        while ((err = glGetError()) != GL_NO_ERROR) {
            if (!found) {
                found = true;
                sb = new StringBuilder("OpenGL error at ").append(stage).append(": ");
            } else {
                sb.append(", ");
            }
            sb.append(decodeGlError(err)).append(" (0x").append(Integer.toHexString(err)).append(')');
        }
        if (found) {
            throw new RuntimeException(sb.toString());
        }
    }

    public static String decodeGlError(int err) {
        return switch (err) {
            case GL_INVALID_ENUM -> "GL_INVALID_ENUM";
            case GL_INVALID_VALUE -> "GL_INVALID_VALUE";
            case GL_INVALID_OPERATION -> "GL_INVALID_OPERATION";
            case GL_STACK_OVERFLOW -> "GL_STACK_OVERFLOW";
            case GL_STACK_UNDERFLOW -> "GL_STACK_UNDERFLOW";
            case GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY";
            case GL_INVALID_FRAMEBUFFER_OPERATION -> "GL_INVALID_FRAMEBUFFER_OPERATION";
            default -> "GL_ERROR";
        };
    }

    private static int compileStage(int type, String source, String resourcePath) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        int status = glGetShaderi(shader, GL_COMPILE_STATUS);
        String log = glGetShaderInfoLog(shader);
        if (status == GL_FALSE) {
            glDeleteShader(shader);
            throw new RuntimeException("GLSL compile error in " + resourcePath + ":\n" + log);
        }
        if (log != null && !log.isBlank()) {
            System.err.println("[GLSL Compile Info] " + resourcePath + ":\n" + log.trim());
        }
        return shader;
    }

    private static String readResource(String path) {
        try (InputStream in = ShaderProgram.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing shader classpath resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader resource: " + path, e);
        }
    }
}
