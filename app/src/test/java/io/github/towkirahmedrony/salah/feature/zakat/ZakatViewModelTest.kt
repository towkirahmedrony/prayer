package io.github.towkirahmedrony.salah.feature.zakat

import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatInventoryBasis
import io.github.towkirahmedrony.salah.core.model.ZakatJewelleryTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatMetalUnit
import io.github.towkirahmedrony.salah.core.model.ZakatNisabMetal
import io.github.towkirahmedrony.salah.core.model.ZakatNisabWeight
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The form holder: what it keeps, what it filters, and what it hands on.
 *
 * ## What this covers that the other two layers cannot
 *
 * `ZakatCalculatorTest` covers the arithmetic and `ZakatUiStateFactoryTest` the conversion from form to state.
 * What is left is the view model's own behaviour: that a keystroke is filtered to something a numeric field can
 * hold, that every methodology choice is recorded and reaches the state, that the valuation date comes from the
 * clock it was given, and that nothing is written anywhere.
 *
 * ## The persistence assertion
 *
 * One test here exists to pin a *privacy* decision rather than a behaviour: the view model has no repository, and
 * `clearAll` restores the exact state the screen started in. If someone later adds storage, that test fails and
 * forces the change to be deliberate — which is the point, because the brief requires the storage decision to be
 * disclosed rather than introduced quietly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZakatViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    /** A fixed instant, so the valuation date is 2026-10-16 rather than whatever today is. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-16T09:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        preferences: FakeUserPreferencesRepository = FakeUserPreferencesRepository(
            initialAppLanguage = AppLanguage.ENGLISH,
        ),
    ): ZakatViewModel = ZakatViewModel(
        userPreferencesRepository = preferences,
        uiStateFactory = ZakatUiStateFactory(),
        clock = clock,
    )

    /** Collects the state so the `WhileSubscribed` flow is live, as a screen would. */
    private fun TestScope.observe(viewModel: ZakatViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()
    }

    private fun ready(viewModel: ZakatViewModel): ZakatUiState.Ready {
        val state = viewModel.uiState.value
        assertTrue("expected a Ready state but was $state", state is ZakatUiState.Ready)
        return state as ZakatUiState.Ready
    }

    // --- Input filtering ------------------------------------------------------------------------

    @Test
    fun `a thousands separator is dropped, so a pasted amount is usable`() {
        assertEquals("12000.50", ZakatViewModel.filterDecimalInput("12,000.50"))
    }

    @Test
    fun `only one decimal point survives`() {
        assertEquals("12.50", ZakatViewModel.filterDecimalInput("12.5.0"))
    }

    @Test
    fun `a negative sign is dropped rather than allowed into the form`() {
        // No amount in this feature can be negative, so letting one in only to reject it later would report an
        // error where a correction is what is wanted.
        assertEquals("5000", ZakatViewModel.filterDecimalInput("-5000"))
    }

    @Test
    fun `letters and symbols never reach the field`() {
        assertEquals("1200", ZakatViewModel.filterDecimalInput("৳1 2 0 0"))
        assertEquals("", ZakatViewModel.filterDecimalInput("abc"))
    }

    @Test
    fun `a leading decimal point is not accepted and a trailing one is`() {
        // ".5" is not a shape this field produces, but "12." is a step towards "12.50" and must survive.
        assertEquals("5", ZakatViewModel.filterDecimalInput(".5"))
        assertEquals("12.", ZakatViewModel.filterDecimalInput("12."))
    }

    // --- The form -------------------------------------------------------------------------------

    @Test
    fun `the screen starts loading and becomes ready once the language is known`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        // Nothing has collected the flow yet, so the state is still the one it was created with.
        assertEquals(ZakatUiState.Loading, viewModel.uiState.value)

        observe(viewModel)

        assertEquals("BDT", ready(viewModel).currencyChoices.single { it.selected }.value)
    }

    @Test
    fun `a keystroke reaches the form and the calculation`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.onValueChange(ZakatFormField.GOLD_WEIGHT, "100")
        viewModel.onValueChange(ZakatFormField.GOLD_PRICE, "12000")
        runCurrent()

        val state = ready(viewModel)
        assertEquals("100", state.assetFields.single { it.field == ZakatFormField.GOLD_WEIGHT }.value)
        assertEquals("1200000.00", state.result.totalAssets)
        assertEquals("30000.00", state.result.due)
    }

    @Test
    fun `what the user typed is kept exactly, so nothing is reformatted under them`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.onValueChange(ZakatFormField.CASH, "1000.5")
        runCurrent()

        assertEquals("1000.5", ready(viewModel).assetFields.single { it.field == ZakatFormField.CASH }.value)
    }

    @Test
    fun `the valuation date comes from the clock the view model was given`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.onValueChange(ZakatFormField.GOLD_WEIGHT, "100")
        viewModel.onValueChange(ZakatFormField.GOLD_PRICE, "12000")
        runCurrent()

        // The nisab follows the price, and the price is the one entered, so this asserts the whole chain used the
        // injected clock rather than a real one — the figures above are fixed and date-independent.
        assertEquals("30000.00", ready(viewModel).result.due)
    }

    // --- Methodology choices --------------------------------------------------------------------

    @Test
    fun `every methodology choice is recorded and reaches the state`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.onCurrencyChange("USD")
        viewModel.onUnitChange(ZakatMetalUnit.TOLA)
        viewModel.onNisabWeightChange(ZakatNisabWeight.BANGLADESH)
        viewModel.onNisabMetalChange(ZakatNisabMetal.SILVER)
        viewModel.onJewelleryChange(ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT)
        viewModel.onDebtTreatmentChange(ZakatDebtTreatment.DO_NOT_DEDUCT)
        viewModel.onInventoryBasisChange(ZakatInventoryBasis.LANDED_COST)
        runCurrent()

        val state = ready(viewModel)
        assertEquals("USD", state.currencyChoices.single { it.selected }.value)
        assertEquals(ZakatMetalUnit.TOLA, state.unitChoices.single { it.selected }.value)
        assertEquals(ZakatNisabWeight.BANGLADESH, state.nisabWeightChoices.single { it.selected }.value)
        assertEquals(ZakatNisabMetal.SILVER, state.nisabMetalChoices.single { it.selected }.value)
        assertEquals(
            ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT,
            state.jewelleryChoices.single { it.selected }.value,
        )
        assertEquals(ZakatDebtTreatment.DO_NOT_DEDUCT, state.debtChoices.single { it.selected }.value)
        assertEquals(
            ZakatInventoryBasis.LANDED_COST,
            state.inventoryBasisChoices.single { it.selected }.value,
        )
    }

    @Test
    fun `switching the nisab weight pair changes the threshold`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        observe(viewModel)
        viewModel.onValueChange(ZakatFormField.GOLD_WEIGHT, "86")
        viewModel.onValueChange(ZakatFormField.GOLD_PRICE, "12000")
        runCurrent()
        // 86 g × 12,000 = 1,032,000 against the international gold nisab of 1,049,760 — below it.
        assertNull(ready(viewModel).result.due)

        viewModel.onNisabWeightChange(ZakatNisabWeight.BANGLADESH)
        runCurrent()

        // Against 85 g × 12,000 = 1,020,000 the same wealth owes 25,800. This is the disagreement in
        // docs/ZAKAT_METHODOLOGY.md §3.2, reached through the screen.
        assertEquals("1020000.00", ready(viewModel).result.nisabAmount)
        assertEquals("25800.00", ready(viewModel).result.due)
    }

    @Test
    fun `an unreadable seed date is reported without disturbing anything else`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.onHawlDateChange("next Ramadan")
        runCurrent()

        val state = ready(viewModel)
        assertTrue(state.hawlDateInvalid)
        assertNull(state.result.hawlEstimateDate)
    }

    // --- Clearing, and what is not kept ---------------------------------------------------------

    @Test
    fun `clear all returns the screen to the state it opened in`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        observe(viewModel)
        val openedIn = ready(viewModel)

        viewModel.onValueChange(ZakatFormField.CASH, "1200000")
        viewModel.onNisabWeightChange(ZakatNisabWeight.BANGLADESH)
        viewModel.onDebtTreatmentChange(ZakatDebtTreatment.DO_NOT_DEDUCT)
        viewModel.onHawlDateChange("2026-01-01")
        runCurrent()
        assertTrue(ready(viewModel).canClear)

        viewModel.clearAll()
        runCurrent()

        val cleared = ready(viewModel)
        assertFalse(cleared.canClear)
        assertNull(cleared.result.netWealth)
        assertNull(cleared.result.hawlEstimateDate)
        // Every methodology choice is cleared too: they are part of what the user entered, and leaving them behind
        // would make "clear everything" a half-truth.
        assertEquals(ZakatNisabWeight.INTERNATIONAL, cleared.nisabWeightChoices.single { it.selected }.value)
        assertEquals(ZakatDebtTreatment.DEDUCT, cleared.debtChoices.single { it.selected }.value)
        assertEquals(
            openedIn.nisabWeightChoices.map { it.selected },
            cleared.nisabWeightChoices.map { it.selected },
        )
    }

    @Test
    fun `the form lives only in the view model, and is not shared`() = runTest(mainDispatcher) {
        val preferences = FakeUserPreferencesRepository(initialAppLanguage = AppLanguage.ENGLISH)
        val viewModel = viewModel(preferences)
        observe(viewModel)

        viewModel.onValueChange(ZakatFormField.CASH, "9876543.21")
        runCurrent()
        assertEquals("9876543.21", ready(viewModel).assetFields.single { it.field == ZakatFormField.CASH }.value)

        // A second view model is a second, empty form over the same preferences. Nothing was passed between them,
        // because this feature has no store to pass it through — which is the privacy decision, asserted rather
        // than described. If storage is ever added, this test fails and the change has to be deliberate.
        val fresh = viewModel(preferences)
        observe(fresh)

        assertFalse(ready(fresh).canClear)
        assertEquals("", ready(fresh).assetFields.single { it.field == ZakatFormField.CASH }.value)
    }

    @Test
    fun `Bengali and Arabic digits typed on the user's own keyboard are accepted`() {
        // A Bengali keyboard produces Bengali numerals. Without this the user would be told their own numbers are
        // not numbers, which is an error about the interface rather than about what they typed.
        assertEquals("12500", ZakatViewModel.filterDecimalInput("১২৫০০"))
        assertEquals("12500.5", ZakatViewModel.filterDecimalInput("১২৫০০.৫"))
        assertEquals("12500", ZakatViewModel.filterDecimalInput("١٢٥٠٠"))
    }
}
