package org.example.unidbg;

import android.app.Application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.file.ByteArrayFileIO;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import com.github.unidbg.file.linux.IOConstants;
import io.github.aireposave.unidbg.runtime.spi.WorkerSession;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

final class NativeFilesProbe {
    static void verify(AndroidEmulator emulator, AndroidResolver resolver, Application application,
                       WorkerSession session, String model, Arm64Jni jni) throws Exception {
        verifyPaths(application, session, jni);
        var libc = emulator.getMemory().load(resolver.resolveLibrary(emulator, "libc.so"), false);
        var memory = emulator.getMemory().malloc(65536, true);
        try {
            var pointer = memory.getPointer();
            var credentials = session.profile().required("processCredentials");
            assertEquals(session.profile().required("package").required("uid").intValue(), libc.findSymbolByName("getuid").call(emulator).intValue());
            assertEquals(credentials.required("euid").intValue(), libc.findSymbolByName("geteuid").call(emulator).intValue());
            assertEquals(credentials.required("gid").intValue(), libc.findSymbolByName("getgid").call(emulator).intValue());
            assertEquals(credentials.required("egid").intValue(), libc.findSymbolByName("getegid").call(emulator).intValue());
            int[] groups = {3003, 9997};
            assertEquals(groups.length, libc.findSymbolByName("getgroups").call(emulator, groups.length, pointer).intValue());
            assertArrayEquals(groups, pointer.getIntArray(0, groups.length));
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
            verifyQueries(emulator, application, session, jni, libc, pointer);
        } finally { memory.free(); }
    }
    private static void verifyPaths(Application application, WorkerSession session, Arm64Jni jni) throws Exception {
        String data = application.getDataDir().getPath();
        String guestData = session.profile().required("filesystem").required("guestDataDirectory").textValue();
        String[] paths = {"", "relative\0path", "relative//./child/../leaf/", "/outside//./child/../leaf/",
                guestData + "/files/\0leaf", data + "-sibling/\0leaf", data + "\0suffix", data, data + "///",
                data + "//files/./child/../leaf/", data + "/files/\0leaf", data + "/../sibling", data + "/."};
        var json = new ObjectMapper();
        var results = json.createObjectNode();
        var constructed = results.putArray("constructed");
        var wrapped = results.putArray("wrapped");
        for (String path : paths) {
            int file = jni.newObject("java/io/File", "(Ljava/lang/String;)V", jni.wrap(path));
            constructed.add((String) jni.value(jni.objectCall(file, "getPath", "()Ljava/lang/String;")));
            wrapped.add((String) jni.value(jni.objectCall(jni.wrap(new File(path)), "getPath", "()Ljava/lang/String;")));
        }
        json.writeValue(session.outputDirectory().resolve("file-paths.json").toFile(), results);
    }

    private static void verifyQueries(AndroidEmulator emulator, Application application, WorkerSession session,
                                      Arm64Jni jni, Module libc, Pointer buffer) throws Exception {
        String guestData = session.profile().required("filesystem").required("guestDataDirectory").textValue();
        Path directory = application.getFilesDir().toPath().resolve("query-directory");
        Files.createDirectory(directory);
        Path backing = directory.resolve("content.bin");
        byte[] content = "file-query-content".getBytes(StandardCharsets.UTF_8);
        Files.write(backing, content);
        Files.write(directory.resolve("empty.bin"), new byte[0]);
        FileTime modified = FileTime.fromMillis(1700000000000L);
        Files.setLastModifiedTime(backing, modified);
        Files.setPosixFilePermissions(backing, PosixFilePermissions.fromString("rwxrwxrwx"));
        String guestDirectory = guestData + "/files/query-directory";
        int physical = jni.wrap(backing.toFile());
        assertEquals(guestDirectory + "/content.bin", jni.value(jni.objectCall(physical, "getPath", "()Ljava/lang/String;")));
        int parent = jni.newObject("java/io/File", "(Ljava/lang/String;)V", jni.wrap(guestDirectory));
        int file = jni.newObject("java/io/File", "(Ljava/io/File;Ljava/lang/String;)V", parent, jni.wrap("content.bin"));
        assertEquals(guestDirectory + "/content.bin", jni.value(jni.objectCall(file, "getPath", "()Ljava/lang/String;")));
        assertEquals(true, jni.booleanCall(file, "exists", "()Z"));
        assertEquals(true, jni.booleanCall(file, "isFile", "()Z"));
        assertEquals(false, jni.booleanCall(file, "isDirectory", "()Z"));
        assertEquals(true, jni.booleanCall(file, "canRead", "()Z"));
        assertEquals(true, jni.booleanCall(file, "canWrite", "()Z"));
        assertEquals(true, jni.booleanCall(file, "canExecute", "()Z"));
        assertEquals(content.length, jni.longCall(file, "length", "()J"));
        assertEquals(modified.toMillis(), jni.longCall(file, "lastModified", "()J"));
        assertEquals(true, jni.booleanCall(parent, "isDirectory", "()Z"));
        String[] names = (String[]) jni.value(jni.objectCall(parent, "list", "()[Ljava/lang/String;"));
        Arrays.sort(names);
        assertArrayEquals(new String[] {"content.bin", "empty.bin"}, names);
        File[] children = (File[]) jni.value(jni.objectCall(parent, "listFiles", "()[Ljava/io/File;"));
        assertArrayEquals(new String[] {guestDirectory + "/content.bin", guestDirectory + "/empty.bin"},
                Arrays.stream(children).map(File::getPath).sorted().toArray(String[]::new));
        int empty = jni.wrap(new File(guestDirectory, "empty.bin"));
        assertEquals(true, jni.booleanCall(empty, "exists", "()Z"));
        assertEquals(0L, jni.longCall(empty, "length", "()J"));
        int missing = jni.wrap(new File(guestDirectory, "missing.bin"));
        assertEquals(false, jni.booleanCall(missing, "exists", "()Z"));
        assertEquals(false, jni.booleanCall(missing, "isFile", "()Z"));
        assertEquals(false, jni.booleanCall(missing, "isDirectory", "()Z"));
        assertEquals(0L, jni.longCall(missing, "length", "()J"));
        assertEquals(0L, jni.longCall(missing, "lastModified", "()J"));
        assertEquals(null, jni.value(jni.objectCall(missing, "list", "()[Ljava/lang/String;")));
        assertEquals(null, jni.value(jni.objectCall(missing, "listFiles", "()[Ljava/io/File;")));
        Files.setPosixFilePermissions(backing, PosixFilePermissions.fromString("---------"));
        try {
            assertEquals(false, jni.booleanCall(file, "canRead", "()Z"));
            assertEquals(false, jni.booleanCall(file, "canWrite", "()Z"));
            assertEquals(false, jni.booleanCall(file, "canExecute", "()Z"));
        } finally {
            Files.setPosixFilePermissions(backing, PosixFilePermissions.fromString("rw-r--r--"));
        }
        verifyUnknown(emulator, session, jni, libc, buffer);
        Files.write(session.outputDirectory().resolve("query-content.bin"), Files.readAllBytes(backing));
    }

