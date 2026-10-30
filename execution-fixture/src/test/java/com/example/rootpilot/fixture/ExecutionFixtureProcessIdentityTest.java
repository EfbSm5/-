package com.example.rootpilot.fixture;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ExecutionFixtureProcessIdentityTest {
    private String stat(String ticks) {
        return "15234 (fixture name ) nested) S " + "0 ".repeat(18) + ticks + " 0 0\n";
    }
    private void invalid(String value) {
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionFixtureActivity.parseProcessStartTicks(value, 15234));
    }
    @Test public void field22SupportsSpacesParenthesesAndInt64() {
        assertEquals(4294967296L, ExecutionFixtureActivity.parseProcessStartTicks(stat("4294967296"), 15234));
        assertEquals(Long.MAX_VALUE, ExecutionFixtureActivity.parseProcessStartTicks(stat("9223372036854775807"), 15234));
    }
    @Test public void rejectsNonpositiveCoercedAndOverflowTicks() {
        for (String ticks : new String[] {"0", "-1", "+1", "01", "1.0", "true", "9223372036854775808"}) invalid(stat(ticks));
    }
    @Test public void rejectsWrongPidMissingFieldsAndOversizeInput() {
        invalid(stat("1").replace("15234 (", "15235 ("));
        invalid("15234 (fixture) S 1");
        invalid("15234 fixture S 1");
        invalid(stat("1") + "x".repeat(4096));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionFixtureActivity.parseProcessStartTicks(stat("1"), 0));
    }
}
