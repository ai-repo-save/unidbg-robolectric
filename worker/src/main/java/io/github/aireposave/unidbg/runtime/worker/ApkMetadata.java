package io.github.aireposave.unidbg.runtime.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import net.dongliu.apk.parser.ApkFile;

final class ApkMetadata {
    static ObjectNode resolve(ObjectNode request) throws Exception {
        ObjectNode normalized = request.deepCopy();
        if (!normalized.required("profile").isObject()) throw new IllegalArgumentException("profile must be a JSON object");
        ObjectNode profile = (ObjectNode) normalized.required("profile");
        if (!profile.has("package")) throw new IllegalArgumentException("Android profile requires package.name or package.apk");
        if (!profile.required("package").isObject()) throw new IllegalArgumentException("profile.package must be a JSON object");
        ObjectNode application = (ObjectNode) profile.required("package");
        if (application.has("apk")) {
            Path source = Path.of(ProfileValues.text(application.required("apk"), "package.apk"));
            if (!source.isAbsolute() || !Files.isRegularFile(source)) throw new IllegalArgumentException("package.apk must be an existing absolute APK path");
            try (ApkFile apk = new ApkFile(source.toFile())) {
                var metadata = apk.getApkMeta();
                text(application, "name", metadata.getPackageName());
                text(application, "versionName", metadata.getVersionName());
                number(application, "versionCode", metadata.getVersionCode());
                if (metadata.getTargetSdkVersion() != null) number(application, "targetSdk", Long.parseLong(metadata.getTargetSdkVersion()));
                var signatures = RequestData.JSON.createArrayNode();
                for (var signer : apk.getApkSingers()) {
                    for (var certificate : signer.getCertificateMetas()) {
                        signatures.add(Base64.getEncoder().encodeToString(certificate.getData()));
                    }
                }
                if (application.has("signatures") && !application.required("signatures").equals(signatures)) {
                    throw new IllegalArgumentException("package.signatures conflicts with APK certificate metadata");
                }
                application.set("signatures", signatures);
            }
        }
        if (!application.hasNonNull("name") || ProfileValues.text(application.required("name"), "package.name").isBlank()) {
            throw new IllegalArgumentException("Android profile requires package.name or APK package metadata");
        }
        return normalized;
    }
    private static void text(ObjectNode target, String field, String actual) {
        if (target.has(field) && !java.util.Objects.equals(target.get(field).textValue(), actual)) {
            throw new IllegalArgumentException("package." + field + " conflicts with APK metadata");
        }
        target.put(field, actual);
    }
    private static void number(ObjectNode target, String field, long actual) {
        if (target.has(field) && (!target.get(field).isIntegralNumber() || target.get(field).longValue() != actual)) {
            throw new IllegalArgumentException("package." + field + " conflicts with APK metadata");
        }
        target.put(field, actual);
    }
    private ApkMetadata() {}
}
