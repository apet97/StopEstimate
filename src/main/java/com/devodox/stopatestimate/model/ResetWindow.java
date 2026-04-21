package com.devodox.stopatestimate.model;

import java.time.Instant;

public record ResetWindow(Instant startInclusive, Instant endExclusive, Instant nextResetAt) {
}
