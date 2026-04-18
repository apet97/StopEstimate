package com.devodox.stopatestimate.model;

import java.io.Serializable;
import java.time.Instant;

public record ResetWindow(Instant startInclusive, Instant endExclusive, Instant nextResetAt) implements Serializable {
}
