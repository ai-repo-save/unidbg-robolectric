package org.example.unidbg;

import android.app.Application;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.file.linux.IOConstants;
import io.github.aireposave.unidbg.runtime.spi.WorkerSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

final class NativeFilesProbe {
    static void verify(AndroidEmulator emulator, AndroidResolver resolver, Application application,
                       WorkerSession session, String model) throws Exception {
        var libc = emulator.getMemory().load(resolver.resolveLibrary(emulator, "libc.so"), false);
        var memory = emulator.getMemory().malloc(65536, true);
        try {
            var pointer = memory.getPointer();
            var properties = session.profile().required("nativeProperties").fields();
            while (properties.hasNext()) {
                var property = properties.next();
                String value = property.getValue().textValue();
                assertEquals(value.getBytes(StandardCharsets.UTF_8).length,
                        libc.findSymbolByName("__system_property_get").call(emulator, property.getKey(), pointer).intValue());
                assertEquals(value, pointer.getString(0));
            }
            assertEquals(2, libc.findSymbolByName("__system_property_get").call(emulator, "ro.build.version.sdk", pointer).intValue());
            assertEquals("30", pointer.getString(0));
            String guest = session.profile().path("filesystem").required("guestDataDirectory").asText();
            Path data = application.getDataDir().toPath();
            try (var entries = Files.walk(data)) {
                for (Path path : entries.filter(Files::isRegularFile).toList()) {
                    byte[] host = Files.readAllBytes(path);
                    int fd = libc.findSymbolByName("open").call(emulator, guest + "/" + data.relativize(path), IOConstants.O_RDONLY).intValue();
                    assertEquals(host.length, libc.findSymbolByName("read").call(emulator, fd, pointer, host.length).intValue());
                    assertArrayEquals(host, pointer.getByteArray(0, host.length));
                    assertEquals(0, libc.findSymbolByName("close").call(emulator, fd).intValue());
                    Path evidence = session.outputDirectory().resolve("native-read").resolve(data.relativize(path));
                    Files.createDirectories(evidence.getParent());
                    Files.write(evidence, pointer.getByteArray(0, host.length));
                }
            }
            Path file = application.getFilesDir().toPath().resolve("cross-boundary.bin");
            byte[] content = ("native-" + model).getBytes(StandardCharsets.UTF_8);
            pointer.write(0, content, 0, content.length);
            int fd = libc.findSymbolByName("open").call(emulator, guest + "/" + data.relativize(file),
                    IOConstants.O_WRONLY | IOConstants.O_CREAT | IOConstants.O_EXCL, 0600).intValue();
            assertEquals(content.length, libc.findSymbolByName("write").call(emulator, fd, pointer, content.length).intValue());
            assertEquals(0, libc.findSymbolByName("close").call(emulator, fd).intValue());
            assertArrayEquals(content, Files.readAllBytes(file));
        } finally { memory.free(); }
    }
    private NativeFilesProbe() {}
}
