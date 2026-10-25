package io.github.aireposave.unidbg.runtime.worker;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.linux.android.SystemPropertyHook;
import com.github.unidbg.linux.android.SystemPropertyProvider;
import com.github.unidbg.virtualmodule.android.SystemProperties;
import com.sun.jna.Pointer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class NativeProperties {
    static void install(AndroidEmulator emulator, Map<String, String> properties) {
        SystemPropertyHook hook = new SystemPropertyHook(emulator);
        var pointers = new LinkedHashMap<String, Pointer>();
        properties.forEach((key, value) -> {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length >= SystemPropertyHook.PROP_VALUE_MAX || value.indexOf('\0') >= 0 || key.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid native property: " + key);
            }
            byte[] name = key.getBytes(StandardCharsets.UTF_8);
            // unidbg's SystemProperties module consumes this prop_info layout.
            var pointer = emulator.getSvcMemory().allocate(4 + SystemPropertyHook.PROP_VALUE_MAX + name.length + 1, "profile:" + key);
            pointer.setInt(0, bytes.length << 24);
            pointer.setString(4, value);
            pointer.setString(4 + SystemPropertyHook.PROP_VALUE_MAX, key);
            pointers.put(key, pointer);
        });
        hook.setPropertyProvider(new SystemPropertyProvider() {
            @Override public String getProperty(String key) { return properties.get(key); }
            @Override public Pointer __system_property_find(String key) { return pointers.get(key); }
        });
        emulator.getMemory().addHookListener(hook);
        new SystemProperties(emulator, null).register(emulator.getMemory());
    }
    private NativeProperties() {}
}
