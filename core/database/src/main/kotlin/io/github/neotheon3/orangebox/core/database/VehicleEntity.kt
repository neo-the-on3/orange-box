package io.github.neotheon3.orangebox.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID
import io.github.neotheon3.orangebox.core.model.Vehicle
import io.github.neotheon3.orangebox.core.model.VehicleFuel

@Entity(tableName = "vehicles", indices = [Index(value = ["clientVehicleId"], unique = true)])
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val maker: String?,
    val model: String?,
    /** [VehicleFuel] name; unknown values read back as PETROL. */
    val fuel: String,
    val displacementCc: Int?,
    val odometerKm: Int?,
    val createdAtMillis: Long,
    /** Backend `clientVehicleId` (UUID): set once, kept across edits (schema 5, M9). */
    @ColumnInfo(defaultValue = "''") val clientVehicleId: String = UUID.randomUUID().toString(),
    /** The backend account that added or pulled it (schema 6); null when added signed out. Edits keep it. */
    val accountId: String? = null,
) {
    fun toModel() = Vehicle(
        id = id, name = name, maker = maker, model = model,
        fuel = VehicleFuel.entries.firstOrNull { it.name == fuel } ?: VehicleFuel.PETROL,
        displacementCc = displacementCc, odometerKm = odometerKm,
    )

    companion object {
        fun from(v: Vehicle, createdAtMillis: Long) = VehicleEntity(
            id = v.id, name = v.name.trim(), maker = v.maker, model = v.model, fuel = v.fuel.name,
            displacementCc = v.displacementCc, odometerKm = v.odometerKm, createdAtMillis = createdAtMillis,
        )
    }
}
