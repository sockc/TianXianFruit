package com.tianxian.fruit.sync

import org.junit.Assert.*
import org.junit.Test

class CloudAuthRecoveryPolicyTest {
    @Test fun expiredAccessTokenMayRefreshOnlyOnceForAuthorizedPaths() {
        assertTrue(CloudAuthRecoveryPolicy.mayRefresh(401, "/api/v1/auth/me", true))
        assertTrue(CloudAuthRecoveryPolicy.mayRefresh(401, "/api/v1/sync/push", true))
        assertTrue(CloudAuthRecoveryPolicy.mayRefresh(401, "/api/v1/sync/pull", true))
    }

    @Test fun permissionDenialAndServerFailureMustNotTriggerRefresh() {
        assertFalse(CloudAuthRecoveryPolicy.mayRefresh(403, "/api/v1/sync/push", true))
        assertFalse(CloudAuthRecoveryPolicy.mayRefresh(500, "/api/v1/sync/push", true))
        assertFalse(CloudAuthRecoveryPolicy.mayRefresh(0, "/api/v1/sync/push", true))
    }

    @Test fun tokenlessLoginAndRefreshEndpointsMustNotRecursivelyRefresh() {
        assertFalse(CloudAuthRecoveryPolicy.mayRefresh(401, "/api/v1/auth/login", false))
        assertFalse(CloudAuthRecoveryPolicy.mayRefresh(401, "/api/v1/auth/login", true))
        assertFalse(CloudAuthRecoveryPolicy.mayRefresh(401, "/api/v1/auth/refresh", true))
        assertFalse(CloudAuthRecoveryPolicy.mayRefresh(401, "/api/v1/sync/push", false))
    }

    @Test fun reauthenticationMustKeepTheSameAccount() {
        assertTrue(CloudAuthRecoveryPolicy.sameAccount("fruit_owner", " FRUIT_OWNER "))
        assertFalse(CloudAuthRecoveryPolicy.sameAccount("fruit_owner", "other"))
        assertFalse(CloudAuthRecoveryPolicy.sameAccount("", "fruit_owner"))
        assertFalse(CloudAuthRecoveryPolicy.sameAccount("fruit_owner", " "))
    }
}
