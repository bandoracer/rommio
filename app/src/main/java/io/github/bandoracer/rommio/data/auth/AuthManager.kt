package io.github.bandoracer.rommio.data.auth

import io.github.bandoracer.rommio.domain.storage.StorageAccess

import io.github.bandoracer.rommio.data.network.CredentialScope
import io.github.bandoracer.rommio.data.network.CredentialScopeInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.data.local.ServerProfileDao
import io.github.bandoracer.rommio.data.local.ServerProfileEntity
import io.github.bandoracer.rommio.model.AuthStatus
import io.github.bandoracer.rommio.model.CloudflareServiceCredentials
import io.github.bandoracer.rommio.model.DeviceRegistrationRequest
import io.github.bandoracer.rommio.model.DeviceRegistrationResponse
import io.github.bandoracer.rommio.model.DirectLoginCredentials
import io.github.bandoracer.rommio.model.EdgeAuthMode
import io.github.bandoracer.rommio.model.HeartbeatDto
import io.github.bandoracer.rommio.model.InteractiveSessionConfig
import io.github.bandoracer.rommio.model.InteractiveSessionProvider
import io.github.bandoracer.rommio.model.OriginAuthMode
import io.github.bandoracer.rommio.model.RequestDecoration
import io.github.bandoracer.rommio.model.ServerAccessResponseKind
import io.github.bandoracer.rommio.model.ServerAccessResult
import io.github.bandoracer.rommio.model.ServerAccessState
import io.github.bandoracer.rommio.model.ServerAccessStatus
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.model.SessionState
import io.github.bandoracer.rommio.model.TokenBundle
import io.github.bandoracer.rommio.model.UserDto
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private data class JsonProbeResult<T>(
    val ok: Boolean,
    val status: Int,
    val isHtml: Boolean,
    val data: T? = null,
    val bodyPreview: String = "",
)

private data class TokenResponsePayload(
    val access_token: String? = null,
    val refresh_token: String? = null,
    val token_type: String? = null,
    val expires_in: Long? = null,
    val expires: Long? = null,
)

