package io.github.aireposave.unidbg.runtime.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import net.dongliu.apk.parser.ApkFile;

final class ApkMetadata {
    static ObjectNode resolve(ObjectNode request) throws Exception {
        ObjectNode normalized = request.deepCopy();
        if (!normalized.required("profile").isObject()) throw new IllegalArgumentException("profile must be a JSON object");
        ObjectNode profile = (ObjectNode) normalized.required("profile");
        if (!profile.has("package")) throw new IllegalArgumentException("Android profile requires package.name or package.apk");
        String host = resolvePackage(profile.required("package"), "profile.package");
        var names = new HashSet<String>();
        names.add(host);
        if (profile.has("packages")) {
            var packages = profile.required("packages");
            if (!packages.isArray()) throw new IllegalArgumentException("profile.packages must be a JSON array");
            for (int i = 0; i < packages.size(); i++) {
                String name = resolvePackage(packages.get(i), "profile.packages[" + i + "]");
                if (name.equals(host)) throw new IllegalArgumentException("profile.packages duplicates host package: " + name);
                if (!names.add(name)) throw new IllegalArgumentException("Duplicate profile.packages name: " + name);
            }
        }
        return normalized;
    }

    private static String resolvePackage(com.fasterxml.jackson.databind.JsonNode value, String field) throws Exception {
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be a JSON object");
        ObjectNode application = (ObjectNode) value;
        if (application.has("apk")) {
            Path source = Path.of(ProfileValues.text(application.required("apk"), field + ".apk"));
            if (!source.isAbsolute() || !Files.isRegularFile(source)) throw new IllegalArgumentException(field + ".apk must be an existing absolute APK path");
            try (ApkFile apk = new ApkFile(source.toFile())) {
                var metadata = apk.getApkMeta();
                text(application, field, "name", metadata.getPackageName());
                text(application, field, "versionName", metadata.getVersionName());
                number(application, field, "versionCode", metadata.getVersionCode());
                if (metadata.getTargetSdkVersion() != null) number(application, field, "targetSdk", Long.parseLong(metadata.getTargetSdkVersion()));
            }
            var signatures = ApkSigningMetadata.signatures(source);
            if (application.has("signatures") && !application.required("signatures").equals(signatures)) {
                throw new IllegalArgumentException(field + ".signatures conflicts with APK certificate metadata");
            }
            application.set("signatures", signatures);
        }
        if (!application.hasNonNull("name") || ProfileValues.text(application.required("name"), field + ".name").isBlank()) {
            throw new IllegalArgumentException(field + " requires name or APK package metadata");
        }
        return application.required("name").textValue();
    }

    private static void text(ObjectNode target, String object, String field, String actual) {
        if (target.has(field) && (!target.get(field).isTextual() && !target.get(field).isNull()
                || !Objects.equals(target.get(field).textValue(), actual))) {
            throw new IllegalArgumentException(object + "." + field + " conflicts with APK metadata");
        }
        target.put(field, actual);
    }

    private static void number(ObjectNode target, String object, String field, long actual) {
        if (target.has(field) && (!target.get(field).isIntegralNumber() || !target.get(field).canConvertToLong() || target.get(field).longValue() != actual)) {
            throw new IllegalArgumentException(object + "." + field + " conflicts with APK metadata");
        }
        target.put(field, actual);
    }

    private ApkMetadata() {}
}
