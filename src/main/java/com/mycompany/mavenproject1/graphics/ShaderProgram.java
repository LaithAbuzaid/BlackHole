package com.mycompany.mavenproject1.graphics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glDeleteShader;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetShaderi;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL43.GL_COMPUTE_SHADER;

/**
 * Loads GLSL graphics and OpenGL 4.3 compute shaders from classpath resources
 * under {@code /shaders/}, compiles and links them with detailed diagnostics,
 * and provides OpenGL error checks.
 */
public final class ShaderProgram implements AutoCloseable {

    private final int programId;
    private final String label;

    private ShaderProgram(int programId, String label) {
        this.programId = programId;
        this.label = label;
    }

    public static ShaderProgram fromResources(String vertexResource, String fragmentResource) {
        String vertSrc = readResource(vertexResource);
        String fragSrc = readResource(fragmentResource);
        int vs = compileStage(GL_VERTEX_SHADER, vertSrc, vertexResource);
        int fs = compileStage(GL_FRAGMENT_SHADER, fragSrc, fragmentResource);

        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);

        int linkStatus = glGetProgrami(program, GL_LINK_STATUS);
        String linkLog = glGetProgramInfoLog(program);
        glDeleteShader(vs);
        glDeleteShader(fs);

        if (linkStatus == GL_FALSE) {
            glDeleteProgram(program);
            throw new RuntimeException("Shader link error [" + vertexResource + " + " + fragmentResource + "]:\n" + linkLog);
        }
        if (linkLog != null && !linkLog.isBlank()) {
            System.err.println("[GLSL Link Info] " + vertexResource + " + " + fragmentResource + ":\n" + linkLog.trim());
        }

        checkGlError("ShaderProgram.link(" + fragmentResource + ")");
        return new ShaderProgram(program, fragmentResource);
    }

    public static ShaderProgram fromComputeResource(String computeResource) {
        String compSrc = readResource(computeResource);
        int cs = compileStage(GL_COMPUTE_SHADER, compSrc, computeResource);

        int program = glCreateProgram();
        glAttachShader(program, cs);
        glLinkProgram(program);

        int linkStatus = glGetProgrami(program, GL_LINK_STATUS);
        String linkLog = glGetProgramInfoLog(program);
        glDeleteShader(cs);

        if (linkStatus == GL_FALSE) {
            glDeleteProgram(program);
            throw new RuntimeException("Compute shader link error [" + computeResource + "]:\n" + linkLog);
        }
        if (linkLog != null && !linkLog.isBlank()) {
            System.err.println("[GLSL Compute Link Info] " + computeResource + ":\n" + linkLog.trim());
        }

        checkGlError("ShaderProgram.linkCompute(" + computeResource + ")");
        return new ShaderProgram(program, computeResource);
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
        if (programId != 0) {
            glDeleteProgram(programId);
        }
    }

    @Override
    public String toString() {
        return "ShaderProgram[" + label + ", id=" + programId + "]";
    }

    /** Verifies no OpenGL errors occurred during startup or pass setup. */
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
            sb.append("0x").append(Integer.toHexString(err));
        }
        if (found) {
            throw new RuntimeException(sb.toString());
        }
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
