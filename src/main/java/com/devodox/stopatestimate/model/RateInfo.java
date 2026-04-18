package com.devodox.stopatestimate.model;

import java.io.Serializable;
import java.math.BigDecimal;

public record RateInfo(BigDecimal amount, String currency) implements Serializable {

	public static RateInfo empty() {
		return new RateInfo(BigDecimal.ZERO, null);
	}

	public boolean present() {
		return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
	}
}
