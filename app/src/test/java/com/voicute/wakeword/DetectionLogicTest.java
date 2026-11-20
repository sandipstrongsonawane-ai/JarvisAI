package com.voicute.wakeword;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DetectionLogicTest {

    @Test
    public void subThresholdBackgroundCannotMakeL2GateImpossible() {
        DetectionLogic logic = new DetectionLogic();
        long now = 10_000L;

        // The classifier emits no label for scores <= 0.5. These values must
        // still be capped in the adaptive background estimate.
        for (int i = 0; i < 128; i++) {
            now += 30L;
            logic.record(0.49f, null, 250f, now);
        }

        String detected = null;
        for (int i = 0; i < 3; i++) {
            now += 30L;
            logic.record(0.56f, "Hey Jarvis", 800f, now);
            detected = logic.evaluate("Hey Jarvis", 0.56f, 800f, 0.55f, 3, 0, now);
        }

        assertEquals("Hey Jarvis", detected);
    }
}
