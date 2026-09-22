package io.github.bandoracer.rommio.data.auth

import java.io.IOException

/** Authentication ownership only. Legacy files are not assigned to this account implicitly. */
data class VerifiedAccount(val baseUrl: String, val userId: Int)

class AccountBindingException : IOException(
    "This server profile belongs to a different RomM account. Sign in with its original account; existing progress has not been reassigned.",
)
