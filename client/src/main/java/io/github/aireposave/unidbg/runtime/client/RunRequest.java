package io.github.aireposave.unidbg.runtime.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RunRequest(Path runtimeDirectory, String entrypoint, List<Path> classpath,
                         JsonNode arguments, Mode mode, JsonNode profile,
                         Path workingDirectory, Path outputDirectory,
                         Duration cancellationGrace, Duration terminationWait,
                         Map<String, String> systemProperties, String traceId) {
    public RunRequest {
        Objects.requireNonNull(runtimeDirectory);
        Objects.requireNonNull(entrypoint);
        classpath = List.copyOf(classpath);
        Objects.requireNonNull(arguments);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(profile);
        Objects.requireNonNull(workingDirectory);
        Objects.requireNonNull(outputDirectory);
        if (cancellationGrace.isNegative() || terminationWait.isNegative() || terminationWait.isZero()) {
            throw new IllegalArgumentException("Cancellation grace must be nonnegative and termination wait positive");
        }
        systemProperties = Map.copyOf(systemProperties);
        Objects.requireNonNull(traceId);
    }
    public enum Mode { NATIVE_ONLY, ANDROID }
}
