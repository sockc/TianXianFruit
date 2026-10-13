package com.tianxian.fruit.sync

/**
 * Auth recovery never treats a 403 permission denial or a network failure as
 * an expired session. Only a 401 for an authorized API request is eligible
 * for one refresh attempt.
 */
internal object CloudAuthRecoveryPolicy {
    fun mayRefresh(statusCode: Int, path: String, hasAccessToken: Boolean): Boolean =
        statusCode == 401 &&
            hasAccessToken &&
            path != "/api/v1/auth/login" &&
            path != "/api/v1/auth/refresh"

    fun sameAccount(expected: String, verified: String): Boolean =
        expected.isNotBlank() &&
            verified.isNotBlank() &&
            expected.trim().equals(verified.trim(), ignoreCase = true)
}
