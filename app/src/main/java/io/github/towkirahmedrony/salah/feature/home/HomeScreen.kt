package io.github.towkirahmedrony.salah.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.model.DashboardCard
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.components.CalendarCard
import io.github.towkirahmedrony.salah.feature.home.components.CurrentPrayerHeroCard
import io.github.towkirahmedrony.salah.feature.home.components.DailyToolsSection
import io.github.towkirahmedrony.salah.feature.home.components.DashboardEditor
import io.github.towkirahmedrony.salah.feature.home.components.FailureBanner
import io.github.towkirahmedrony.salah.feature.home.components.ForbiddenTimesCard
import io.github.towkirahmedrony.salah.feature.home.components.HomeHeader
import io.github.towkirahmedrony.salah.feature.home.components.HomeSkeleton
import io.github.towkirahmedrony.salah.feature.home.components.NightPeriodsCard
import io.github.towkirahmedrony.salah.feature.home.components.QiblaHomeCard
import io.github.towkirahmedrony.salah.feature.home.components.RamadanHomeCard
import io.github.towkirahmedrony.salah.feature.home.components.SolarGeometryCard
import io.github.towkirahmedrony.salah.feature.home.components.TodayPrayerList
import org.koin.androidx.compose.koinViewModel

/**
 * The Home screen: the app's personalised dashboard.
 *
 * The screen is a list of independent cards. Which cards appear, and in what order, is the user's
 * own layout, read from the persisted dashboard configuration through the view model — so a hidden card
 * leaves no empty space, and a reordered card stays where it was put across restarts and process
 * recreation.
 *
 * Nothing here calculates anything. Every card binds to [HomeUiState.Ready], where each value has
 * already been resolved from the same repositories the corresponding screens use; the cards pick the
 * slice of that state they own and render it. Cards are keyed by their stable [DashboardCard.id], so
 * a data update rebuilding one card cannot reshuffle the list.
 *
 * **Editing is transient UI state.** [editing] only decides whether the management list is shown; it
 * is not persisted, and each change the management list makes is written through the view model at
 * once, the same immediate-update convention the rest of Salah's settings use.
 */
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }

    when (val state = uiState) {
        HomeUiState.Loading -> HomeSkeleton(modifier)
        is HomeUiState.Failed -> HomeFailure(
            reason = state.reason,
            onRetry = viewModel::onRetry,
            modifier = modifier,
        )

        is HomeUiState.Ready -> HomeContent(
            state = state,
            editing = editing,
            onToggleEditing = { editing = !editing },
            onNavigate = onNavigate,
            onRetry = viewModel::onRetry,
            onSetCardEnabled = viewModel::setCardEnabled,
            onMoveCard = viewModel::moveCard,
            onResetDashboard = viewModel::resetDashboard,
            modifier = modifier,
        )
    }
}

@Composable
private fun HomeFailure(
    reason: UnavailabilityReason,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.state_error_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(reason.messageRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/**
 * The composed dashboard.
 *
 * The header — the saved place, the reminder bell and the customize control — is always present: it
 * is the one thing every reader needs regardless of how they arranged their cards. The cards follow
 * in the saved order. In edit mode the management list replaces them, so a card's own controls
 * cannot be tapped by accident while the layout is being changed.
 */
@Composable
private fun HomeContent(
    state: HomeUiState.Ready,
    editing: Boolean,
    onToggleEditing: () -> Unit,
    onNavigate: (String) -> Unit,
    onRetry: () -> Unit,
    onSetCardEnabled: (DashboardCard, Boolean) -> Unit,
    onMoveCard: (DashboardCard, Int) -> Unit,
    onResetDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The Ramadan card is context-aware: outside Ramadan and away from it there is nothing honest to
    // show, so it is not rendered at all rather than as an empty card.
    val visibleCards = state.dashboard.enabled.filterNot { cardState ->
        cardState.card == DashboardCard.RAMADAN && !state.ramadan.visible
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "home-header") {
            HomeHeader(
                state = state,
                onOpenLocation = { onNavigate("location") },
                onOpenNotifications = { onNavigate("notifications") },
                onCustomize = onToggleEditing,
                isEditing = editing,
            )
        }

        state.failureDetail?.let { detail ->
            item(key = "failure") {
                FailureBanner(detail = detail, onRetry = onRetry)
            }
        }

        if (editing) {
            item(key = "dashboard-editor") {
                DashboardEditor(
                    state = state,
                    onToggleCard = onSetCardEnabled,
                    onMoveCard = onMoveCard,
                    onResetDashboard = onResetDashboard,
                    onDone = onToggleEditing,
                )
            }
        } else if (visibleCards.isEmpty()) {
            item(key = "dashboard-empty") {
                SalahUnavailableNote(text = stringResource(R.string.home_dashboard_all_hidden))
            }
        } else {
            visibleCards.forEach { cardState ->
                item(key = "dashboard-card-${cardState.card.id}") {
                    DashboardCardContent(
                        card = cardState.card,
                        state = state,
                        onNavigate = onNavigate,
                    )
                }
            }
        }
    }
}

/**
 * Renders the one card [card] names, from the state it owns.
 *
 * A `when` over the enum rather than a lambda held in it, so adding a card without giving it a
 * composable is a compile error rather than a blank space on the dashboard. Every branch reads only
 * the section it renders and formats nothing itself.
 */
@Composable
private fun DashboardCardContent(
    card: DashboardCard,
    state: HomeUiState.Ready,
    onNavigate: (String) -> Unit,
) {
    when (card) {
        DashboardCard.NEXT_PRAYER -> CurrentPrayerHeroCard(state = state)

        DashboardCard.TODAY_TIMETABLE -> TodayPrayerList(
            state = state,
            // The full timetable opens the prayer screen, which renders the same live period and the
            // day's complete timetable.
            onViewAll = { onNavigate("prayer") },
        )

        DashboardCard.FORBIDDEN_TIMES -> ForbiddenTimesCard(
            state = state,
            // The prohibited windows are anchored to the day's solar events, so details open the
            // prayer-day destination rather than Calendar.
            onViewDetails = { onNavigate("prayer") },
        )

        DashboardCard.CALENDAR -> CalendarCard(state = state)

        DashboardCard.RAMADAN -> RamadanHomeCard(
            section = state.ramadan,
            onOpen = { onNavigate("ramadan") },
        )

        DashboardCard.NIGHT_PERIODS -> NightPeriodsCard(state = state)

        DashboardCard.SOLAR_GEOMETRY -> SolarGeometryCard(state = state)

        DashboardCard.QIBLA -> QiblaHomeCard(
            section = state.qibla,
            onOpen = { onNavigate("qibla") },
        )

        DashboardCard.DAILY_TOOLS -> DailyToolsSection(onNavigate = onNavigate)
    }
}
