package io.github.aireposave.unidbg.runtime.worker;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aireposave.unidbg.runtime.client.RuntimeVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

final class InstallationVerifier {
    static JsonNode verify(Path runtime) throws Exception {
        JsonNode manifest = RequestData.JSON.readTree(runtime.resolve("runtime-manifest.json").toFile());
        if (!manifest.required("version").asText().equals(RuntimeVersion.VERSION)) throw new IllegalArgumentException("Runtime installation version mismatch");
        for (JsonNode entry : manifest.required("files")) {
            Path file = runtime.resolve(entry.required("file").asText());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(entry.required("sha256").asText())) {
                throw new IllegalArgumentException("Runtime installation checksum mismatch: " + file);
            }
        }
        return RequestData.JSON.createObjectNode().put("version", RuntimeVersion.VERSION)
                .put("filesVerified", manifest.required("files").size()).put("sourceCommit", manifest.required("sourceCommit").asText())
                .put("sourceDirty", manifest.required("sourceDirty").booleanValue());
    }
    private InstallationVerifier() {}
}
