package io.github.aireposave.unidbg.runtime.worker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

/** Pbuffer EGL that lets DroidGuard.g() reach glGetString. */
public final class DeviceEgl implements EGL10 {
    private static final EGLDisplay DISPLAY = new EGLDisplay() { };
    private static final EGLConfig CONFIG = new EGLConfig() { };
    private static final EGLContext CONTEXT = new EGLContext() {
        @Override public GL getGL() { return null; }
    };
    private static final EGLSurface SURFACE = new EGLSurface() { };

    public static void install() {
        try {
            Class<?> unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Field instance = EGLContext.class.getDeclaredField("EGL_INSTANCE");
            Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, instance);
            long offset = (long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, instance);
            Method put = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            put.invoke(unsafe, base, offset, new DeviceEgl());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot install device EGL", failure);
        }
    }

    @Override public boolean eglInitialize(EGLDisplay display, int[] majorMinor) { return true; }
    @Override public boolean eglQueryContext(EGLDisplay display, EGLContext context, int attribute, int[] value) { return true; }
    @Override public boolean eglQuerySurface(EGLDisplay display, EGLSurface surface, int attribute, int[] value) { return true; }
    @Override public boolean eglChooseConfig(EGLDisplay display, int[] attribList, EGLConfig[] configs, int configSize, int[] numConfig) {
        if (configs != null && configs.length > 0) configs[0] = config();
        if (numConfig != null && numConfig.length > 0) numConfig[0] = 1;
        return true;
    }
    @Override public boolean eglGetConfigAttrib(EGLDisplay display, EGLConfig config, int attribute, int[] value) { return true; }
    @Override public boolean eglGetConfigs(EGLDisplay display, EGLConfig[] configs, int configSize, int[] numConfig) { return true; }
    @Override public int eglGetError() { return EGL_SUCCESS; }
    @Override public boolean eglDestroyContext(EGLDisplay display, EGLContext context) { return true; }
    @Override public boolean eglDestroySurface(EGLDisplay display, EGLSurface surface) { return true; }
    @Override public boolean eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context) { return true; }
    @Override public String eglQueryString(EGLDisplay display, int name) { return ""; }
    @Override public boolean eglSwapBuffers(EGLDisplay display, EGLSurface surface) { return true; }
    @Override public boolean eglTerminate(EGLDisplay display) { return true; }
    @Override public boolean eglCopyBuffers(EGLDisplay display, EGLSurface surface, Object nativePixmap) { return true; }
    @Override public boolean eglWaitGL() { return true; }
    @Override public boolean eglWaitNative(int engine, Object bindTarget) { return true; }
    @Override public EGLContext eglCreateContext(EGLDisplay display, EGLConfig config, EGLContext share, int[] attribList) { return context(); }
    @Override public EGLSurface eglCreatePbufferSurface(EGLDisplay display, EGLConfig config, int[] attribList) { return surface(); }
    @Override public EGLSurface eglCreatePixmapSurface(EGLDisplay display, EGLConfig config, Object nativePixmap, int[] attribList) { return surface(); }
    @Override public EGLSurface eglCreateWindowSurface(EGLDisplay display, EGLConfig config, Object nativeWindow, int[] attribList) { return surface(); }
    @Override public EGLDisplay eglGetDisplay(Object nativeDisplay) {
        return display();
    }

    @Override public EGLContext eglGetCurrentContext() { return context(); }
    @Override public EGLDisplay eglGetCurrentDisplay() { return display(); }
    @Override public EGLSurface eglGetCurrentSurface(int readdraw) { return surface(); }
    @Override public boolean eglReleaseThread() { return true; }

    private static EGLDisplay display() { return DISPLAY; }
    private static EGLConfig config() { return CONFIG; }
    private static EGLContext context() { return CONTEXT; }
    private static EGLSurface surface() { return SURFACE; }
}
