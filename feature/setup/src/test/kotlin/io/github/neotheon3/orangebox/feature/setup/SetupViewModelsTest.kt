package io.github.neotheon3.orangebox.feature.setup

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
) : ObdDongleProvider {
    override val selected = MutableStateFlow<SelectedDongle?>(null)
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
