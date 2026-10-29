package io.github.aireposave.unidbg.runtime.egl;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads EGL class-init natives on the loader that defines EGLImpl. */
public final class DeviceEglNatives {
    private static boolean loaded;

    private DeviceEglNatives() {}

    public static void load() {
        if (loaded) {
            return;
        }
        String resource = "/native/linux-x86_64/libdevice-egl.so";
        try (InputStream input = DeviceEglNatives.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing " + resource);
            }
            Path file = Files.createTempFile("libdevice-egl", ".so");
            file.toFile().deleteOnExit();
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
            System.load(file.toString());
            loaded = true;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load device EGL natives", failure);
        }
    }
}
