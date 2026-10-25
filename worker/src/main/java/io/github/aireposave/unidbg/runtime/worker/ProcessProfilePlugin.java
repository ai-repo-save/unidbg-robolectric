package io.github.aireposave.unidbg.runtime.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin;
import org.robolectric.shadows.ShadowProcess;

public final class ProcessProfilePlugin implements TestEnvironmentLifecyclePlugin {
    @Override
    public void onSetupApplicationState() {
        try {
            var identity = RequestData.read().required("profile").required("package");
            if (identity.has("uid")) {
                var uid = identity.required("uid");
                if (!uid.isInt() || uid.intValue() < 0) throw new IllegalArgumentException("package.uid must be a nonnegative integer");
                ShadowProcess.setUid(uid.intValue());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read process profile before Android initialization", failure);
        }
    }
}
