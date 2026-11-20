package com.tianxian.fruit.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerManagementAccessPolicyTest {
    private fun cloud(role: String, id: String = role,
                      deleted: Boolean = false, purged: Boolean = false) =
        CloudBookInfo(id, "测试账本", role, "owner", "模拟所有者",
            deleted = deleted, purged = purged)

    private fun local(role: String, id: String = role) =
        LedgerBook(id, "测试账本", "test-$id.db", "test-device",
            permission = role, createdAt = 1L, updatedAt = 1L)

    @Test fun ordinaryMembersSeeOnlyTheBooksTheyCanAccess() {
        val result = LedgerManagementAccessPolicy.visibleCloudBooks(listOf(
            cloud("OWNER"), cloud("EDITOR"), cloud("VIEWER"),
            cloud("REVOKED"), cloud("OWNER", "deleted", deleted = true),
            cloud("OWNER", "purged", purged = true)
        ))
        assertEquals(listOf("OWNER", "EDITOR", "VIEWER"), result.map { it.id })
        assertEquals(listOf("OWNER", "EDITOR", "VIEWER"),
            LedgerManagementAccessPolicy.visibleLocalBooks(listOf(
                local("OWNER"), local("EDITOR"), local("VIEWER"),
                local("REVOKED")
            ), "USER").map { it.id })
    }

    @Test fun ordinaryMemberCanSwitchButCannotPerformOwnerOperations() {
        assertFalse(LedgerManagementAccessPolicy.canManageCloudBook(
            cloud("VIEWER"), "USER"))
        assertFalse(LedgerManagementAccessPolicy.canManageCloudBook(
            cloud("EDITOR"), "USER"))
        assertFalse(LedgerManagementAccessPolicy.canOpenCloudTrash(
            listOf(cloud("VIEWER"), cloud("EDITOR")), "USER"))
    }

    @Test fun ownerAndSystemAdminRetainAuthorizedCloudActions() {
        assertTrue(LedgerManagementAccessPolicy.canManageCloudBook(
            cloud("OWNER"), "USER"))
        assertTrue(LedgerManagementAccessPolicy.canManageCloudBook(
            cloud("VIEWER"), "SUPERADMIN"))
        assertTrue(LedgerManagementAccessPolicy.canOpenCloudTrash(
            listOf(cloud("OWNER")), "USER"))
        assertTrue(LedgerManagementAccessPolicy.canOpenCloudTrash(
            emptyList(), "SUPERADMIN"))
        assertEquals(2, LedgerManagementAccessPolicy.visibleLocalBooks(
            listOf(local("OWNER"), local("REVOKED")), "SUPERADMIN").size)
    }
}
