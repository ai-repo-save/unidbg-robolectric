package io.github.aireposave.unidbg.runtime.worker;

import android.app.Application;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.HostFileFacts;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.linux.file.DirectoryFileIO;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.unix.UnixEmulator;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.LoggerFactory;

final class GuestFiles implements IOResolver<AndroidFileIO> {
    private static final int O_TRUNC = 0x200;
    private final LinkedHashMap<String, Path> mounts = new LinkedHashMap<>();
    private final Path data;
    private final String guestData;

    GuestFiles(Application application, JsonNode profile) {
        data = application.getDataDir().toPath();
        guestData = profile.path("filesystem").path("guestDataDirectory").asText("/data/user/0/" + application.getPackageName());
        mounts.put(guestData, data);
        mounts.put(data.toString(), data);
        JsonNode pkg = profile.required("package");
        if (pkg.has("guestSourceDir")) {
            mounts.put(pkg.required("guestSourceDir").asText(), Path.of(pkg.required("apk").asText()));
        }
    }

    String guestPath(File file) {
        String path = file.getPath();
        String hostData = data.toString();
        if (path.equals(hostData)) return guestData;
        return path.startsWith(hostData + "/") ? guestData + path.substring(hostData.length()) : path;
    }

    void install(AndroidEmulator emulator) {
        mounts.forEach((guest, host) -> {
            if (!guest.startsWith("/") || !host.isAbsolute()) throw new IllegalArgumentException("Data mounts require absolute guest and host paths");
            LoggerFactory.getLogger(GuestFiles.class).info("event=file_mount_configured guest={} host={} source={}",
                    guest, host, host.equals(data) ? "Application.getDataDir" : "profile.package.apk");
        });
        emulator.getSyscallHandler().addIOResolver(this);
    }

    @Override public FileResult<AndroidFileIO> resolve(Emulator<AndroidFileIO> emulator, String pathname, int flags) {
        Path host = hostPath(pathname);
        return host == null ? null : open(host, pathname, flags);
    }

    @Override public FileQueryResult<FileMetadata> queryMetadata(Emulator<AndroidFileIO> emulator, String pathname, boolean followLinks) {
        Path host = hostPath(pathname);
        return host == null ? null : HostFileFacts.metadata(host.toFile(), followLinks);
    }

    @Override public FileQueryResult<List<DirectoryEntry>> queryDirectory(Emulator<AndroidFileIO> emulator, String pathname) {
        Path host = hostPath(pathname);
        return host == null ? null : HostFileFacts.directory(host.toFile());
    }

    private Path hostPath(String pathname) {
        for (var mount : mounts.entrySet()) {
            if (pathname.equals(mount.getKey())) return mount.getValue();
            if (Files.isDirectory(mount.getValue()) && pathname.startsWith(mount.getKey() + "/")) {
                return mount.getValue().resolve(pathname.substring(mount.getKey().length() + 1));
            }
        }
        return null;
    }

    private static FileResult<AndroidFileIO> open(Path host, String guest, int flags) {
        try {
            if (Files.exists(host) && (flags & IOConstants.O_CREAT) != 0 && (flags & IOConstants.O_EXCL) != 0) return FileResult.failed(UnixEmulator.EEXIST);
            if (!Files.exists(host)) {
                if ((flags & IOConstants.O_CREAT) == 0 || !Files.isDirectory(host.getParent())) return FileResult.failed(UnixEmulator.ENOENT);
                Files.createFile(host);
            }
            if (Files.isDirectory(host)) return FileResult.success(new DirectoryFileIO(flags, guest, host.toFile()));
            if ((flags & IOConstants.O_DIRECTORY) != 0) return FileResult.failed(UnixEmulator.ENOTDIR);
            if ((flags & O_TRUNC) != 0) {
                try (var ignored = Files.newByteChannel(host, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {}
            }
            return FileResult.success(new SimpleFileIO(flags, host.toFile(), guest));
        } catch (IOException failure) { throw new IllegalStateException("Guest file IO failed: " + guest + " -> " + host, failure); }
    }
}