class AuthManager(
    private val serverProfileDao: ServerProfileDao,
    private val secretStore: AuthSecretStore,
    private val discovery: AuthDiscovery,
    private val jsonCodec: AuthJsonCodec,
) {
    val profileCookies = ProfileCookies(secretStore::cookieStoreName, secretStore::storeCookieStoreName)

    fun cookieInterceptor() = io.github.bandoracer.rommio.data.network.CookiePersistenceInterceptor(profileCookies)
    private val tokenRefresh = TokenRefreshCoordinator(
        read = secretStore::getTokenBundle,
        write = secretStore::storeTokenBundle,
        clear = secretStore::clearTokenBundle,
        refresh = ::performTokenRefresh,
    )
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val heartbeatAdapter = moshi.adapter(HeartbeatDto::class.java)
    private val userAdapter = moshi.adapter(UserDto::class.java)
    private val tokenAdapter = moshi.adapter(TokenResponsePayload::class.java)
    private val deviceRegistrationResponseAdapter = moshi.adapter(DeviceRegistrationResponse::class.java)
    private val bareClient = OkHttpClient.Builder()
        .addNetworkInterceptor(CredentialScopeInterceptor())
        .addNetworkInterceptor(cookieInterceptor())
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun activeProfileFlow(): Flow<ServerProfile?> {
        return serverProfileDao.observeActive().map { entity -> entity?.toModel() }
    }

    suspend fun getActiveProfile(): ServerProfile? = StorageAccess.normal { serverProfileDao.getActive()?.toModel() }

    suspend fun getProfile(profileId: String): ServerProfile? = StorageAccess.normal { serverProfileDao.getById(profileId)?.toModel() }

    suspend fun listProfiles(): List<ServerProfile> = StorageAccess.normal { serverProfileDao.listAll().map { it.toModel() } }

    suspend fun setActiveProfile(profileId: String): ServerProfile = StorageAccess.normal {
        requireNotNull(serverProfileDao.getById(profileId)) { "Unknown server profile." }
        serverProfileDao.setActiveOnly(profileId, nowIso())
        return@normal requireProfile(profileId)
    }

    fun hasCloudflareServiceCredentials(profileId: String): Boolean {
        return secretStore.getCloudflareCredentials(profileId) != null
    }

    suspend fun initializeForAppLaunch() = StorageAccess.normal {
        val active = getActiveProfile() ?: return@normal
        if (active.serverAccess.status != ServerAccessStatus.READY) {
            return@normal
        }
        if (active.status == AuthStatus.CONNECTED || active.sessionState.hasOriginSession) {
            runCatching { validateProfile(active.id) }
        }
    }

    suspend fun discoverServer(baseUrl: String) = StorageAccess.normal { discovery.discover(baseUrl) }

    suspend fun configureServerProfile(
        baseUrl: String,
        label: String? = null,
        edgeAuthMode: EdgeAuthMode? = null,
        originAuthMode: OriginAuthMode? = null,
        discoveryResult: io.github.bandoracer.rommio.model.AuthDiscoveryResult? = null,
        makeActive: Boolean = true,
    ): ServerProfile = StorageAccess.normal {
        val resolvedDiscovery = discoveryResult ?: discoverServer(baseUrl)
        val normalized = resolvedDiscovery.baseUrl
        val existing = serverProfileDao.listAll().map { it.toModel() }.firstOrNull { it.baseUrl == normalized }
        val nextEdgeMode = edgeAuthMode ?: existing?.edgeAuthMode ?: resolvedDiscovery.recommendedEdgeAuthMode
        val nextOriginMode = originAuthMode ?: existing?.originAuthMode ?: resolvedDiscovery.recommendedOriginAuthMode
        val shouldResetServerAccess = existing?.edgeAuthMode != nextEdgeMode
        val now = nowIso()

        val profile = ServerProfile(
            id = existing?.id ?: "server_${UUID.randomUUID()}",
            label = label ?: existing?.label ?: labelFromUrl(normalized),
            baseUrl = normalized,
            edgeAuthMode = nextEdgeMode,
            originAuthMode = nextOriginMode,
            capabilities = resolvedDiscovery.capabilities,
            serverAccess = if (shouldResetServerAccess) ServerAccessState() else existing?.serverAccess ?: ServerAccessState(),
            sessionState = existing?.sessionState ?: SessionState(),
            isActive = makeActive,
            status = existing?.status ?: AuthStatus.INVALID_CONFIGURATION,
            lastValidationAt = existing?.lastValidationAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        persistProfile(profile)
        if (makeActive) {
            serverProfileDao.setActiveOnly(profile.id, now)
        }
        return@normal requireNotNull(getProfile(profile.id))
    }

    suspend fun setCloudflareServiceCredentials(profileId: String, credentials: CloudflareServiceCredentials) = StorageAccess.normal {
        secretStore.storeCloudflareCredentials(profileId, credentials)
        val profile = requireProfile(profileId)
        persistProfile(
            profile.copy(
                edgeAuthMode = EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
                serverAccess = ServerAccessState(status = ServerAccessStatus.CHECKING),
                status = AuthStatus.INVALID_CONFIGURATION,
                updatedAt = nowIso(),
            ),
        )
    }

    suspend fun beginEdgeAccess(profileId: String) = StorageAccess.normal {
        val profile = requireProfile(profileId)
        persistProfile(
            profile.copy(
                serverAccess = profile.serverAccess.copy(
                    status = ServerAccessStatus.CHECKING,
                    lastError = null,
                    lastHttpStatus = null,
                    lastResponseKind = null,
                ),
                updatedAt = nowIso(),
            ),
        )
    }

    suspend fun completeEdgeAccessAttempt(profileId: String): ServerAccessState = StorageAccess.normal {
        val profile = requireProfile(profileId)
        val cookieNames = getCookieNames(profile)
        val hasRequiredCookie = hasRequiredEdgeCookie(profile)
        val sessionState = profile.sessionState.copy(
            hasEdgeSession = hasRequiredCookie || profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
            lastValidatedAt = nowIso(),
            canRefreshInBackground = profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
        )
        val nextAccess = profile.serverAccess.copy(
            status = ServerAccessStatus.CHECKING,
            lastError = if (hasRequiredCookie) {
                "Protected login finished. Run the native access test to verify the app can reach RomM."
            } else {
                "Protected login finished, but the app did not see a reusable authorization cookie yet. Run the native access test to confirm."
            },
            lastHttpStatus = null,
            lastResponseKind = null,
            lastTestedAt = nowIso(),
            cookieNamesSeen = cookieNames,
        )
        persistProfile(
            profile.copy(
                sessionState = sessionState,
                serverAccess = nextAccess,
                updatedAt = nowIso(),
            ),
        )
        return@normal nextAccess
    }

    suspend fun getInteractiveSessionConfig(profileId: String, provider: InteractiveSessionProvider): InteractiveSessionConfig = StorageAccess.normal {
        val profile = requireProfile(profileId)
        val cookieContext = profileCookies.requireBrowserContext(profile.id, profile.baseUrl)
        return@normal when (provider) {
            InteractiveSessionProvider.EDGE -> InteractiveSessionConfig(
                profileId = profile.id,
                title = when (profile.edgeAuthMode) {
                    EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION -> "Cloudflare Access"
                    else -> "Protected server login"
                },
                startUrl = profile.baseUrl,
                provider = provider,
                expectedBaseUrl = profile.baseUrl,
                cookieContext = cookieContext,
            )

            InteractiveSessionProvider.ORIGIN -> InteractiveSessionConfig(
                profileId = profile.id,
                title = "RomM SSO",
                startUrl = "${profile.baseUrl}/api/login/openid",
                provider = provider,
                expectedBaseUrl = profile.baseUrl,
                cookieContext = cookieContext,
            )
        }
    }

    suspend fun completeInteractiveLogin(profileId: String, provider: InteractiveSessionProvider): AuthStatus = StorageAccess.normal {
        if (provider == InteractiveSessionProvider.EDGE) {
            completeEdgeAccessAttempt(profileId)
            return@normal AuthStatus.REAUTH_REQUIRED_ORIGIN
        }

        val profile = requireProfile(profileId)
        val sessionState = profile.sessionState.copy(
            hasOriginSession = !getCookieHeader(profile, profile.baseUrl).isNullOrBlank(),
            lastValidatedAt = nowIso(),
            canRefreshInBackground = profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
        )
        persistProfile(profile.copy(sessionState = sessionState, updatedAt = nowIso()))
        return@normal validateProfile(profile.id)
    }

    suspend fun testServerAccess(profileId: String? = null): ServerAccessResult = StorageAccess.normal {
        return@normal withContext(Dispatchers.IO) {
            val profile = profileId?.let { requireProfile(it) } ?: getActiveProfile()
                ?: return@withContext ServerAccessResult(
                    status = ServerAccessStatus.FAILED,
                    responseKind = ServerAccessResponseKind.NETWORK_ERROR,
                    cookieNamesSeen = emptyList(),
                    message = "Configure a server URL before testing access.",
                )

            val testedAt = nowIso()
            val request = Request.Builder()
                .url("${profile.baseUrl}/api/heartbeat")
                .get()
                .applyDecoration(decorateRequest(profile.id, "${profile.baseUrl}/api/heartbeat", includeOriginAuth = false))
                .build()

            runCatching {
                bareClient.newCall(request).execute().use { response ->

                    val probe = probeJsonResponse(response, heartbeatAdapter)
                    val cookieNames = getCookieNames(profile)
                    if (probe.ok && probe.data?.system != null) {
                        val nextProfile = profile.copy(
                            serverAccess = profile.serverAccess.copy(
                                status = ServerAccessStatus.READY,
                                verifiedAt = testedAt,
                                lastTestedAt = testedAt,
                                lastError = null,
                                lastHttpStatus = response.code,
                                lastResponseKind = ServerAccessResponseKind.JSON,
                                cookieNamesSeen = cookieNames,
                            ),
                            sessionState = profile.sessionState.copy(
                                hasEdgeSession = when (profile.edgeAuthMode) {
                                    EdgeAuthMode.NONE -> false
                                    EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE -> true
                                    else -> profile.sessionState.hasEdgeSession
                                },
                                lastValidatedAt = testedAt,
                                canRefreshInBackground = profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
                            ),
                            updatedAt = nowIso(),
                        )
                        persistProfile(nextProfile)
                        ServerAccessResult(
                            status = ServerAccessStatus.READY,
                            httpStatus = response.code,
                            responseKind = ServerAccessResponseKind.JSON,
                            cookieNamesSeen = cookieNames,
                            message = "The app can reach the RomM server from native requests.",
                        )
                    } else {
                        val kind = when {
                            probe.status == 401 || probe.status == 403 -> ServerAccessResponseKind.UNAUTHORIZED
                            probe.isHtml -> ServerAccessResponseKind.HTML
                            else -> ServerAccessResponseKind.JSON
                        }
                        val message = when (kind) {
                            ServerAccessResponseKind.HTML -> "The app is still receiving a login page instead of RomM. Protected server access has not carried over yet."
                            ServerAccessResponseKind.UNAUTHORIZED -> "The protected server access step is still incomplete. Finish the edge login or retest with the selected access method."
                            else -> "The server responded, but the app could not confirm RomM access from native requests."
                        }
                        persistProfile(
                            profile.copy(
                                serverAccess = profile.serverAccess.copy(
                                    status = ServerAccessStatus.FAILED,
                                    verifiedAt = null,
                                    lastTestedAt = testedAt,
                                    lastError = message,
                                    lastHttpStatus = probe.status,
                                    lastResponseKind = kind,
                                    cookieNamesSeen = cookieNames,
                                ),
                                sessionState = profile.sessionState.copy(
                                    hasEdgeSession = profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
                                    lastValidatedAt = testedAt,
                                    canRefreshInBackground = profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
                                ),
                                updatedAt = nowIso(),
                            ),
                        )
                        ServerAccessResult(
                            status = ServerAccessStatus.FAILED,
                            httpStatus = probe.status,
                            responseKind = kind,
                            cookieNamesSeen = cookieNames,
                            message = message,
                        )
                    }
                }
            }.getOrElse { error ->
                val message = error.message ?: "Unable to reach the server from the app."
                persistProfile(
                    profile.copy(
                        serverAccess = profile.serverAccess.copy(
                            status = ServerAccessStatus.FAILED,
                            verifiedAt = null,
                            lastTestedAt = testedAt,
                            lastError = message,
                            lastHttpStatus = null,
                            lastResponseKind = ServerAccessResponseKind.NETWORK_ERROR,
                            cookieNamesSeen = emptyList(),
                        ),
                        updatedAt = nowIso(),
                    ),
                )
                ServerAccessResult(
                    status = ServerAccessStatus.FAILED,
                    responseKind = ServerAccessResponseKind.NETWORK_ERROR,
                    cookieNamesSeen = emptyList(),
                    message = message,
                )
            }
        }
    }

    suspend fun loginWithDirectCredentials(profileId: String, credentials: DirectLoginCredentials) = StorageAccess.normal {
        withContext(Dispatchers.IO) {
            val normalized = credentials.copy(username = credentials.username.trim(), password = credentials.password)
            require(normalized.username.isNotBlank() && normalized.password.isNotBlank()) {
                "Enter both your RomM username and password."
            }

            val profile = requireProfile(profileId)
            val generation = tokenRefresh.invalidate(profileId)
            val tokenLoginSucceeded = if (profile.originAuthMode != OriginAuthMode.ROMM_BASIC_LEGACY) {
                tryTokenLogin(profile, normalized, generation)
            } else {
                false
            }

            if (!tokenLoginSucceeded) {
                performLegacyBasicLogin(profile, normalized, generation)
            }

            when (val status = validateProfile(profileId)) {
                AuthStatus.CONNECTED -> Unit
                AuthStatus.REAUTH_REQUIRED_EDGE -> error("Protected server access expired. Complete the edge login step again.")
                AuthStatus.REAUTH_REQUIRED_ORIGIN -> error("RomM authentication did not complete. Check your username and password and try again.")
                else -> error("The server login completed, but the session could not be validated.")
            }
        }
    }

    suspend fun validateProfile(profileId: String? = null): AuthStatus = StorageAccess.normal {
        return@normal withContext(Dispatchers.IO) {
            val profile = profileId?.let { requireProfile(it) } ?: getActiveProfile() ?: return@withContext AuthStatus.INVALID_CONFIGURATION
            val generation = tokenRefresh.generation(profile.id)
            runCatching {
                if (profile.serverAccess.status != ServerAccessStatus.READY) {
                    if (profile.edgeAuthMode == EdgeAuthMode.NONE) {
                        AuthStatus.INVALID_CONFIGURATION
                    } else {
                        AuthStatus.REAUTH_REQUIRED_EDGE
                    }
                } else {
                    val heartbeatUrl = "${profile.baseUrl}/api/heartbeat"
                    val heartbeatRequest = Request.Builder()
                        .url(heartbeatUrl)
                        .get()
                        .applyDecoration(decorateRequest(profile.id, heartbeatUrl, includeOriginAuth = true))
                        .build()
                    val heartbeatProbe = bareClient.newCall(heartbeatRequest).execute().use { response ->

                        probeJsonResponse(response, heartbeatAdapter)
                    }

                    if (heartbeatProbe.status == 401 || heartbeatProbe.status == 403) {
                        val status = if (profile.edgeAuthMode == EdgeAuthMode.NONE) {
                            AuthStatus.REAUTH_REQUIRED_ORIGIN
                        } else {
                            AuthStatus.REAUTH_REQUIRED_EDGE
                        }
                        persistProfile(
                            profile.copy(
                                status = status,
                                sessionState = profile.sessionState.copy(
                                    hasEdgeSession = false,
                                    lastValidatedAt = nowIso(),
                                ),
                                serverAccess = profile.serverAccess.copy(
                                    status = if (profile.edgeAuthMode == EdgeAuthMode.NONE) {
                                        profile.serverAccess.status
                                    } else {
                                        ServerAccessStatus.FAILED
                                    },
                                    verifiedAt = if (profile.edgeAuthMode == EdgeAuthMode.NONE) {
                                        profile.serverAccess.verifiedAt
                                    } else {
                                        null
                                    },
                                    lastTestedAt = nowIso(),
                                    lastError = if (profile.edgeAuthMode == EdgeAuthMode.NONE) {
                                        profile.serverAccess.lastError
                                    } else {
                                        "Protected server access is no longer valid. Reauthenticate the server access step."
                                    },
                                    lastHttpStatus = heartbeatProbe.status,
                                    lastResponseKind = ServerAccessResponseKind.UNAUTHORIZED,
                                ),
                                lastValidationAt = nowIso(),
                                updatedAt = nowIso(),
                            ),
                        )
                        status
                    } else if (!heartbeatProbe.ok || heartbeatProbe.data?.system == null) {
                        val status = if (profile.edgeAuthMode == EdgeAuthMode.NONE) {
                            AuthStatus.INVALID_CONFIGURATION
                        } else {
                            AuthStatus.REAUTH_REQUIRED_EDGE
                        }
                        persistProfile(
                            profile.copy(
                                status = status,
                                sessionState = profile.sessionState.copy(
                                    hasEdgeSession = false,
                                    hasOriginSession = false,
                                    lastValidatedAt = nowIso(),
                                ),
                                serverAccess = profile.serverAccess.copy(
                                    status = ServerAccessStatus.FAILED,
                                    verifiedAt = null,
                                    lastTestedAt = nowIso(),
                                    lastError = "The app is no longer reaching RomM JSON through the configured server access layer.",
                                    lastHttpStatus = heartbeatProbe.status,
                                    lastResponseKind = when {
                                        heartbeatProbe.status == 401 || heartbeatProbe.status == 403 -> ServerAccessResponseKind.UNAUTHORIZED
                                        heartbeatProbe.isHtml -> ServerAccessResponseKind.HTML
                                        else -> ServerAccessResponseKind.JSON
                                    },
                                ),
                                lastValidationAt = nowIso(),
                                updatedAt = nowIso(),
                            ),
                        )
                        status
                    } else {
                        val meUrl = "${profile.baseUrl}/api/users/me"
                        val meRequest = Request.Builder()
                            .url(meUrl)
                            .get()
                            .applyDecoration(decorateRequest(profile.id, meUrl, includeOriginAuth = true))
                            .build()
                        val meProbe = bareClient.newCall(meRequest).execute().use { response ->
                            if (!CredentialScope(profile.baseUrl).contains(response.request.url)) {
                                throw TokenRefreshException(TokenRefreshException.Reason.ACCESS_REQUIRED)
                            }
                            probeJsonResponse(response, userAdapter)
                        }

                        val nextStatus = when {
                            meProbe.status == 401 || meProbe.status == 403 ->
                                if (profile.originAuthMode == OriginAuthMode.NONE) AuthStatus.INVALID_CONFIGURATION else AuthStatus.REAUTH_REQUIRED_ORIGIN

                            !meProbe.ok || meProbe.data?.id == null || meProbe.data.username.isBlank() ->
                                if (profile.originAuthMode == OriginAuthMode.NONE) AuthStatus.INVALID_CONFIGURATION else AuthStatus.REAUTH_REQUIRED_ORIGIN

                            else -> AuthStatus.CONNECTED
                        }

                        if (nextStatus == AuthStatus.CONNECTED) {
                            tokenRefresh.mutateSession(profile.id, generation) {
                                secretStore.bindVerifiedAccount(profile.id, VerifiedAccount(profile.baseUrl, requireNotNull(meProbe.data).id))
                            }
                        }
                        persistProfile(
                            profile.copy(
                                status = nextStatus,
                                sessionState = profile.sessionState.copy(
                                    hasEdgeSession = if (profile.edgeAuthMode == EdgeAuthMode.NONE) false else heartbeatProbe.ok,
                                    hasOriginSession = nextStatus == AuthStatus.CONNECTED,
                                    lastValidatedAt = nowIso(),
                                    canRefreshInBackground = profile.originAuthMode == OriginAuthMode.ROMM_BEARER_PASSWORD ||
                                        profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
                                ),
                                serverAccess = profile.serverAccess.copy(
                                    status = ServerAccessStatus.READY,
                                    verifiedAt = profile.serverAccess.verifiedAt ?: nowIso(),
                                    lastTestedAt = profile.serverAccess.lastTestedAt ?: nowIso(),
                                    lastError = if (nextStatus == AuthStatus.CONNECTED) null else profile.serverAccess.lastError,
                                    lastHttpStatus = heartbeatProbe.status,
                                    lastResponseKind = ServerAccessResponseKind.JSON,
                                ),
                                lastValidationAt = nowIso(),
                                updatedAt = nowIso(),
                            ),
                        )

                        nextStatus
                    }
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (error is AccountBindingException) {
                    // A browser/server changed the authenticated account. Retain the owner,
                    // but retire credentials and cookies before another request can reuse them.
                    tokenRefresh.mutateSession(profile.id, generation) {
                        secretStore.clearBasicCredentials(profile.id)
                        secretStore.clearTokenBundle(profile.id)
                    }
                    logout(profile.id)
                    throw error
                }
                if (error is TokenRefreshException) {
                    when (error.reason) {
                        TokenRefreshException.Reason.UNAVAILABLE, TokenRefreshException.Reason.INVALID_RESPONSE -> throw error
                        TokenRefreshException.Reason.INVALID_CREDENTIALS -> {
                            persistProfile(profile.copy(
                                status = AuthStatus.REAUTH_REQUIRED_ORIGIN,
                                sessionState = profile.sessionState.copy(hasOriginSession = false),
                                updatedAt = nowIso(),
                            ))
                            return@getOrElse AuthStatus.REAUTH_REQUIRED_ORIGIN
                        }
                        TokenRefreshException.Reason.ACCESS_REQUIRED -> Unit
                    }
                }
                val status = if (profile.edgeAuthMode == EdgeAuthMode.NONE) {
                    AuthStatus.REAUTH_REQUIRED_ORIGIN
                } else {
                    AuthStatus.REAUTH_REQUIRED_EDGE
                }
                persistProfile(
                    profile.copy(
                        status = status,
                        sessionState = profile.sessionState.copy(
                            hasOriginSession = false,
                            hasEdgeSession = profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
                            lastValidatedAt = nowIso(),
                        ),
                        serverAccess = profile.serverAccess.copy(
                            status = if (profile.edgeAuthMode == EdgeAuthMode.NONE) {
                                profile.serverAccess.status
                            } else {
                                ServerAccessStatus.FAILED
                            },
                            verifiedAt = if (profile.edgeAuthMode == EdgeAuthMode.NONE) {
                                profile.serverAccess.verifiedAt
                            } else {
                                null
                            },
                            lastTestedAt = nowIso(),
                            lastError = error.message ?: "The app could not validate the current server session.",
                            lastResponseKind = ServerAccessResponseKind.NETWORK_ERROR,
                        ),
                        lastValidationAt = nowIso(),
                        updatedAt = nowIso(),
                    ),
                )
                status
            }
        }
    }

    suspend fun logout(profileId: String? = null, clearServerAccess: Boolean = false) = StorageAccess.normal {
        val profile = profileId?.let { requireProfile(it) } ?: getActiveProfile() ?: return@normal
        tokenRefresh.invalidate(profile.id) {
            secretStore.clearBasicCredentials(profile.id)
            secretStore.clearTokenBundle(profile.id)
        }
        profileCookies.clear(profile.id, profile.baseUrl)
        val clearedEdgeCookies = profile.edgeAuthMode in setOf(EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION, EdgeAuthMode.GENERIC_COOKIE_SSO)
        if (clearServerAccess) { secretStore.clearCloudflareCredentials(profile.id); secretStore.clearDeviceId(profile.id) }
        persistProfile(
            profile.copy(
                sessionState = profile.sessionState.copy(
                    hasOriginSession = false,
                    hasEdgeSession = if (clearServerAccess || clearedEdgeCookies) false else profile.sessionState.hasEdgeSession,
                    lastValidatedAt = nowIso(),
                ),
                status = if (!clearServerAccess && !clearedEdgeCookies && profile.serverAccess.status == ServerAccessStatus.READY) {
                    AuthStatus.REAUTH_REQUIRED_ORIGIN
                } else {
                    AuthStatus.INVALID_CONFIGURATION
                },
                serverAccess = if (clearServerAccess || clearedEdgeCookies) ServerAccessState() else profile.serverAccess,
                updatedAt = nowIso(),
            ),
        )
    }

    suspend fun clearServerAccess(profileId: String) = StorageAccess.normal {
        val profile = requireProfile(profileId)
        tokenRefresh.invalidate(profile.id) {
            secretStore.clearBasicCredentials(profile.id)
            secretStore.clearTokenBundle(profile.id)
        }
        secretStore.clearCloudflareCredentials(profile.id)
        secretStore.clearDeviceId(profile.id)
        profileCookies.clear(profile.id, profile.baseUrl)
        persistProfile(
            profile.copy(
                serverAccess = ServerAccessState(),
                sessionState = profile.sessionState.copy(
                    hasEdgeSession = false,
                    hasOriginSession = false,
                    lastValidatedAt = nowIso(),
                ),
                status = AuthStatus.INVALID_CONFIGURATION,
                updatedAt = nowIso(),
            ),
        )
    }

    suspend fun deleteProfile(profileId: String): ServerProfile? = StorageAccess.normal {
        val profile = requireProfile(profileId)
        val wasActive = profile.isActive
        tokenRefresh.invalidate(profile.id) { secretStore.clearAll(profile.id) }
        profileCookies.clear(profile.id, profile.baseUrl)
        serverProfileDao.delete(profile.id)
        if (wasActive) {
            val nextProfile = serverProfileDao.listAll()
                .sortedByDescending { it.updatedAt }
                .firstOrNull()
                ?.toModel()
            if (nextProfile != null) {
                serverProfileDao.setActiveOnly(nextProfile.id, nowIso())
            }
        }
        return@normal getActiveProfile()
    }

    suspend fun decorateRequest(profileId: String, url: String, includeOriginAuth: Boolean): RequestDecoration = StorageAccess.normal {
        val profile = requireProfile(profileId)
        if (!CredentialScope(profile.baseUrl).contains(url.toHttpUrl())) {
            return@normal RequestDecoration(url, emptyMap(), shouldPersistCookies = false, credentialBaseUrl = profile.baseUrl)
        }
        val headers = linkedMapOf<String, String>()

        if (profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE) {
            secretStore.getCloudflareCredentials(profile.id)?.let {
                headers["CF-Access-Client-Id"] = it.clientId
                headers["CF-Access-Client-Secret"] = it.clientSecret
            }
        }

        if (includeOriginAuth) {
            secretStore.verifiedAccount(profile.id)?.let {
                if (it.baseUrl != profile.baseUrl) throw AccountBindingException()
            }
            when (profile.originAuthMode) {
                OriginAuthMode.ROMM_BEARER_PASSWORD -> {
                    refreshBearerTokenIfNeeded(profile.id)
                    secretStore.getTokenBundle(profile.id)?.let { bundle ->
                        headers["Authorization"] = "${bundle.tokenType} ${bundle.accessToken}"
                    }
                }

                OriginAuthMode.ROMM_BASIC_LEGACY -> {
                    secretStore.getBasicCredentials(profile.id)?.let { credentials ->
                        headers["Authorization"] = okhttp3.Credentials.basic(credentials.username, credentials.password)
                    }
                }

                else -> Unit
            }
        }

        val cookieContext = if (shouldSendCookies(profile)) profileCookies.context(profile.id, profile.baseUrl) else null
        if (cookieContext != null) {
            profileCookies.header(cookieContext, url.toHttpUrl())?.let { cookieHeader ->
                headers["Cookie"] = cookieHeader
            }
        }

        return@normal RequestDecoration(url = url, headers = headers, credentialBaseUrl = profile.baseUrl, cookieContext = cookieContext)
    }

    fun verifiedAccount(profileId: String): VerifiedAccount? = secretStore.verifiedAccount(profileId)

    fun getDeviceId(profileId: String): String? = secretStore.getDeviceId(profileId)

    fun storeDeviceId(profileId: String, deviceId: String) = secretStore.storeDeviceId(profileId, deviceId)

    suspend fun ensureDeviceId(profileId: String): String = StorageAccess.normal {
        return@normal withContext(Dispatchers.IO) {
            secretStore.getDeviceId(profileId)?.let { return@withContext it }
            val profile = requireProfile(profileId)
            val payload = moshi.adapter(DeviceRegistrationRequest::class.java).toJson(
                DeviceRegistrationRequest(name = "RomM Native ${android.os.Build.MODEL}"),
            )
            val request = Request.Builder()
                .url("${profile.baseUrl}/api/devices")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .applyDecoration(decorateRequest(profile.id, "${profile.baseUrl}/api/devices", includeOriginAuth = true))
                .build()
            val deviceId = bareClient.newCall(request).execute().use { response ->

                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Device registration failed with ${response.code}")
                }
                deviceRegistrationResponseAdapter.fromJson(body)?.deviceId ?: error("Device registration returned an invalid response.")
            }
            secretStore.storeDeviceId(profileId, deviceId)
            deviceId
        }
    }

    private suspend fun performLegacyBasicLogin(profile: ServerProfile, credentials: DirectLoginCredentials, generation: Long) {
        withContext(Dispatchers.IO) {
            val user = verifyCandidate(profile, okhttp3.Credentials.basic(credentials.username, credentials.password))

            tokenRefresh.mutateSession(profile.id, generation) {
                secretStore.bindVerifiedAccount(profile.id, VerifiedAccount(profile.baseUrl, user.id))
                secretStore.storeBasicCredentials(profile.id, credentials)
                secretStore.clearTokenBundle(profile.id)
            }
            persistProfile(profile.copy(originAuthMode = OriginAuthMode.ROMM_BASIC_LEGACY, updatedAt = nowIso()))
        }
    }

    private suspend fun tryTokenLogin(profile: ServerProfile, credentials: DirectLoginCredentials, generation: Long): Boolean {
        return withContext(Dispatchers.IO) {
            val decoration = decorateRequest(profile.id, "${profile.baseUrl}/api/token", includeOriginAuth = false)
            val body = FormBody.Builder()
                .add("grant_type", "password")
                .add("username", credentials.username)
                .add("password", credentials.password)
                .add("scope", MOBILE_APP_SCOPES)
                .build()
            val request = Request.Builder()
                .url("${profile.baseUrl}/api/token")
                .post(body)
                .applyDecoration(decoration)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            bareClient.newCall(request).execute().use { response ->
                if (!CredentialScope(profile.baseUrl).contains(response.request.url)) {
                    throw TokenRefreshException(TokenRefreshException.Reason.ACCESS_REQUIRED)
                }

                val responseText = response.body?.string().orEmpty()
                if (looksLikeHtmlResponse(responseText, response.header("Content-Type"))) {
                    throw TokenRefreshException(TokenRefreshException.Reason.ACCESS_REQUIRED)
                }
                if (response.code == 404 || response.code == 405) return@use false
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) {
                        throw IOException("RomM rejected the username and password. Check the credentials and try again.")
                    }
                    throw TokenRefreshException(TokenRefreshException.Reason.UNAVAILABLE, response.code)
                }
                val tokenResponse = tokenAdapter.fromJson(responseText)
                    ?: throw TokenRefreshException(TokenRefreshException.Reason.INVALID_RESPONSE)
                val accessToken = tokenResponse.access_token?.takeIf { it.isNotBlank() }
                    ?: throw TokenRefreshException(TokenRefreshException.Reason.INVALID_RESPONSE)
                val expiresAt = (tokenResponse.expires_in ?: tokenResponse.expires)?.let { seconds ->
                    Instant.ofEpochMilli(System.currentTimeMillis() + seconds * 1000).toString()
                }
                val user = verifyCandidate(profile, "${tokenResponse.token_type ?: "Bearer"} $accessToken")
                tokenRefresh.mutateSession(profile.id, generation) {
                    secretStore.bindVerifiedAccount(profile.id, VerifiedAccount(profile.baseUrl, user.id))
                    secretStore.storeTokenBundle(
                        profile.id,
                        TokenBundle(
                            accessToken = accessToken,
                            refreshToken = tokenResponse.refresh_token,
                            tokenType = tokenResponse.token_type ?: "Bearer",
                            expiresAt = expiresAt,
                        ),
                    )
                    secretStore.clearBasicCredentials(profile.id)
                }
                persistProfile(profile.copy(originAuthMode = OriginAuthMode.ROMM_BEARER_PASSWORD, updatedAt = nowIso()))
                true
            }
        }
    }

    private suspend fun verifyCandidate(profile: ServerProfile, authorization: String): UserDto {
        val url = "${profile.baseUrl}/api/users/me"
        val request = Request.Builder().url(url).get()
            .applyDecoration(decorateRequest(profile.id, url, includeOriginAuth = false))
            .header("Accept", "application/json").header("Authorization", authorization).build()
        return bareClient.newCall(request).execute().use { response ->
            if (!CredentialScope(profile.baseUrl).contains(response.request.url)) {
                throw TokenRefreshException(TokenRefreshException.Reason.ACCESS_REQUIRED)
            }
            val probe = probeJsonResponse(response, userAdapter)
            if (!probe.ok || probe.data == null || probe.data.username.isBlank()) {
                throw IOException("RomM did not verify the account for these credentials.")
            }
            probe.data
        }
    }

    private suspend fun refreshBearerTokenIfNeeded(profileId: String) {
        tokenRefresh.prepare(profileId)
    }

    suspend fun refreshAfterUnauthorized(profileId: String, url: String, rejectedAuthorization: String): Boolean = StorageAccess.normal {
        val profile = requireProfile(profileId)
        if (profile.originAuthMode != OriginAuthMode.ROMM_BEARER_PASSWORD ||
            !CredentialScope(profile.baseUrl).contains(url.toHttpUrl())) return@normal false
        tokenRefresh.prepare(profileId, rejectedAuthorization)
        val bundle = secretStore.getTokenBundle(profileId) ?: return@normal false
        return@normal "${bundle.tokenType} ${bundle.accessToken}" != rejectedAuthorization
    }

    private suspend fun performTokenRefresh(profileId: String, bundle: TokenBundle): TokenBundle = withContext(Dispatchers.IO) {
        val profile = requireProfile(profileId)
        val url = "${profile.baseUrl}/api/token"
        val request = Request.Builder()
            .url(url)
            .post(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", requireNotNull(bundle.refreshToken)).build())
            .applyDecoration(decorateRequest(profileId, url, includeOriginAuth = false))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        try {
            bareClient.newCall(request).execute().use { response ->
                if (!CredentialScope(profile.baseUrl).contains(response.request.url)) {
                    throw TokenRefreshException(TokenRefreshException.Reason.ACCESS_REQUIRED)
                }

                val candidate = TokenRefreshResponse.decode(response.code, response.header("Content-Type"), response.body?.string().orEmpty(), bundle)
                secretStore.verifiedAccount(profileId)?.let { owner ->
                    val user = verifyCandidate(profile, "${candidate.tokenType} ${candidate.accessToken}")
                    if (owner != VerifiedAccount(profile.baseUrl, user.id)) throw AccountBindingException()
                }
                candidate
            }
        } catch (error: IOException) {
            if (error is TokenRefreshException || error is AccountBindingException) throw error
            throw TokenRefreshException(TokenRefreshException.Reason.UNAVAILABLE)
        }
    }

    private fun shouldSendCookies(profile: ServerProfile): Boolean {
        return when {
            profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE ->
                profile.originAuthMode == OriginAuthMode.ROMM_OIDC_SESSION

            profile.edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION -> true
            profile.edgeAuthMode == EdgeAuthMode.GENERIC_COOKIE_SSO -> true
            profile.originAuthMode == OriginAuthMode.ROMM_OIDC_SESSION -> true
            else -> false
        }
    }

    private suspend fun getCookieHeader(profile: ServerProfile, url: String): String? =
        profileCookies.header(profileCookies.context(profile.id, profile.baseUrl), url.toHttpUrl())

    private suspend fun getCookieNames(profile: ServerProfile): List<String> =
        getCookieHeader(profile, "${profile.baseUrl.removeSuffix("/")}/api/users/me").orEmpty().split(';')
            .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }.distinct()

    private suspend fun hasRequiredEdgeCookie(profile: ServerProfile): Boolean {
        val names = getCookieNames(profile)
        return when (profile.edgeAuthMode) {
            EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION -> names.any { it.startsWith("CF_Authorization") }
            EdgeAuthMode.GENERIC_COOKIE_SSO -> names.isNotEmpty()
            else -> false
        }
    }

    private fun looksLikeHtmlResponse(body: String, contentType: String?): Boolean {
        val normalizedType = contentType?.lowercase().orEmpty()
        val trimmed = body.trimStart()
        return normalizedType.contains("text/html") ||
            trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.startsWith("<")
    }

    private fun <T> probeJsonResponse(response: okhttp3.Response, adapter: com.squareup.moshi.JsonAdapter<T>): JsonProbeResult<T> {
        val body = response.body?.string().orEmpty()
        val isHtml = looksLikeHtmlResponse(body, response.header("Content-Type"))
        if (!response.isSuccessful || isHtml) {
            return JsonProbeResult(
                ok = false,
                status = response.code,
                isHtml = isHtml,
                bodyPreview = body.take(200),
            )
        }
        if (body.isBlank()) {
            return JsonProbeResult(ok = true, status = response.code, isHtml = false, data = null, bodyPreview = "")
        }
        return try {
            JsonProbeResult(
                ok = true,
                status = response.code,
                isHtml = false,
                data = adapter.fromJson(body),
                bodyPreview = body.take(200),
            )
        } catch (_: Exception) {
            JsonProbeResult(ok = false, status = response.code, isHtml = false, bodyPreview = body.take(200))
        }
    }

    private suspend fun requireProfile(profileId: String): ServerProfile {
        return getProfile(profileId) ?: error("Server profile not found.")
    }

    private suspend fun persistProfile(profile: ServerProfile) {
        serverProfileDao.upsert(profile.toEntity())
        if (profile.isActive) {
            serverProfileDao.setActiveOnly(profile.id, profile.updatedAt)
        }
    }

    private fun ServerProfileEntity.toModel(): ServerProfile {
        return ServerProfile(
            id = id,
            label = label,
            baseUrl = baseUrl,
            edgeAuthMode = runCatching { EdgeAuthMode.valueOf(edgeAuthMode) }.getOrDefault(EdgeAuthMode.NONE),
            originAuthMode = runCatching { OriginAuthMode.valueOf(originAuthMode) }.getOrDefault(OriginAuthMode.ROMM_BASIC_LEGACY),
            capabilities = jsonCodec.decodeCapabilities(capabilitiesJson),
            serverAccess = jsonCodec.decodeServerAccess(serverAccessJson),
            sessionState = jsonCodec.decodeSessionState(sessionStateJson),
            isActive = isActive,
            status = runCatching { AuthStatus.valueOf(status) }.getOrDefault(AuthStatus.INVALID_CONFIGURATION),
            lastValidationAt = lastValidationAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun ServerProfile.toEntity(): ServerProfileEntity {
        return ServerProfileEntity(
            id = id,
            label = label,
            baseUrl = baseUrl,
            edgeAuthMode = edgeAuthMode.name,
            originAuthMode = originAuthMode.name,
            capabilitiesJson = jsonCodec.encodeCapabilities(capabilities),
            serverAccessJson = jsonCodec.encodeServerAccess(serverAccess),
            sessionStateJson = jsonCodec.encodeSessionState(sessionState),
            isActive = isActive,
            status = status.name,
            lastValidationAt = lastValidationAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun labelFromUrl(baseUrl: String): String {
        return runCatching { java.net.URL(baseUrl).host }.getOrDefault("RomM server")
    }

    private fun nowIso(): String = Instant.now().toString()

    private fun Request.Builder.applyDecoration(decoration: RequestDecoration): Request.Builder {
        decoration.credentialBaseUrl?.let { tag(CredentialScope::class.java, CredentialScope(it)) }
        tag(ProfileCookieContext::class.java, decoration.cookieContext)
        decoration.headers.forEach { (key, value) -> header(key, value) }
        return this
    }

    companion object {
        private const val MOBILE_APP_SCOPES =
            "me.read me.write roms.read platforms.read assets.read assets.write devices.read devices.write firmware.read collections.read roms.user.read roms.user.write"
    }
}
