package io.github.neotheon3.orangebox.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY createdAtMillis, id")
    fun observeAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    fun observe(id: Long): Flow<VehicleEntity?>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun get(id: Long): VehicleEntity?

    @Insert
    suspend fun insert(vehicle: VehicleEntity): Long

    @Update
    suspend fun update(vehicle: VehicleEntity): Int

    @Query("DELETE FROM vehicles WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT COUNT(*) FROM vehicles")
    suspend fun count(): Int
}
