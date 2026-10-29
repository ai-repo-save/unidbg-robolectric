package io.github.aireposave.unidbg.runtime.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.unidbg.linux.GuestCredentials;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

final class ProcessCredentialsProjection {
    static GuestCredentials resolve(JsonNode profile) {
        JsonNode values = profile.path("processCredentials");
        if (!values.isMissingNode() && !values.isObject()) {
            throw new IllegalArgumentException("processCredentials must be a JSON object");
        }
        OptionalInt uid = identity(values, "uid", "processCredentials");
        OptionalInt packageUid = identity(profile.path("package"), "uid", "package");
        if (uid.isPresent() && packageUid.isPresent() && uid.getAsInt() != packageUid.getAsInt()) {
            throw new IllegalArgumentException("processCredentials.uid conflicts with package.uid");
        }
        Optional<Set<Integer>> groups = Optional.empty();
        if (values.has("groups")) {
            JsonNode supplied = values.required("groups");
            if (!supplied.isArray()) throw new IllegalArgumentException("processCredentials.groups must be a JSON array");
            var selected = new LinkedHashSet<Integer>();
            for (int index = 0; index < supplied.size(); index++) {
                selected.add(identityValue(supplied.get(index), "processCredentials.groups[" + index + "]"));
            }
            groups = Optional.of(selected);
        }
        return new GuestCredentials(uid.isPresent() ? uid : packageUid,
                identity(values, "euid", "processCredentials"), identity(values, "gid", "processCredentials"),
                identity(values, "egid", "processCredentials"), groups);
    }

    private static OptionalInt identity(JsonNode values, String key, String field) {
        return values.has(key) ? OptionalInt.of(identityValue(values.required(key), field + "." + key)) : OptionalInt.empty();
    }

    private static int identityValue(JsonNode value, String field) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalArgumentException(field + " must be a nonnegative 32-bit integer");
        }
        return value.intValue();
    }

    private ProcessCredentialsProjection() {}
}
