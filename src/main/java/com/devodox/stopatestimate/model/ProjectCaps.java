package com.devodox.stopatestimate.model;

import java.io.Serializable;
import java.math.BigDecimal;

public record ProjectCaps(
		boolean timeCapActive,
		long timeLimitMs,
		String timeResetOption,
		boolean includeNonBillable,
		boolean budgetCapActive,
		BigDecimal budgetLimit,
		String budgetResetOption,
		boolean includeExpenses,
		ResetWindowSchedule resetWindowSchedule) implements Serializable {

	public boolean hasActiveCaps() {
		return (timeCapActive && timeLimitMs > 0) || (budgetCapActive && budgetLimit != null && budgetLimit.signum() > 0);
	}
}
