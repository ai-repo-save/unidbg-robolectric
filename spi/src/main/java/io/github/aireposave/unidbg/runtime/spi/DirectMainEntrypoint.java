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
        String mainClass = arguments.required("mainClass").asText();
        var values = new ArrayList<String>();
        for (JsonNode value : arguments.required("arguments")) values.add(value.asText());
        var main = Class.forName(mainClass, true, getClass().getClassLoader()).getMethod("main", String[].class);
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
