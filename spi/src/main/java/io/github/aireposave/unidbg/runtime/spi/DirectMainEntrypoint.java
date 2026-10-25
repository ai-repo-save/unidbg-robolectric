package io.github.aireposave.unidbg.runtime.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Executes a CLI in the current worker; the CLI must return rather than call System.exit. */
public final class DirectMainEntrypoint implements WorkerEntrypoint {
    @Override
    public JsonNode run(WorkerSession session, JsonNode arguments) throws Exception {
        JsonNode className = arguments.required("mainClass");
        if (!className.isTextual()) throw new IllegalArgumentException("mainClass must be a JSON string");
        JsonNode argv = arguments.required("arguments");
        if (!argv.isArray()) throw new IllegalArgumentException("arguments must be a JSON string array");
        var values = new ArrayList<String>();
        for (JsonNode value : argv) {
            if (!value.isTextual()) throw new IllegalArgumentException("arguments must contain only JSON strings");
            values.add(value.textValue());
        }
        var main = Class.forName(className.textValue(), true, getClass().getClassLoader()).getMethod("main", String[].class);
        if (!java.lang.reflect.Modifier.isStatic(main.getModifiers()) || main.getReturnType() != void.class) {
            throw new IllegalArgumentException("Entrypoint main must be public static void main(String[])");
        }
        PrintStream diagnostics = System.out;
        PrintStream protocol = new PrintStream(session.output(), true, StandardCharsets.UTF_8);
        try {
            System.setOut(protocol);
            main.invoke(null, (Object) values.toArray(String[]::new));
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } finally {
            protocol.flush();
            System.setOut(diagnostics);
        }
        return NullNode.instance;
    }
}
