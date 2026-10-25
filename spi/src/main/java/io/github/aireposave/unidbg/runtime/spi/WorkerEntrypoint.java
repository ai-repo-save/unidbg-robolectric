package io.github.aireposave.unidbg.runtime.spi;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface WorkerEntrypoint {
    JsonNode run(WorkerSession session, JsonNode arguments) throws Exception;
}
