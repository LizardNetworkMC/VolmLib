package art.arcane.volmlib.util.storage;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChangeRetriesTest {
    @Test
    public void reportsTheFirstFailureOnceAndRetriesWithBackoff() {
        ChangeRetries retries = new ChangeRetries();

        assertEquals(ChangeRetries.Outcome.FIRST, retries.failed("users", "not-a-uuid", 0L));
        assertTrue(retries.due(ChangeRetries.FIRST_DELAY_MILLIS - 1L).isEmpty());
        assertEquals(List.of(new ChangeRetries.Key("users", "not-a-uuid")), retries.due(ChangeRetries.FIRST_DELAY_MILLIS));
        assertEquals(ChangeRetries.Outcome.RETRYING, retries.failed("users", "not-a-uuid", ChangeRetries.FIRST_DELAY_MILLIS));
        assertTrue(retries.due(ChangeRetries.FIRST_DELAY_MILLIS * 2L).isEmpty());
        assertEquals(1, retries.size());
    }

    @Test
    public void givesUpAfterTheLastAttemptAndForgetsSuccesses() {
        ChangeRetries retries = new ChangeRetries();
        for (int attempt = 1; attempt < ChangeRetries.MAXIMUM_ATTEMPTS; attempt++) {
            retries.failed("groups", "broken", 0L);
        }

        assertEquals(ChangeRetries.Outcome.GAVE_UP, retries.failed("groups", "broken", 0L));
        assertEquals(0, retries.size());
        retries.failed("groups", "vip", 0L);
        retries.succeeded("groups", "vip");
        assertEquals(0, retries.size());
    }

    @Test
    public void staysBoundedAndCountsDroppedEntries() {
        ChangeRetries retries = new ChangeRetries();
        for (int index = 0; index < ChangeRetries.MAXIMUM_ENTRIES + 3; index++) {
            retries.failed("users", "u" + index, 0L);
        }

        assertEquals(ChangeRetries.MAXIMUM_ENTRIES, retries.size());
        assertEquals(3L, retries.dropped());
    }
}
