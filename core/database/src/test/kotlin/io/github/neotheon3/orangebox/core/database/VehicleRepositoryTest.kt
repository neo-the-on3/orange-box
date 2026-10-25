package io.github.neotheon3.orangebox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.model.Vehicle
import io.github.neotheon3.orangebox.core.model.VehicleCatalogue
import io.github.neotheon3.orangebox.core.model.VehicleFuel
import io.github.neotheon3.orangebox.core.testing.FakeAppPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VehicleRepositoryTest {
    private lateinit var db: OrangeBoxDatabase
    private val prefs = FakeAppPreferencesRepository()
    private lateinit var repo: RoomVehicleRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OrangeBoxDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RoomVehicleRepository(db.vehicleDao(), prefs).also { var t = 1000L; it.clock = { t++ } }
    }

    @After fun tearDown() = db.close()

    @Test fun `create read update delete`() = runTest {
        val id = repo.save(Vehicle(name = "My car", fuel = VehicleFuel.DIESEL, displacementCc = 1500, odometerKm = 12000))
        assertTrue(id > 0)
        assertEquals(listOf(Vehicle(id, "My car", null, null, VehicleFuel.DIESEL, 1500, 12000)), repo.vehicles.first())

        repo.save(repo.vehicles.first().single().copy(name = "Renamed", odometerKm = 12500))
        assertEquals("Renamed", repo.vehicles.first().single().name)
        assertEquals(12500, repo.vehicles.first().single().odometerKm)

        val second = repo.save(VehicleCatalogue.models.first().toVehicle())
        assertEquals(listOf(id, second), repo.vehicles.first().map { it.id })

        repo.delete(id)
        assertEquals(listOf(second), repo.vehicles.first().map { it.id })
    }

    @Test fun `active vehicle follows the preference and clears on delete`() = runTest {
        assertNull(repo.activeVehicle.first())
        val id = repo.save(Vehicle(name = "A"))
        repo.setActive(id)
        assertEquals("A", repo.activeVehicle.first()?.name)
        repo.delete(id)
        assertNull(prefs.state.value.activeVehicleId)
        assertNull(repo.activeVehicle.first())
    }

    @Test fun `several vehicles keep their order and data, and the active one can change (#61)`() = runTest {
        val a = repo.save(Vehicle(name = "A", fuel = VehicleFuel.PETROL, displacementCc = 1200))
        val b = repo.save(Vehicle(name = "B", fuel = VehicleFuel.DIESEL, odometerKm = 5))
        val c = repo.save(Vehicle(name = "C", fuel = VehicleFuel.CNG))
        assertEquals(listOf("A", "B", "C"), repo.vehicles.first().map { it.name })
        repo.setActive(b)
        assertEquals("B", repo.activeVehicle.first()?.name)
        // Editing keeps the creation order and the id.
        repo.save(repo.vehicles.first().first { it.id == a }.copy(name = "A2", fuel = VehicleFuel.LPG))
        assertEquals(listOf(a to "A2", b to "B", c to "C"), repo.vehicles.first().map { it.id to it.name })
        assertEquals(VehicleFuel.LPG, db.vehicleDao().get(a)?.toModel()?.fuel)
        assertEquals(3, db.vehicleDao().count())
        // Deleting a non-active vehicle leaves the active one alone.
        repo.delete(c)
        assertEquals(b, prefs.state.value.activeVehicleId)
        repo.setActive(a)
        assertEquals("A2", repo.activeVehicle.first()?.name)
    }

    @Test fun `unknown stored fuel reads back as petrol`() {
        val e = VehicleEntity(1, "X", null, null, "PLUTONIUM", null, null, 0)
        assertEquals(VehicleFuel.PETROL, e.toModel().fuel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank name is rejected`() { Vehicle(name = " ") }

    @Test fun `catalogue makers are sorted and every model resolves`() {
        assertEquals(VehicleCatalogue.makers.sorted(), VehicleCatalogue.makers)
        VehicleCatalogue.makers.forEach { assertTrue(VehicleCatalogue.modelsOf(it).isNotEmpty()) }
    }
}
