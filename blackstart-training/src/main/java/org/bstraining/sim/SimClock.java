package org.bstraining.sim;

import java.time.Duration;
import java.time.Instant;

/** 仿真钟：与墙钟分离，保证测试可确定性推进。 */
public final class SimClock {
    private Instant now;

    public SimClock(Instant start) {
        this.now = start;
    }

    public Instant now() {
        return now;
    }

    public Instant advance(Duration d) {
        this.now = now.plus(d);
        return now;
    }

    public void reset(Instant start) {
        this.now = start;
    }
}
