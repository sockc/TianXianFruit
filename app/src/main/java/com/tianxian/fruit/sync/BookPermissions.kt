package com.tianxian.fruit.sync

object BookPermissions {
    const val HOME_BUSINESS_VIEW = "home.business.view"

    const val PURCHASE_VIEW = "purchase.view"
    const val PURCHASE_CREATE = "purchase.create"
    const val PURCHASE_EDIT = "purchase.edit"
    const val PURCHASE_DELETE = "purchase.delete"
    const val PURCHASE_ACTIVITY_VIEW = "purchase_activity.view"
    const val PURCHASE_PLAN_EDIT = "purchase_plan.edit"

    const val BUSINESS_VIEW = "business.view"
    const val BUSINESS_EDIT = "business.edit"

    const val HISTORY_VIEW = "history.view"
    const val STATS_VIEW = "stats.view"

    const val PROFIT_VIEW = "profit.view"
    const val PROFIT_EDIT = "profit.edit"

    const val SETTLEMENT_VIEW = "settlement.view"
    const val SETTLEMENT_EDIT = "settlement.edit"

    const val REPORT_VIEW = "report.view"
    const val BASIC_EDIT = "basic.edit"

    val allMemberPermissions: Set<String> =
        setOf(
            HOME_BUSINESS_VIEW,
            PURCHASE_VIEW,
            PURCHASE_CREATE,
            PURCHASE_EDIT,
            PURCHASE_DELETE,
            PURCHASE_ACTIVITY_VIEW,
            PURCHASE_PLAN_EDIT,
            BUSINESS_VIEW,
            BUSINESS_EDIT,
            HISTORY_VIEW,
            STATS_VIEW,
            PROFIT_VIEW,
            PROFIT_EDIT,
            SETTLEMENT_VIEW,
            SETTLEMENT_EDIT,
            REPORT_VIEW,
            BASIC_EDIT
        )

    val readOnlyPermissions: Set<String> =
        setOf(
            HOME_BUSINESS_VIEW,
            PURCHASE_VIEW,
            PURCHASE_ACTIVITY_VIEW,
            BUSINESS_VIEW,
            HISTORY_VIEW,
            STATS_VIEW,
            PROFIT_VIEW,
            SETTLEMENT_VIEW,
            REPORT_VIEW
        )

    val editPermissions: Set<String> =
        setOf(
            PURCHASE_CREATE,
            PURCHASE_EDIT,
            PURCHASE_DELETE,
            PURCHASE_PLAN_EDIT,
            BUSINESS_EDIT,
            PROFIT_EDIT,
            SETTLEMENT_EDIT,
            BASIC_EDIT
        )

    fun templatePermissions(
        template: String
    ): Set<String> =
        when (template.uppercase()) {
            "FULL" ->
                allMemberPermissions

            "PURCHASER" ->
                setOf(
                    PURCHASE_VIEW,
                    PURCHASE_CREATE,
                    PURCHASE_EDIT,
                    PURCHASE_DELETE,
                    PURCHASE_ACTIVITY_VIEW,
                    PURCHASE_PLAN_EDIT
                )

            "OPERATOR" ->
                setOf(
                    HOME_BUSINESS_VIEW,
                    PURCHASE_VIEW,
                    PURCHASE_ACTIVITY_VIEW,
                    BUSINESS_VIEW,
                    BUSINESS_EDIT,
                    HISTORY_VIEW,
                    STATS_VIEW,
                    REPORT_VIEW
                )

            "FINANCE" ->
                setOf(
                    HOME_BUSINESS_VIEW,
                    PURCHASE_VIEW,
                    PURCHASE_ACTIVITY_VIEW,
                    BUSINESS_VIEW,
                    HISTORY_VIEW,
                    STATS_VIEW,
                    PROFIT_VIEW,
                    PROFIT_EDIT,
                    SETTLEMENT_VIEW,
                    SETTLEMENT_EDIT,
                    REPORT_VIEW
                )

            "READONLY" ->
                readOnlyPermissions

            else ->
                emptySet()
        }

    fun templateLabel(
        template: String
    ): String =
        when (template.uppercase()) {
            "OWNER" -> "所有者"
            "FULL" -> "全功能成员"
            "PURCHASER" -> "采购员"
            "OPERATOR" -> "经营员"
            "FINANCE" -> "财务"
            "READONLY" -> "只读成员"
            "CUSTOM" -> "自定义"
            "SYSTEM" -> "系统访问"
            else -> "成员"
        }

    fun fallbackForRole(
        role: String
    ): Set<String> =
        when (role) {
            "OWNER",
            "SUPERADMIN",
            "EDITOR" ->
                allMemberPermissions

            "VIEWER" ->
                readOnlyPermissions

            else ->
                emptySet()
        }

    fun has(
        book: LedgerBook,
        systemRole: String,
        permission: String
    ): Boolean {
        if (systemRole == "SUPERADMIN") return true
        if (
            book.permission == "OWNER" ||
            book.permission == "SUPERADMIN"
        ) {
            return true
        }

        val effective =
            if (book.permissions.isNotEmpty()) {
                book.permissions
            } else {
                fallbackForRole(book.permission)
            }

        return permission in effective
    }

    fun canModifyAnything(
        book: LedgerBook,
        systemRole: String
    ): Boolean =
        systemRole == "SUPERADMIN" ||
            book.permission == "OWNER" ||
            book.permission == "SUPERADMIN" ||
            (
                if (book.permissions.isNotEmpty()) {
                    book.permissions
                } else {
                    fallbackForRole(book.permission)
                }
            ).any { it in editPermissions }
}
