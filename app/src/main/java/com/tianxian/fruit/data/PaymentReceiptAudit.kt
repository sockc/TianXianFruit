package com.tianxian.fruit.data

/**
 * Conservative, read-only checks on receipts already imported into the ledger.
 * Missing evidence is a REVIEW item, never a zero-payment assertion.
 */
data class PaymentReceiptAudit(
    val unmatchedPayments: Int,
    val refundRows: Int,
    val repeatedTransactionRefs: Int,
    val assignedOutsideSavedWindow: Int,
    val sessionsWithoutMatchedReceipts: List<String>
) {
    val needsReview: Boolean get() =
        unmatchedPayments > 0 || repeatedTransactionRefs > 0 ||
            assignedOutsideSavedWindow > 0 || sessionsWithoutMatchedReceipts.isNotEmpty()
}

internal object PaymentReceiptAuditor {
    fun inspect(
        rows: List<CustomerPaymentRecord>,
        sessions: List<CustomerTrafficSession>,
        startDate: String? = null,
        endDate: String? = null,
        storeId: Long = 0L,
        platform: String = "ALL"
    ): PaymentReceiptAudit {
        val scoped = rows.filter { record ->
            (startDate == null || record.businessDate >= startDate) &&
                (endDate == null || record.businessDate <= endDate) &&
                (platform == "ALL" || record.platform == platform) &&
                (storeId == 0L || (storeId == -1L && record.storeId <= 0L) ||
                    (storeId > 0L && record.storeId == storeId))
        }
        val payments = scoped.filter { it.amount > 0.005 && it.netAmount > 0.005 }
        val references = payments
            .filter { it.transactionRef.trim().isNotBlank() }
            .groupBy { it.platform to it.transactionRef.trim() }
        val collisions = references.values.count { group ->
            group.map { it.id }.distinct().size > 1
        }
        val visibleSessions = sessions.filter { session ->
            session.storeId > 0L &&
                (storeId == 0L || storeId == session.storeId) &&
                (startDate == null || session.date >= startDate) &&
                (endDate == null || session.date <= endDate)
        }.distinctBy { it.date to it.storeId }
        val unmatched = payments.count { it.storeId <= 0L }
        val outOfWindow = payments.count { row ->
            if (row.storeId <= 0L) return@count false
            val day = row.tradeTime.take(10)
            val relevant = sessions.filter { session ->
                session.storeId == row.storeId &&
                    (session.date == row.businessDate || session.date == day ||
                        session.date == runCatching {
                            java.time.LocalDate.parse(day).minusDays(1).toString()
                        }.getOrNull())
            }
            relevant.isNotEmpty() &&
                CustomerTrafficDeepDive.matchSession(
                    row.tradeTime, relevant, row.storeId
                ) == null
        }
        val absent = if (storeId == -1L) emptyList() else visibleSessions.filter { session ->
            payments.none { it.storeId == session.storeId && it.businessDate == session.date }
        }.map { "${it.date} · ${it.storeName}" }

        return PaymentReceiptAudit(
            unmatchedPayments = unmatched,
            refundRows = scoped.count { it.refundAmount > 0.005 },
            repeatedTransactionRefs = collisions,
            assignedOutsideSavedWindow = outOfWindow,
            sessionsWithoutMatchedReceipts = absent
        )
    }
}
