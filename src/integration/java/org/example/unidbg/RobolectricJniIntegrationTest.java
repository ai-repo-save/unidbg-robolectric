package org.example.unidbg;

import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.Assert.assertEquals;

public class RobolectricJniIntegrationTest {
    private static final Logger LOG = LoggerFactory.getLogger(RobolectricJniIntegrationTest.class);

    @Test
    public void crossJniEffectsAndSessionIsolation() {
        MDC.put("run_id", System.getProperty("integration.runId"));
        LOG.info("event=integration_started");
        try {
            for (String session : new String[] {"first", "second"}) {
                MDC.put("session_id", session);
                try {
                    System.setProperty("integration.session", session);
                    Result result = JUnitCore.runClasses(AndroidSession.class);
                    if (!result.wasSuccessful()) {
                        AssertionError failure = new AssertionError("Robolectric session " + session + " failed");
                        for (Failure resultFailure : result.getFailures()) {
                            failure.addSuppressed(resultFailure.getException());
                        }
                        throw failure;
                    }
                    assertEquals("Exactly one complete scenario must execute", 1, result.getRunCount());
                } catch (RuntimeException | Error failure) {
                    LOG.error("event=session_failed", failure);
                    throw failure;
                } finally {
                    MDC.remove("session_id");
                }
            }
            LOG.info("event=integration_completed sessions=2");
        } finally {
            System.clearProperty("integration.session");
            LOG.info("event=integration_closed");
            MDC.clear();
        }
    }
}
