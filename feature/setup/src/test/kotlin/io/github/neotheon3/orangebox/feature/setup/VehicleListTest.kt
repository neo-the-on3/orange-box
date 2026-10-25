package io.github.neotheon3.orangebox.feature.setup

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.model.Vehicle
import io.github.neotheon3.orangebox.core.model.VehicleFuel
import io.github.neotheon3.orangebox.core.testing.FakeVehicleRepository
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** #61: several vehicles, choose the active one, add / edit / delete. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class VehicleListTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val main = MainDispatcherRule()

    private val swift = Vehicle(1, "Maruti Swift", "Maruti Suzuki", "Swift 1.2", VehicleFuel.PETROL, 1197, 42_000)
    private val van = Vehicle(2, "Work van", fuel = VehicleFuel.DIESEL, displacementCc = 2499)

    private fun shot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/vehicles/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `list shows every vehicle, the active one selected, and reports actions`() {
        val events = mutableListOf<String>()
        compose.setContent {
            OrangeBoxTheme {
                VehicleListScreen(
                    VehicleListUiState(listOf(swift, van), activeId = 1, loaded = true), onBack = {},
                    onEdit = { events += "edit $it" }, onAdd = { events += "add" }, onSelect = { events += "select $it" }, onDelete = { events += "delete $it" },
                )
            }
        }
        compose.onNodeWithTag("vehicle_row_1").assertIsSelected().assertTextContains("Active", substring = true)
        compose.onNodeWithTag("vehicle_row_2").assertIsNotSelected().assertTextContains("Diesel · 2499 cc", substring = true)
        shot("vehicles_list")
        compose.onNodeWithTag("vehicle_row_2").performClick()
        compose.onNodeWithTag("vehicle_edit_2").performClick()
        compose.onNodeWithTag("vehicles_add").performClick()
        compose.onNodeWithTag("vehicle_delete_2").performClick()
        compose.onNodeWithTag("vehicles_delete_confirm").assertIsDisplayed()
        shot("vehicles_delete_confirm")
        compose.onNodeWithTag("vehicles_delete_confirm").performClick()
        assertEquals(listOf("select 2", "edit 2", "add", "delete 2"), events)
    }

    @Test fun `empty list explains what to do`() {
        compose.setContent {
            OrangeBoxTheme(darkTheme = true) {
                VehicleListScreen(VehicleListUiState(loaded = true), onBack = {}, onEdit = {}, onAdd = {}, onSelect = {}, onDelete = {})
            }
        }
        compose.onNodeWithTag("vehicles_empty").assertIsDisplayed()
        shot("vehicles_empty_dark")
    }

    @Test fun `view model switches the active vehicle and deleting it activates the next`() = runTest {
        val repo = FakeVehicleRepository(listOf(swift, van), activeId = 1)
        val vm = VehicleListViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(VehicleListUiState(listOf(swift, van), 1, loaded = true), vm.state.value)
        vm.setActive(2)
        assertEquals(2L, vm.state.value.activeId)
        vm.delete(2)
        assertEquals(listOf(swift), vm.state.value.vehicles)
        assertEquals("the remaining car becomes active", 1L, repo.activeVehicle.first()?.id)
        vm.delete(1)
        assertNull(repo.activeVehicle.first())
    }

    @Test fun `deleting an inactive vehicle keeps the active one`() = runTest {
        val repo = FakeVehicleRepository(listOf(swift, van), activeId = 1)
        VehicleListViewModel(repo).delete(2)
        assertEquals(1L, repo.activeVehicle.first()?.id)
    }

    @Test fun `editing from the list loads that vehicle and keeps the active one`() = runTest {
        val repo = FakeVehicleRepository(listOf(swift, van), activeId = 1)
        val vm = VehicleSetupViewModel(repo, SavedStateHandle(mapOf(VehicleEditDestination.ARG to 2L)))
        assertEquals("Work van", vm.state.value.form.name)
        assertEquals(VehicleSource.MANUAL, vm.state.value.form.source)
        vm.onFormChange(vm.state.value.form.copy(name = "Work van 2", odometerKm = "1500"))
        vm.save()
        assertEquals(Vehicle(2, "Work van 2", fuel = VehicleFuel.DIESEL, displacementCc = 2499, odometerKm = 1500), repo.all.value.first { it.id == 2L })
        assertEquals(1L, repo.activeVehicle.first()?.id)
    }

    @Test fun `adding from the list starts blank and activates only the first vehicle`() = runTest {
        val repo = FakeVehicleRepository(listOf(swift), activeId = 1)
        val add = VehicleSetupViewModel(repo, SavedStateHandle(mapOf(VehicleEditDestination.ARG to 0L)))
        assertEquals(VehicleForm(), add.state.value.form)
        add.onFormChange(VehicleForm(source = VehicleSource.MANUAL, name = "Bike", fuel = VehicleFuel.PETROL))
        add.save()
        assertEquals(2, repo.all.value.size)
        assertEquals(1L, repo.activeVehicle.first()?.id)

        val empty = FakeVehicleRepository()
        val first = VehicleSetupViewModel(empty, SavedStateHandle(mapOf(VehicleEditDestination.ARG to 0L)))
        first.onFormChange(VehicleForm(source = VehicleSource.MANUAL, name = "Only car"))
        first.save()
        assertEquals("Only car", empty.activeVehicle.first()?.name)
    }
}
