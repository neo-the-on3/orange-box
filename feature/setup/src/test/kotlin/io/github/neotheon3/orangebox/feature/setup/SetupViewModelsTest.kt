package io.github.neotheon3.orangebox.feature.setup

import androidx.lifecycle.SavedStateHandle
import io.github.neotheon3.orangebox.core.bluetooth.BluetoothAvailability
import io.github.neotheon3.orangebox.core.bluetooth.DongleCandidate
import io.github.neotheon3.orangebox.core.bluetooth.DongleTestResult
import io.github.neotheon3.orangebox.core.bluetooth.DongleTester
import io.github.neotheon3.orangebox.core.bluetooth.ObdDongleProvider
import io.github.neotheon3.orangebox.core.model.SelectedDongle
import io.github.neotheon3.orangebox.core.model.Vehicle
import io.github.neotheon3.orangebox.core.model.VehicleFuel
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.obd.ObdTransport
import io.github.neotheon3.orangebox.core.testing.FakeAppPreferencesRepository
import io.github.neotheon3.orangebox.core.testing.FakeVehicleRepository
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

internal class FakeDongleProvider(
    var list: List<DongleCandidate> = emptyList(),
    var denied: Boolean = false,
    var bluetooth: BluetoothAvailability = BluetoothAvailability.ON,
) : ObdDongleProvider {
    override val selected = MutableStateFlow<SelectedDongle?>(null)
    override fun bluetoothAvailability() = bluetooth
    override fun candidates(): List<DongleCandidate> =
        if (denied) throw ObdError.PermissionDenied("android.permission.BLUETOOTH_CONNECT") else list
    override suspend fun select(candidate: DongleCandidate?) { selected.value = candidate?.let { SelectedDongle(it.address, it.name) } }
    override suspend fun create(): ObdTransport = throw ObdError.NotPaired()
}

