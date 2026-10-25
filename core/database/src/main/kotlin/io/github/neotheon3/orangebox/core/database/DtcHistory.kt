package io.github.neotheon3.orangebox.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One trouble code as the car reported it over time (#51). A record is *open* ([endedAtMillis] null)
 * while the code keeps showing up in reads; it ends when the user clears codes ([END_CLEARED]) or a
 * later successful read of the same list no longer reports it ([END_RESOLVED]). The same code coming
 * back later opens a new record.
 */
@Entity(tableName = "dtc_records", indices = [Index("code"), Index("lastSeenAtMillis")])
data class DtcRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SAE J2012 form, e.g. `P0300`. */
    val code: String,
    /** [KIND_STORED], [KIND_PENDING] or [KIND_PERMANENT]. */
    val kind: String,
    val vehicleId: Long?,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val endedAtMillis: Long? = null,
    val endReason: String? = null,
) {
    val isOpen: Boolean get() = endedAtMillis == null

    companion object {
        const val KIND_STORED = "stored"
        const val KIND_PENDING = "pending"
        const val KIND_PERMANENT = "permanent"
        const val END_CLEARED = "cleared"
        const val END_RESOLVED = "resolved"
    }
}

@Dao
interface DtcRecordDao {
    @Query("SELECT * FROM dtc_records ORDER BY lastSeenAtMillis DESC, id DESC")
    fun observeAll(): Flow<List<DtcRecordEntity>>

    @Query("SELECT * FROM dtc_records WHERE endedAtMillis IS NULL")
    suspend fun open(): List<DtcRecordEntity>

    @Insert
    suspend fun insert(record: DtcRecordEntity): Long

    @Update
    suspend fun update(records: List<DtcRecordEntity>): Int

    @Query("UPDATE dtc_records SET endedAtMillis = :at, endReason = :reason WHERE endedAtMillis IS NULL AND kind IN (:kinds)")
    suspend fun endOpen(kinds: List<String>, at: Long, reason: String): Int

    @Query("DELETE FROM dtc_records")
    suspend fun deleteAll(): Int

    /**
     * Applies one read: [lists] maps a kind to the codes it returned (kinds that could not be read are
     * left out, so their records stay as they are). Codes already open get [at] as last seen, new codes
     * open a record, and open records missing from their list end as resolved.
     */
    @Transaction
    suspend fun applyReading(lists: Map<String, List<String>>, vehicleId: Long?, at: Long) {
        val open = open().filter { it.kind in lists.keys && it.vehicleId == vehicleId }
        val touched = mutableListOf<DtcRecordEntity>()
        for ((kind, codes) in lists) {
            val openOfKind = open.filter { it.kind == kind }.associateBy { it.code }
            val reported = codes.map { it.uppercase() }.toSet()
            for (code in reported) {
                val existing = openOfKind[code]
                if (existing != null) {
                    touched += existing.copy(lastSeenAtMillis = at)
                } else {
                    insert(DtcRecordEntity(code = code, kind = kind, vehicleId = vehicleId, firstSeenAtMillis = at, lastSeenAtMillis = at))
                }
            }
            openOfKind.values.filter { it.code !in reported }
                .forEach { touched += it.copy(endedAtMillis = at, endReason = DtcRecordEntity.END_RESOLVED) }
        }
        if (touched.isNotEmpty()) update(touched)
    }
}

/** DTC history (#51): what the car reported, when, and when it went away. */
interface DtcHistoryRepository {
    /** Newest first. */
    val history: Flow<List<DtcRecordEntity>>

    /** See [DtcRecordDao.applyReading]. */
    suspend fun recordReading(lists: Map<String, List<String>>, vehicleId: Long?, at: Long)

    /** Mode 04 was acknowledged: stored and pending codes end as cleared (permanent ones stay, as on a real ECU). */
    suspend fun recordCleared(at: Long)

    suspend fun deleteAll()
}

@Singleton
class RoomDtcHistoryRepository @Inject constructor(private val dao: DtcRecordDao) : DtcHistoryRepository {
    override val history: Flow<List<DtcRecordEntity>> = dao.observeAll()

    override suspend fun recordReading(lists: Map<String, List<String>>, vehicleId: Long?, at: Long) =
        dao.applyReading(lists, vehicleId, at)

    override suspend fun recordCleared(at: Long) {
        dao.endOpen(listOf(DtcRecordEntity.KIND_STORED, DtcRecordEntity.KIND_PENDING), at, DtcRecordEntity.END_CLEARED)
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }
}
