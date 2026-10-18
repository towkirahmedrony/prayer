package io.github.towkirahmedrony.salah.feature.zakat

import io.github.towkirahmedrony.salah.core.domain.metalprice.LoadPublishedReferencePrices
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFailure
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFreshness
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRepository
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPurity
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalType
import io.github.towkirahmedrony.salah.core.domain.metalprice.PublishedMetalPrice
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePriceResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePrices
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatInventoryBasis
import io.github.towkirahmedrony.salah.core.model.ZakatJewelleryTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatMetal
import io.github.towkirahmedrony.salah.core.model.ZakatMetalUnit
import io.github.towkirahmedrony.salah.core.model.ZakatNisabMetal
import io.github.towkirahmedrony.salah.core.model.ZakatNisabWeight
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
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
        priceRepository: MetalPriceRepository = FakeMetalPriceRepository(),
    ): ZakatViewModel = ZakatViewModel(
        userPreferencesRepository = preferences,
        uiStateFactory = ZakatUiStateFactory(),
        loadReferencePrices = LoadPublishedReferencePrices(priceRepository),
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
    fun `a leading decimal point is kept, with a zero in front of it`() {
        // ".5" is half a unit. Dropping the point would turn it into five units — a wrong figure that nothing
        // later in the flow could catch, because "5" is a perfectly valid amount, and the error would be the
        // user's own figure changed silently under them.
        assertEquals("0.5", ZakatViewModel.filterDecimalInput(".5"))
        assertEquals("0.", ZakatViewModel.filterDecimalInput("."))
        // A point with nothing after it is a step towards "0.50" and must survive too.
        assertEquals("12.", ZakatViewModel.filterDecimalInput("12."))
        assertEquals("12.50", ZakatViewModel.filterDecimalInput("12.5.0"))
        assertEquals("0.50", ZakatViewModel.filterDecimalInput(".5.0"))
        // The same rule in the user's own numerals.
        assertEquals("0.5", ZakatViewModel.filterDecimalInput("০.৫"))
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

    // --- The Hawl date's digits -----------------------------------------------------------------

    @Test
    fun `normalizeDateDigits rewrites the digits it recognises and nothing else`() {
        assertEquals("2026-01-01", ZakatViewModel.normalizeDateDigits("২০২৬-০১-০১"))
        assertEquals("2026-01-01", ZakatViewModel.normalizeDateDigits("٢٠٢٦-٠١-٠١"))
        // A date is structured text: separators survive, and text that carries no digits is left alone.
        assertEquals("next Ramadan", ZakatViewModel.normalizeDateDigits("next Ramadan"))
    }

    @Test
    fun `a Bengali seed date is normalised and parsed`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.onHawlDateChange("২০২৬-০১-০১")
        runCurrent()

        val state = ready(viewModel)
        assertEquals("2026-01-01", state.hawlDateText)
        assertFalse(state.hawlDateInvalid)
        assertNotNull(state.result.hawlEstimateDate)
    }

    @Test
    fun `an Arabic-Indic seed date is normalised and parsed`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.onHawlDateChange("٢٠٢٦-٠١-٠١")
        runCurrent()

        assertEquals("2026-01-01", ready(viewModel).hawlDateText)
    }

    @Test
    fun `a partially typed seed date is not reported as an error`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.onHawlDateChange("2026")
        runCurrent()

        val state = ready(viewModel)
        assertFalse(state.hawlDateInvalid)
        assertTrue(state.canClear)
    }

    // --- Published reference prices --------------------------------------------------------------

    @Test
    fun `the published rates are read once when the screen is created`() = runTest(mainDispatcher) {
        val repository = FakeMetalPriceRepository()
        repository.result = prices(gold = "19550")

        viewModel(priceRepository = repository)
        runCurrent()

        // One cache read and one refresh for the screen's lifetime so far: nothing reacts to a keystroke or a
        // recomposition.
        assertEquals(1, repository.cachedCalls)
        assertEquals(1, repository.calls)
    }

    @Test
    fun `a saved price is shown before the refresh has finished`() = runTest(mainDispatcher) {
        val repository = FakeMetalPriceRepository()
        repository.cachedResult = prices(
            gold = "18000",
            freshness = MetalPriceFreshness.CACHED,
            cachedAt = 1_700_000_000_000L,
        )
        repository.result = prices(gold = "19550")
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate

        val viewModel = viewModel(priceRepository = repository)
        observe(viewModel)
        runCurrent()

        // The request is still in flight, and the stored rate is already on screen and usable.
        var state = ready(viewModel)
        assertEquals("18000", state.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        assertEquals(
            ZakatReferencePriceStatus.CACHED,
            state.referencePrices.single { it.metal == ZakatMetal.GOLD }.status,
        )
        assertTrue(state.refreshingReferencePrices)

        gate.complete(Unit)
        runCurrent()

        state = ready(viewModel)
        assertEquals("19550", state.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        assertEquals(
            ZakatReferencePriceStatus.AVAILABLE,
            state.referencePrices.single { it.metal == ZakatMetal.GOLD }.status,
        )
    }

    @Test
    fun `the calculator works offline from the last saved price`() = runTest(mainDispatcher) {
        val repository = FakeMetalPriceRepository()
        // What the repository returns when every request failed but a validated cache survived.
        repository.cachedResult = prices(
            gold = "19550",
            freshness = MetalPriceFreshness.CACHED,
            cachedAt = 1_700_000_000_000L,
        )
        repository.result = repository.cachedResult!!.copy(refreshFailure = MetalPriceFailure.CONNECTION)
        val viewModel = viewModel(priceRepository = repository)
        observe(viewModel)

        viewModel.onValueChange(ZakatFormField.GOLD_WEIGHT, "87.5")
        runCurrent()

        val state = ready(viewModel)
        assertEquals("19550", state.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        assertEquals("1710625.00", state.result.totalAssets)
        assertEquals(
            ZakatReferencePriceStatus.CACHED,
            state.referencePrices.single { it.metal == ZakatMetal.GOLD }.status,
        )
        assertNotNull(state.referencePrices.single { it.metal == ZakatMetal.GOLD }.messageRes)
    }

    @Test
    fun `an available published rate fills a price field the user has left alone`() = runTest(mainDispatcher) {
        val repository = FakeMetalPriceRepository()
        repository.result = prices(gold = "19550")
        val viewModel = viewModel(priceRepository = repository)
        observe(viewModel)

        viewModel.onValueChange(ZakatFormField.GOLD_WEIGHT, "87.5")
        runCurrent()

        val state = ready(viewModel)
        assertEquals("19550", state.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        // 87.5 g × 19,550 = 1,710,625.
        assertEquals("1710625.00", state.result.totalAssets)
    }

    @Test
    fun `refreshing never overwrites a price the user edited`() = runTest(mainDispatcher) {
        val repository = FakeMetalPriceRepository()
        repository.result = prices(gold = "19550")
        val viewModel = viewModel(priceRepository = repository)
        observe(viewModel)

        viewModel.onValueChange(ZakatFormField.GOLD_PRICE, "20000")
        runCurrent()
        assertEquals("20000", ready(viewModel).assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)

        // The service now has a newer rate, and the user asks for it.
        repository.result = prices(gold = "21000")
        viewModel.refreshReferencePrices()
        runCurrent()

        val state = ready(viewModel)
        assertEquals("20000", state.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        assertEquals(2, repository.calls)
        assertTrue(state.referencePrices.single { it.metal == ZakatMetal.GOLD }.manuallyEdited)
    }

    @Test
    fun `restoring the published reference price puts it back`() = runTest(mainDispatcher) {
        val repository = FakeMetalPriceRepository()
        repository.result = prices(gold = "19550")
        val viewModel = viewModel(priceRepository = repository)
        observe(viewModel)

        viewModel.onValueChange(ZakatFormField.GOLD_PRICE, "20000")
        runCurrent()
        viewModel.onRestoreReferencePrice(ZakatFormField.GOLD_PRICE)
        runCurrent()

        assertEquals("19550", ready(viewModel).assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
    }

    @Test
    fun `a second read is not started while one is in flight`() = runTest(mainDispatcher) {
        val repository = FakeMetalPriceRepository()
        val viewModel = viewModel(priceRepository = repository)
        observe(viewModel)

        // The opening read has completed. Two refreshes asked for back to back produce exactly one more call.
        viewModel.refreshReferencePrices()
        viewModel.refreshReferencePrices()
        runCurrent()

        assertEquals(2, repository.calls)
    }

    @Test
    fun `a failed retrieval leaves the calculator working from a typed price`() = runTest(mainDispatcher) {
        val repository = FakeMetalPriceRepository()
        repository.result = ReferencePrices.failed(MetalPriceFailure.NETWORK)
        val viewModel = viewModel(priceRepository = repository)
        observe(viewModel)

        viewModel.onValueChange(ZakatFormField.GOLD_WEIGHT, "87.5")
        viewModel.onValueChange(ZakatFormField.GOLD_PRICE, "19550")
        runCurrent()

        val state = ready(viewModel)
        assertEquals("1710625.00", state.result.totalAssets)
        assertEquals(
            ZakatReferencePriceStatus.FAILED,
            state.referencePrices.single { it.metal == ZakatMetal.GOLD }.status,
        )
    }

    @Test
    fun `clearing also drops the user's price edits, so the published rate applies again`() = runTest(mainDispatcher) {
        val repository = FakeMetalPriceRepository()
        repository.result = prices(gold = "19550")
        val viewModel = viewModel(priceRepository = repository)
        observe(viewModel)

        viewModel.onValueChange(ZakatFormField.GOLD_PRICE, "20000")
        runCurrent()
        viewModel.clearAll()
        runCurrent()

        assertEquals("19550", ready(viewModel).assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        assertEquals(1, repository.calls)
    }
}

/**
 * A price repository that answers from memory and counts its calls.
 *
 * Offline and deterministic by construction: no test in this file reaches a network, and the rates it returns
 * are labelled fixtures rather than presented as real Bangladesh market prices.
 */
private class FakeMetalPriceRepository : MetalPriceRepository {

    var calls: Int = 0
        private set

    var cachedCalls: Int = 0
        private set

    var cachedResult: ReferencePrices? = null

    var result: ReferencePrices = ReferencePrices.notPublished()

    /** When set, a refresh waits on it, so the cache-first state can be observed mid-flight. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun cachedReferencePrices(): ReferencePrices? {
        cachedCalls += 1
        return cachedResult
    }

    override suspend fun publishedReferencePrices(): ReferencePrices {
        calls += 1
        gate?.await()
        return result
    }
}

/** A reading with a published fixture rate for whichever metals are named. */
private fun prices(
    gold: String? = null,
    silver: String? = null,
    freshness: MetalPriceFreshness = MetalPriceFreshness.CURRENT,
    cachedAt: Long? = null,
): ReferencePrices = ReferencePrices(
    gold = gold
        ?.let { price -> availablePrice(MetalType.GOLD, price, freshness, cachedAt) }
        ?: ReferencePriceResult.NotPublished,
    silver = silver
        ?.let { price -> availablePrice(MetalType.SILVER, price, freshness, cachedAt) }
        ?: ReferencePriceResult.NotPublished,
)

private fun availablePrice(
    metal: MetalType,
    price: String,
    freshness: MetalPriceFreshness,
    cachedAt: Long?,
): ReferencePriceResult =
    ReferencePriceResult.Available(
        PublishedMetalPrice(
            metal = metal,
            purity = MetalPurity.K22,
            pricePerGram = BigDecimal(price),
            currency = "BDT",
            source = "Salah test fixture",
        ),
        freshness = freshness,
        cachedAtEpochMillis = cachedAt,
    )
