package io.github.aireposave.unidbg.runtime.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.LoggerFactory;

final class EvidenceFiles {
    static void preserveApplication(Path output) throws IOException {
        Path location = output.resolve("application-data.location");
        if (!Files.isRegularFile(location)) return;
        Path source = Path.of(Files.readString(location));
        Path destination = output.resolve("application-data");
        int count = 0;
        try (var entries = Files.walk(source)) {
            for (Path file : entries.filter(Files::isRegularFile).toList()) {
                Path target = destination.resolve(source.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        LoggerFactory.getLogger(EvidenceFiles.class).info("event=application_data_preserved source={} evidence={} files={}", source, destination, count);
    }
    private EvidenceFiles() {}
}
