package com.tianxian.fruit.sync

/**
 * Whether the existing on-device natural-day receipt actually persisted the
 * cash-deduction flag. A pre-V123 missing field means "historically do not
 * deduct", NOT an explicit edit made on the current device.
 */
internal enum class NaturalCashFlagPresence {
    EXPLICIT, LEGACY_ABSENT, UNKNOWN
}

/**
 * Diagnose only: different cash-deduction choices change business totals.
 * Never treat an absent legacy field as permission to adopt a remote value,
 * force-update a server revision, or silently overwrite another device.
 */
internal object CustomerCashDeductionConflictPolicy {
    fun describe(
        localValue: Boolean?,
        cloudValue: Boolean?,
        localPresence: NaturalCashFlagPresence
    ): String {
        fun label(value: Boolean?): String = when (value) {
            true -> "扣减"
            false -> "不扣减"
            null -> "字段缺失"
        }
        val origin = when (localPresence) {
            NaturalCashFlagPresence.LEGACY_ABSENT ->
                "本机为旧版无现金扣减字段的记录，历史默认不扣减"
            NaturalCashFlagPresence.EXPLICIT ->
                "本机已经明确保存过现金扣减设置"
            NaturalCashFlagPresence.UNKNOWN ->
                "本机现金扣减设置来源尚未确定"
        }
        return "deduct_cash 冲突：本机${label(localValue)}，云端${label(cloudValue)}；" +
            "$origin。请到对应营业日核对该收款人的现金扣减选择；" +
            "未确认前双方金额和扣减设置均不覆盖"
    }
}
