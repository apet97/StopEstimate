package com.devodox.stopatestimate.model;

import java.io.Serializable;
import java.math.BigDecimal;

public record RateInfo(BigDecimal amount, String currency, boolean configured) implements Serializable {

	public static RateInfo empty() {
		return new RateInfo(BigDecimal.ZERO, null, false);
	}

	public static RateInfo of(BigDecimal amount, String currency) {
		return new RateInfo(amount, currency, amount != null);
	}

	public boolean present() {
		return configured && amount != null && amount.compareTo(BigDecimal.ZERO) >= 0;
	}
}