internal class FakeTester(var result: DongleTestResult) : DongleTester {
    var calls = 0
    override suspend fun testSelected(): DongleTestResult { calls++; return result }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `disclaimer gates the flow and finishing persists completion`() = runTest {
        val prefs = FakeAppPreferencesRepository()
        val vm = OnboardingViewModel(prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.next()
        assertEquals(OnboardingStep.DISCLAIMER, vm.state.value.step)
        vm.next() // not accepted yet
        assertEquals(OnboardingStep.DISCLAIMER, vm.state.value.step)
        vm.onDisclaimerChecked(true)
        vm.next()
        assertEquals(OnboardingStep.PERMISSIONS, vm.state.value.step)
        assertTrue(prefs.state.value.disclaimerAccepted)
        repeat(3) { vm.next() }
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
        assertTrue(vm.back())
        assertEquals(OnboardingStep.DONGLE, vm.state.value.step)
        var done = false
        vm.finish { done = true }
        assertTrue(done)
        assertTrue(prefs.state.value.onboardingComplete)
    }

    @Test fun `step survives process death through SavedStateHandle`() = runTest {
        val prefs = FakeAppPreferencesRepository()
        val saved = SavedStateHandle()
        val vm = OnboardingViewModel(prefs, saved)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.next()
        vm.onDisclaimerChecked(true)
        // Recreated after process death: only the saved state is handed back (DataStore wiped here).
        val restored = OnboardingViewModel(FakeAppPreferencesRepository(), SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.state.collect {} }
        assertEquals(OnboardingStep.DISCLAIMER, restored.state.value.step)
        assertTrue(restored.state.value.disclaimerChecked)
        assertFalse(restored.state.value.restoring)
    }

    @Test fun `cold start resumes at the step stored in DataStore`() = runTest {
        val prefs = FakeAppPreferencesRepository()
        val vm = OnboardingViewModel(prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.next(); vm.onDisclaimerChecked(true); vm.next(); vm.next()
        assertEquals(OnboardingStep.VEHICLE, vm.state.value.step)
        assertEquals("VEHICLE", prefs.state.value.onboardingStep)
        // Task swiped away: no saved state, same DataStore.
        val cold = OnboardingViewModel(prefs, SavedStateHandle())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cold.state.collect {} }
        assertEquals(OnboardingStep.VEHICLE, cold.state.value.step)
        cold.finish {}
        assertEquals(null, prefs.state.value.onboardingStep)
    }

    @Test fun `a stored step past the disclaimer needs the disclaimer accepted`() {
        assertEquals(OnboardingStep.DISCLAIMER, OnboardingViewModel.resolveStep("DONGLE", disclaimerAccepted = false))
        assertEquals(OnboardingStep.DONGLE, OnboardingViewModel.resolveStep("DONGLE", disclaimerAccepted = true))
        assertEquals(OnboardingStep.WELCOME, OnboardingViewModel.resolveStep("BOGUS", disclaimerAccepted = false))
        assertEquals(OnboardingStep.WELCOME, OnboardingViewModel.resolveStep(null, disclaimerAccepted = false))
    }

    @Test fun `asked permissions are remembered for the permanently-denied check`() = runTest {
        val prefs = FakeAppPreferencesRepository()
        OnboardingViewModel(prefs).markAsked(listOf("p1"))
        assertEquals(setOf("p1"), prefs.state.value.askedPermissions)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VehicleSetupViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `catalogue pick is saved and made active`() = runTest {
        val repo = FakeVehicleRepository()
        val vm = VehicleSetupViewModel(repo)
        vm.onFormChange(VehicleForm(maker = "Toyota", model = "Corolla 1.8", odometerKm = "42000"))
        vm.save()
        val active = repo.activeVehicle.first()!!
        assertEquals("Toyota Corolla 1.8", active.name)
        assertEquals(1798, active.displacementCc)
        assertEquals(42000, active.odometerKm)
        assertTrue(vm.state.value.saved)
    }

    @Test fun `manual entry validates name and ranges`() = runTest {
        val repo = FakeVehicleRepository()
        val vm = VehicleSetupViewModel(repo)
        vm.onFormChange(VehicleForm(source = VehicleSource.MANUAL, name = " ", displacementCc = "20"))
        vm.save()
        assertEquals(setOf(VehicleFormError.NAME, VehicleFormError.DISPLACEMENT), vm.state.value.errors)
        assertEquals(emptyList<Vehicle>(), repo.all.value)
        vm.onFormChange(VehicleForm(source = VehicleSource.MANUAL, name = "Van", fuel = VehicleFuel.DIESEL, displacementCc = "2500"))
        vm.save()
        assertEquals(VehicleFuel.DIESEL, repo.activeVehicle.first()?.fuel)
    }

    @Test fun `catalogue without a model is an error`() {
        val (v, errors) = VehicleFormValidator.validate(VehicleForm(maker = "Toyota"))
        assertEquals(null, v)
        assertEquals(setOf(VehicleFormError.MODEL), errors)
    }

    @Test fun `typed form survives process death`() = runTest {
        val repo = FakeVehicleRepository(listOf(Vehicle(id = 3, name = "Old car", fuel = VehicleFuel.LPG)), activeId = 3)
        val saved = SavedStateHandle()
        val vm = VehicleSetupViewModel(repo, saved)
        val typed = VehicleForm(source = VehicleSource.MANUAL, name = "New van", fuel = VehicleFuel.DIESEL, displacementCc = "1998.6", odometerKm = "12.5")
        vm.onFormChange(typed)
        val restored = VehicleSetupViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertEquals(typed, restored.state.value.form) // not overwritten by the stored "Old car"
    }

    @Test fun `decimal odometer and engine size are rounded, not multiplied by ten`() = runTest {
        val repo = FakeVehicleRepository()
        val vm = VehicleSetupViewModel(repo)
        vm.onFormChange(VehicleForm(source = VehicleSource.MANUAL, name = "Van", displacementCc = "1598.5", odometerKm = "12.5"))
        vm.save()
        assertEquals(13, repo.activeVehicle.first()?.odometerKm)
        assertEquals(1599, repo.activeVehicle.first()?.displacementCc)
    }

    @Test fun `number input keeps one decimal separator`() {
        assertEquals("12.5", NumberInput.sanitize("abc-12.5x"))
        assertEquals("45210.7", NumberInput.sanitize("45210,7"))
        assertEquals("1.23", NumberInput.sanitize("1.2.3"))
        assertEquals("1234567.89", NumberInput.sanitize("123456789.891"))
        assertEquals("", NumberInput.sanitize("km"))
        assertEquals(13, NumberInput.parseRounded("12.5"))
        assertEquals(45210, NumberInput.parseRounded("45210.4"))
        assertEquals(7, NumberInput.parseRounded("7."))
        assertEquals(0, NumberInput.parseRounded(".4"))
        assertEquals(null, NumberInput.parseRounded("."))
        assertEquals(null, NumberInput.parseRounded(""))
        // "1.6" typed as litres is 2 cc: rejected with the cc range message rather than silently accepted.
        val (_, errors) = VehicleFormValidator.validate(VehicleForm(source = VehicleSource.MANUAL, name = "Car", displacementCc = "1.6"))
        assertEquals(setOf(VehicleFormError.DISPLACEMENT), errors)
    }

    @Test fun `editing loads the active vehicle`() = runTest {
        val repo = FakeVehicleRepository(listOf(Vehicle(id = 3, name = "Old car", fuel = VehicleFuel.LPG)), activeId = 3)
        val vm = VehicleSetupViewModel(repo)
        assertEquals(VehicleSource.MANUAL, vm.state.value.form.source)
        assertEquals("Old car", vm.state.value.form.name)
        vm.save()
        assertEquals(1, repo.all.value.size) // updated in place
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DongleSetupViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val obd = DongleCandidate("00:11:22:33:44:55", "OBDII")

    @Test fun `missing permission is reported, then the list appears after a refresh`() = runTest {
        val provider = FakeDongleProvider(listOf(obd), denied = true)
        val vm = DongleSetupViewModel(provider, FakeTester(DongleTestResult.Failure(ObdError.NotPaired())), FakeAppPreferencesRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertTrue(vm.state.value.needsPermission)
        provider.denied = false
        vm.refresh()
        assertFalse(vm.state.value.needsPermission)
        assertEquals(listOf(obd), vm.state.value.candidates)
    }

    @Test fun `bluetooth availability is reported and refreshed`() = runTest {
        val provider = FakeDongleProvider(emptyList(), bluetooth = BluetoothAvailability.UNAVAILABLE)
        val vm = DongleSetupViewModel(provider, FakeTester(DongleTestResult.Failure(ObdError.NotPaired())), FakeAppPreferencesRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(BluetoothAvailability.UNAVAILABLE, vm.state.value.bluetooth)
        provider.bluetooth = BluetoothAvailability.OFF
        vm.refresh()
        assertEquals(BluetoothAvailability.OFF, vm.state.value.bluetooth)
    }

    @Test fun `select then test maps results`() = runTest {
        val provider = FakeDongleProvider(listOf(obd))
        val tester = FakeTester(DongleTestResult.Success(Elm327Info("ELM327 v1.5", ObdProtocol.entries.last(), 12.4)))
        val prefs = FakeAppPreferencesRepository()
        val vm = DongleSetupViewModel(provider, tester, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.select(obd)
        assertEquals(obd.address, vm.state.value.selectedAddress)
        vm.testConnection()
        assertTrue(vm.state.value.test is TestState.Passed)
        tester.result = DongleTestResult.Failure(ObdError.NotPaired())
        vm.testConnection()
        assertEquals(FailureKind.NOT_PAIRED, (vm.state.value.test as TestState.Failed).kind)
        tester.result = DongleTestResult.Failure(ObdError.Timeout())
        vm.testConnection()
        assertEquals(FailureKind.OTHER, (vm.state.value.test as TestState.Failed).kind)
        vm.setAutoStart(true)
        assertTrue(vm.state.value.autoStart)
        assertTrue(prefs.state.value.autoStartOnDongleConnect)
    }
}
