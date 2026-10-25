package io.github.aireposave.unidbg.runtime.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;

final class RequestData {
    static final ObjectMapper JSON = new ObjectMapper();
    static JsonNode read() throws IOException {
        return JSON.readTree(Path.of(System.getProperty("unidbg.request")).toFile());
    }
    static Path output(JsonNode request) { return Path.of(request.required("outputDirectory").asText()); }
    private RequestData() {}
}
