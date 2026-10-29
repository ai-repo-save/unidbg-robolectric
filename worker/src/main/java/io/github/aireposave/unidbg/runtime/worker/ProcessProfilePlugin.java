package io.github.aireposave.unidbg.runtime.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin;
import org.robolectric.shadows.ShadowProcess;

public final class ProcessProfilePlugin implements TestEnvironmentLifecyclePlugin {
    @Override
    public void onSetupApplicationState() {
        try {
            var credentials = ProcessCredentialsProjection.resolve(RequestData.read().required("profile"));
            if (credentials.realUid.isPresent()) ShadowProcess.setUid(credentials.realUid.getAsInt());
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read process profile before Android initialization", failure);
        }
    }
}
