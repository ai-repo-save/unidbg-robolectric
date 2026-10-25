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
                System.setProperty("integration.session", session);
                Result result = JUnitCore.runClasses(AndroidSession.class);
                for (Failure failure : result.getFailures()) {
                    LOG.error("event=session_failed session={}", session, failure.getException());
                }
                if (!result.wasSuccessful()) {
                    throw new AssertionError("Robolectric session " + session + " failed",
                            result.getFailures().getFirst().getException());
                }
                assertEquals("Exactly one complete scenario must execute", 1, result.getRunCount());
            }
            LOG.info("event=integration_completed sessions=2");
        } finally {
            System.clearProperty("integration.session");
            LOG.info("event=integration_closed");
            MDC.clear();
        }
    }
}
