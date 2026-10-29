package io.github.aireposave.unidbg.runtime.worker;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.linux.AndroidSyscallHandler;
import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.unix.IO;
import io.github.aireposave.unidbg.runtime.spi.jni.JniCall;
import io.github.aireposave.unidbg.runtime.spi.jni.JniConfiguration;
import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GuestFileJni {
    private static final Logger LOG = LoggerFactory.getLogger(GuestFileJni.class);
    private static final int S_IFMT = 0170000;
    private final VM vm;
    private final AndroidEmulator emulator;
    private final AndroidSyscallHandler queries;
    private final GuestFiles files;

    private GuestFileJni(VM vm, GuestFiles files) {
        this.vm = vm;
        this.emulator = (AndroidEmulator) vm.getEmulator();
        this.queries = (AndroidSyscallHandler) emulator.getSyscallHandler();
        this.files = files;
    }

    static void install(VM vm, GuestFiles files, JniConfiguration configuration) {
        var adapter = new GuestFileJni(vm, files);
        configuration.handle("java/io/File->getPath()Ljava/lang/String;", call -> adapter.wrap(adapter.path(call)));
        configuration.handle("java/io/File->exists()Z", call -> adapter.access(call, 0));
        configuration.handle("java/io/File->canRead()Z", call -> adapter.access(call, 4));
        configuration.handle("java/io/File->canWrite()Z", call -> adapter.access(call, 2));
        configuration.handle("java/io/File->canExecute()Z", call -> adapter.access(call, 1));
        configuration.handle("java/io/File->isFile()Z", call -> adapter.isType(call, IO.S_IFREG));
        configuration.handle("java/io/File->isDirectory()Z", call -> adapter.isType(call, IO.S_IFDIR));
        configuration.handle("java/io/File->length()J", adapter::length);
        configuration.handle("java/io/File->lastModified()J", adapter::lastModified);
        configuration.handle("java/io/File->list()[Ljava/lang/String;", call -> adapter.list(call, false));
        configuration.handle("java/io/File->listFiles()[Ljava/io/File;", call -> adapter.list(call, true));
    }

    private String path(JniCall call) {
        File file = (File) ProxyDvmObject.unpack((DvmObject<?>) call.arguments()[1]);
        return files == null ? file.getPath() : files.guestPath(file);
    }

    private Object wrap(Object value) { return ProxyDvmObject.createObject(vm, value); }

    private boolean access(JniCall call, int mode) {
        String path = path(call);
        try {
            queries.checkAccessAt(emulator, IO.AT_FDCWD, path, mode);
            LOG.debug("event=guest_file_query descriptor={} path={} result=accessible mode={}", call.signature(), path, mode);
            return true;
        } catch (LinuxErrnoException failure) {
            failed(call, path, failure.getErrno());
            return false;
        }
    }

    private FileMetadata metadata(JniCall call) {
        String path = path(call);
        try {
            FileQueryResult<FileMetadata> result = queries.queryMetadataAt(emulator, IO.AT_FDCWD, path, 0);
            LOG.debug("event=guest_file_query descriptor={} path={} status={} errno={} mode={} size={} mtime={}",
                    call.signature(), path, result.status(), result.errno,
                    result.value == null ? null : result.value.mode,
                    result.value == null ? null : result.value.size,
                    result.value == null ? null : result.value.mtime);
            return switch (result.status()) {
                case PRESENT, FALLBACK -> result.value;
                case ERROR -> null;
                case UNKNOWN -> throw unavailable(call, path, "metadata");
            };
        } catch (LinuxErrnoException failure) {
            failed(call, path, failure.getErrno());
            return null;
        }
    }

    private boolean isType(JniCall call, int type) {
        FileMetadata metadata = metadata(call);
        return metadata != null && (metadata.mode.orElseThrow(() -> unavailable(call, path(call), "mode")) & S_IFMT) == type;
    }

    private long length(JniCall call) {
        FileMetadata metadata = metadata(call);
        return metadata == null ? 0L : metadata.size.orElseThrow(() -> unavailable(call, path(call), "size"));
    }

    private long lastModified(JniCall call) {
        FileMetadata metadata = metadata(call);
        return metadata == null ? 0L : metadata.mtime.orElseThrow(() -> unavailable(call, path(call), "mtime")).toMillis();
    }

    private Object list(JniCall call, boolean fileObjects) {
        String path = path(call);
        try {
            var entries = queries.listDirectoryAt(emulator, IO.AT_FDCWD, path);
            LOG.debug("event=guest_file_query descriptor={} path={} result=directory entries={}",
                    call.signature(), path, entries.stream().map(entry -> entry.name).toList());
            if (fileObjects) return wrap(entries.stream().map(entry -> new File(path, entry.name)).toArray(File[]::new));
            return wrap(entries.stream().map(entry -> entry.name).toArray(String[]::new));
        } catch (LinuxErrnoException failure) {
            failed(call, path, failure.getErrno());
            return null;
        }
    }

    private static UnsupportedOperationException unavailable(JniCall call, String path, String field) {
        return new UnsupportedOperationException("File query unavailable: " + call.signature() + " path=" + path + " field=" + field);
    }

    private static void failed(JniCall call, String path, int errno) {
        LOG.debug("event=guest_file_query descriptor={} path={} result=error errno={}", call.signature(), path, errno);
    }
}
