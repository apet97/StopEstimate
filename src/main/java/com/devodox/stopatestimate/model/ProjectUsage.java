package com.devodox.stopatestimate.model;

import java.math.BigDecimal;

public record ProjectUsage(
		ResetWindow resetWindow,
		long trackedTimeMs,
		BigDecimal laborCost,
		BigDecimal expenseTotal) {

	public BigDecimal budgetUsage(boolean includeExpenses) {
		BigDecimal total = laborCost == null ? BigDecimal.ZERO : laborCost;
		if (includeExpenses && expenseTotal != null) {
			total = total.add(expenseTotal);
		}
		return total;
	}
}
