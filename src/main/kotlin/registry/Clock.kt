package registry

import java.time.Instant

/**
 * Server-side clock. Time is always UTC. Two modes exist:
 *  - REAL: returns the current wall-clock instant;
 *  - SIM: frozen at a simulated instant so reviewers can rehearse expiry.
 */
class ServiceClock(
    private var simulating: Boolean = false,
    private var simulated: Instant = Instant.now()
) {
    @Synchronized fun now(): Instant =
        if (simulating) simulated else Instant.now()

    @Synchronized fun isSimulating(): Boolean = simulating

    @Synchronized fun simulatedAt(): Instant = simulated

    @Synchronized fun freezeAt(instant: Instant) {
        simulating = true
        simulated = instant
    }

    @Synchronized fun advance(seconds: Long): Instant {
        simulating = true
        simulated = simulated.plusSeconds(seconds)
        return simulated
    }

    @Synchronized fun reset(): Instant {
        simulating = false
        simulated = Instant.now()
        return simulated
    }

    @Synchronized fun snapshot(): Triple<Boolean, Instant, Instant> =
        Triple(simulating, simulated, Instant.now())
}
