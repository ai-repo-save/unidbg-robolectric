package io.github.aireposave.unidbg.runtime.client;

public final class RuntimeVersion {
    public static final String VERSION = "1.2.0";
    public static final int PROTOCOL = 1;
    private RuntimeVersion() {}
    public static String version() { return VERSION; }
}
