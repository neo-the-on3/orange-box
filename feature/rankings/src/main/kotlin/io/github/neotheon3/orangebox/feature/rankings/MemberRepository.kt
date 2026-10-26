package io.github.neotheon3.orangebox.feature.rankings

import io.github.neotheon3.orangebox.core.datastore.MemberBackend
import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.network.BackendApi
import io.github.neotheon3.orangebox.core.network.MyRankingDto
import io.github.neotheon3.orangebox.core.network.PointsBalanceDto
import io.github.neotheon3.orangebox.core.network.PointsLedgerPageDto
import io.github.neotheon3.orangebox.core.network.ProfileDto
import io.github.neotheon3.orangebox.core.network.ProfilePatchDto
import io.github.neotheon3.orangebox.core.network.RankingPageDto
import io.github.neotheon3.orangebox.core.network.StatsSummaryDto
import java.time.ZoneId
import javax.inject.Inject

/** A member-feature call's outcome, as the screens show it. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Ready<T>(val value: T) : Load<T>

    /** The server hasn't built this yet (`404` on the endpoint, or `501`): a calm "not available" state. */
    data object Unavailable : Load<Nothing>

    /** Remote without a session (or it expired): sign in from Settings. */
    data object SignedOut : Load<Nothing>

    data class Failed(val reason: Reason) : Load<Nothing>

    enum class Reason { NETWORK, BUSY, SERVER }
}

/**
 * Stats, rankings and points over the member backend (#68), with the server's errors mapped to [Load] states.
 * The rankings backend (#113) may not be live yet, so a `404` on a list endpoint or a `501` anywhere is
 * [Load.Unavailable], not an error. A `404` on `/rankings/{metric}/me` is the spec's "not ranked": `Ready(null)`.
 */
class MemberRepository internal constructor(private val backend: MemberBackend, private val zone: () -> ZoneId) {
    @Inject constructor(backend: MemberBackend) : this(backend, ZoneId::systemDefault)

    private val tz: String get() = zone().id

    private suspend fun <T> call(notFound: (() -> Load<T>)? = null, block: suspend (BackendApi) -> T): Load<T> {
        val api = backend.api() ?: return Load.SignedOut
        return try {
            Load.Ready(block(api))
        } catch (e: ApiError.NotSignedIn) {
            Load.SignedOut
        } catch (e: ApiError.Network) {
            Load.Failed(Load.Reason.NETWORK)
        } catch (e: ApiError.Http) {
            when {
                e.status == 404 && notFound != null -> notFound()
                e.status == 404 || e.status == 501 -> Load.Unavailable
                e.status == 401 -> Load.SignedOut
                e.status == 429 || e.status == 503 -> Load.Failed(Load.Reason.BUSY)
                else -> Load.Failed(Load.Reason.SERVER)
            }
        }
    }

    suspend fun summary(period: String): Load<StatsSummaryDto> = call { it.getStatsSummary(period, tz) }

    suspend fun ranking(metric: String, period: String, cursor: String? = null): Load<RankingPageDto> =
        call { it.listRanking(metric, period, tz, cursor = cursor) }

    /** `Ready(null)`: not ranked for this period (no trips, or not opted in). */
    suspend fun myRanking(metric: String, period: String): Load<MyRankingDto?> =
        call(notFound = { Load.Ready(null) }) { it.getMyRanking(metric, period, tz) }

    suspend fun profile(): Load<ProfileDto> = call { it.getMe().value }

    /**
     * `PATCH /v1/me {rankingOptIn}` with the profile's current `ETag`. A `412` / `428` (changed elsewhere, or no
     * ETag yet) fetches it again and retries once.
     */
    suspend fun setRankingOptIn(on: Boolean): Load<ProfileDto> = call { api ->
        var current = api.getMe()
        try {
            api.patchMe(ProfilePatchDto(rankingOptIn = on), current.etag).value
        } catch (e: ApiError.Http) {
            if (e.status != 412 && e.status != 428) throw e
            current = api.getMe()
            api.patchMe(ProfilePatchDto(rankingOptIn = on), current.etag).value
        }
    }

    suspend fun points(): Load<PointsBalanceDto> = call { it.getPointsBalance() }

    suspend fun ledger(cursor: String? = null): Load<PointsLedgerPageDto> = call { it.listPointsLedger(cursor) }
}