    private static void verifyUnknown(AndroidEmulator emulator, WorkerSession session, Arm64Jni jni,
                                      Module libc, Pointer buffer) throws Exception {
        String path = "/runtime-query/unknown";
        if (!emulator.getFileSystem().mkdir(new File(path).getParent(), 0)) {
            throw new IllegalStateException("Creating controlled query ancestor failed: " + path);
        }
        emulator.getSyscallHandler().addIOResolver(new IOResolver<AndroidFileIO>() {
            private int open;
            @Override public FileResult<AndroidFileIO> resolve(Emulator<AndroidFileIO> target, String pathname, int flags) {
                if (!pathname.equals(path)) return null;
                int index = open++;
                if (index == 0) return FileResult.failed(UnixEmulator.EINTR);
                return FileResult.success(new ByteArrayFileIO(flags, pathname,
                        ("open-" + index).getBytes(StandardCharsets.UTF_8)) {
                    @Override public FileQueryResult<FileMetadata> queryMetadata() { return FileQueryResult.unknown(); }
                });
            }
            @Override public FileQueryResult<FileMetadata> queryMetadata(Emulator<AndroidFileIO> target, String pathname, boolean followLinks) {
                if (pathname.equals(path)) return FileQueryResult.unknown();
                if (pathname.equals(path + "-partial")) return FileQueryResult.success(FileMetadata.builder().build());
                return null;
            }
            @Override public FileQueryResult<List<DirectoryEntry>> queryDirectory(Emulator<AndroidFileIO> target, String pathname) {
                return pathname.equals(path) ? FileQueryResult.unknown() : null;
            }
        });
        int unknown = jni.wrap(new File(path));
        assertThrows(UnsupportedOperationException.class, () -> jni.booleanCall(unknown, "exists", "()Z"));
        assertThrows(UnsupportedOperationException.class, () -> jni.objectCall(unknown, "list", "()[Ljava/lang/String;"));
        int partial = jni.wrap(new File(path + "-partial"));
        assertEquals(true, jni.booleanCall(partial, "exists", "()Z"));
        assertThrows(UnsupportedOperationException.class, () -> jni.booleanCall(partial, "isFile", "()Z"));
        assertThrows(UnsupportedOperationException.class, () -> jni.booleanCall(partial, "canRead", "()Z"));
        assertThrows(UnsupportedOperationException.class, () -> jni.longCall(partial, "length", "()J"));
        assertThrows(UnsupportedOperationException.class, () -> jni.longCall(partial, "lastModified", "()J"));
        assertEquals(-1, libc.findSymbolByName("open").call(emulator, path, IOConstants.O_RDONLY).intValue());
        var errno = UnidbgPointer.pointer(emulator, libc.findSymbolByName("__errno").call(emulator).longValue());
        assertEquals(UnixEmulator.EINTR, errno.getInt(0));
        var observed = new ByteArrayOutputStream();
        for (int index = 1; index <= 2; index++) {
            assertThrows(UnsupportedOperationException.class, () -> jni.booleanCall(unknown, "exists", "()Z"));
            int fd = libc.findSymbolByName("open").call(emulator, path, IOConstants.O_RDONLY).intValue();
            byte[] content = ("open-" + index).getBytes(StandardCharsets.UTF_8);
            try {
                assertEquals(content.length, libc.findSymbolByName("read").call(emulator, fd, buffer, content.length).intValue());
                byte[] actual = buffer.getByteArray(0, content.length);
                assertArrayEquals(content, actual);
                observed.write(actual);
            } finally {
                assertEquals(0, libc.findSymbolByName("close").call(emulator, fd).intValue());
            }
        }
        Files.write(session.outputDirectory().resolve("controlled-query.bin"), observed.toByteArray());
    }

    private NativeFilesProbe() {}
}
