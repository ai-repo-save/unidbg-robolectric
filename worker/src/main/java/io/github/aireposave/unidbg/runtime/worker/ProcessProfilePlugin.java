package io.github.aireposave.unidbg.runtime.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.robolectric.ApplicationPackageSource;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin;
import org.robolectric.shadows.ShadowProcess;

public final class ProcessProfilePlugin implements TestEnvironmentLifecyclePlugin {
    @Override
    public void onSetupApplicationState() {
        try {
            var profile = RequestData.read().required("profile");
            var pkg = profile.required("package");
            RuntimeEnvironment.setApplicationPackageSource(pkg.has("apk")
                    ? new ApplicationPackageSource(Path.of(ProfileValues.text(pkg.required("apk"), "package.apk")),
                            pkg.has("guestSourceDir") ? ProfileValues.text(pkg.required("guestSourceDir"), "package.guestSourceDir") : null)
                    : null);
            var credentials = ProcessCredentialsProjection.resolve(profile);
            if (credentials.realUid.isPresent()) ShadowProcess.setUid(credentials.realUid.getAsInt());
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read process profile before Android initialization", failure);
        }
    }
}
