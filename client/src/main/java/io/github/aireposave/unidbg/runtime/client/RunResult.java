package io.github.aireposave.unidbg.runtime.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

public record RunResult(Status status, int exitCode, long pid, JsonNode payload,
                        JsonNode failure, Path outputDirectory) {
    public enum Status { COMPLETED, FAILED, CANCELLED }
}
