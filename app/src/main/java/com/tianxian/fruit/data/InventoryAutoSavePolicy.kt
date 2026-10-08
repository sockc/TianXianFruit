package com.tianxian.fruit.data

/** Inventory auto-save accepts completed decimal quantities only; invalid drafts remain unsaved. */
internal data class InventoryAutoSaveValues(
    val remaining: Double,
    val loss: Double
)

internal object InventoryAutoSavePolicy {
    private fun parseQuantity(raw: String): Double? {
        val text = raw.trim()
        if (text.isEmpty()) return 0.0
        // "1." and "." are intermediate editing states, never commit them as zero/one.
        if (text.endsWith(".") || !text.matches(Regex("^\\d+(\\.\\d{1,2})?$"))) return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun validate(remaining: String, loss: String, available: Double): InventoryAutoSaveValues? {
        if (!available.isFinite() || available < 0.0) return null
        val rem = parseQuantity(remaining) ?: return null
        val damaged = parseQuantity(loss) ?: return null
        if (rem + damaged > available + 0.000001) return null
        return InventoryAutoSaveValues(rem, damaged)
    }
}
