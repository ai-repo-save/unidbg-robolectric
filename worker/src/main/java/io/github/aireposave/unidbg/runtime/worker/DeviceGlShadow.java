package io.github.aireposave.unidbg.runtime.worker;

import android.opengl.GLES20;
import java.nio.Buffer;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowGLES20;

/** Device GL strings collected from the paired gms.unstable process. */
@Implements(GLES20.class)
public class DeviceGlShadow extends ShadowGLES20 {
    @Implementation
    public static String glGetString(int name) {
        String value = switch (name) {
            case 0x1F00 -> property("dg.glVendor", "Qualcomm");
            case 0x1F01 -> property("dg.glRenderer", "Adreno (TM) 630");
            case 0x1F02 -> property("dg.glVersion",
                    "OpenGL ES 3.2 V@0502.0 (GIT@8e5405b, I57aaec3440, 1621601959) (Date:05/21/21)");
            default -> "";
        };
        return value;
    }

    @Implementation
    public static int glGetError() {
        return 0;
    }

    @Implementation
    public static int glGetAttribLocation(int program, String name) {
        return 1;
    }

    @Implementation
    public static void glClearColor(float red, float green, float blue, float alpha) { }

    @Implementation
    public static void glClear(int mask) { }

    @Implementation
    public static void glDisable(int cap) { }

    @Implementation
    public static void glUseProgram(int program) { }

    @Implementation
    public static void glDrawArrays(int mode, int first, int count) { }

    @Implementation
    public static void glReadPixels(int x, int y, int width, int height, int format, int type, Buffer pixels) { }

    @Implementation
    public static void glShaderSource(int shader, String source) { }

    @Implementation
    public static void glCompileShader(int shader) { }

    @Implementation
    public static void glAttachShader(int program, int shader) { }

    @Implementation
    public static void glLinkProgram(int program) { }

    @Implementation
    public static void glEnableVertexAttribArray(int index) { }

    @Implementation
    public static void glDisableVertexAttribArray(int index) { }

    @Implementation
    public static void glDeleteShader(int shader) { }

    @Implementation
    public static void glDeleteProgram(int program) { }

    @Implementation
    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, Buffer pointer) { }

    private static String property(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null ? fallback : value;
    }
}
