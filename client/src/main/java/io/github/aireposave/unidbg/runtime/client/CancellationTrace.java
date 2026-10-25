package io.github.aireposave.unidbg.runtime.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

final class CancellationTrace {
    private static final Logger LOG = LoggerFactory.getLogger(CancellationTrace.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RunRequest request;
    private final String sessionId;
    private final long rootPid;
    private final Path output;
    private final ObjectNode report = JSON.createObjectNode();
    private ObjectNode phase;
    private boolean signalled;

    CancellationTrace(RunRequest request, String sessionId, long rootPid, Path output) {
        this.request = request;
        this.sessionId = sessionId;
        this.rootPid = rootPid;
        this.output = output;
        report.put("traceId", request.traceId()).put("sessionId", sessionId).put("pid", rootPid)
                .put("startedAt", Instant.now().toString()).put("finallyConfirmed", false);
        report.putObject("budgets").put("graceMillis", request.cancellationGrace().toMillis())
                .put("terminationWaitMillis", request.terminationWait().toMillis());
        report.putArray("phases");
    }

    void captured(List<ProcessHandle> handles) {
        var processes = report.withArray("processes");
        for (ProcessHandle handle : handles) {
            processes.addObject().put("pid", handle.pid()).put("parentPid", handle.parent().map(ProcessHandle::pid).orElse(-1L));
        }
        event("cancellation_tree_captured").addKeyValue("process.pids", handles.stream().map(ProcessHandle::pid).toList()).log();
    }

    void phase(String name, long budgetMillis, List<ProcessHandle> targets) {
        phase = report.withArray("phases").addObject().put("phase", name).put("budgetMillis", budgetMillis)
                .put("startedAt", Instant.now().toString());
        phase.set("pids", JSON.valueToTree(targets.stream().map(ProcessHandle::pid).toList()));
        phase.putArray("signals");
        phase.putArray("waits");
        event("cancellation_phase_started").addKeyValue("phase", name).addKeyValue("budget_ms", budgetMillis)
                .addKeyValue("process.pids", targets.stream().map(ProcessHandle::pid).toList()).log();
    }

    void signal(ProcessHandle target, boolean accepted) {
        signalled = true;
        phase.withArray("signals").addObject().put("pid", target.pid()).put("accepted", accepted);
        event("process_signal_sent").addKeyValue("phase", phase.path("phase").textValue())
                .addKeyValue("process.pid", target.pid()).addKeyValue("accepted", accepted).log();
    }

    void waiting(ProcessHandle target, long remainingNanos) {
        event("process_exit_wait_started").addKeyValue("phase", phase.path("phase").textValue())
                .addKeyValue("process.pid", target.pid()).addKeyValue("remaining_ms", Math.max(0, remainingNanos) / 1_000_000).log();
    }

    void waited(ProcessHandle target, boolean exited) {
        phase.withArray("waits").addObject().put("pid", target.pid()).put("exited", exited);
        LoggingEventBuilder event = exited ? event("process_exit_observed") : fields(LOG.atWarn(), "process_exit_wait_expired");
        event.addKeyValue("phase", phase.path("phase").textValue()).addKeyValue("process.pid", target.pid()).log();
    }

    void graceCompleted(boolean rootExited) {
        report.put("rootExitedDuringGrace", rootExited);
        phase.put("rootExited", rootExited);
        event("cancellation_grace_completed").addKeyValue("root_exited", rootExited).log();
    }

    void finish(List<ProcessHandle> handles, Throwable failure) throws IOException {
        report.put("finishedAt", Instant.now().toString()).put("status", failure == null ? "CANCELLED" : "FAILED");
        var remaining = report.putArray("remainingPids");
        handles.stream().filter(ProcessHandle::isAlive).forEach(handle -> remaining.add(handle.pid()));
        report.put("cleanupConfirmed", remaining.isEmpty());
        if (failure != null) {
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            report.putObject("failure").put("type", failure.getClass().getName()).put("message", failure.getMessage()).put("stack", stack.toString());
            fields(LOG.atError(), "cancellation_failed").setCause(failure).log();
        } else event("cancellation_completed").addKeyValue("process.pids", handles.stream().map(ProcessHandle::pid).toList()).log();
        String json = JSON.writeValueAsString(report);
        Files.writeString(output.resolve("cancellation.json"), json);
        if (signalled) Files.writeString(output.resolve("aborted.json"), json);
    }

    private LoggingEventBuilder event(String name) { return fields(LOG.atInfo(), name); }
    private LoggingEventBuilder fields(LoggingEventBuilder event, String name) {
        return event.setMessage(name).addKeyValue("event.name", name).addKeyValue("trace_id", request.traceId())
                .addKeyValue("session_id", sessionId).addKeyValue("process.root_pid", rootPid);
    }
}
