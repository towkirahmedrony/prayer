package io.github.towkirahmedrony.salah.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahStyle
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.components.FailureBanner
import io.github.towkirahmedrony.salah.feature.home.components.NaflTimetableSection
import io.github.towkirahmedrony.salah.feature.home.components.NightTimetableSection
import io.github.towkirahmedrony.salah.feature.home.components.PrayerTimesList
import io.github.towkirahmedrony.salah.feature.home.components.PrayerTimetableHeader
import io.github.towkirahmedrony.salah.feature.home.components.ProhibitedTimetableSection
import io.github.towkirahmedrony.salah.feature.home.components.SawmTimetableSection
import io.github.towkirahmedrony.salah.feature.home.components.SunTimetableSection
import org.koin.androidx.compose.koinViewModel

/**
 * The Prayer screen: today's timetable, read as a single calm column.
 *
 * The page is deliberately not a stack of cards. The five prayers lead, the open period is the only
 * tinted row, and the secondary readings — Nafl, prohibited intervals, Sawm, the sun and the night —
 * follow as plain sections. Every value comes from [HomeUiState.Ready], already resolved; nothing here
 * calculates a time, and no note is repeated once its section is available.
 */
@Composable
fun PrayerScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        HomeUiState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is HomeUiState.Failed -> Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(state.reason.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        is HomeUiState.Ready -> PrayerTimetableContent(
            state = state,
            onRetry = viewModel::onRetry,
            modifier = modifier,
        )
    }
}

@Composable
private fun PrayerTimetableContent(
    state: HomeUiState.Ready,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalSalahStyle.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = layout.contentHorizontalPadding,
                end = layout.contentHorizontalPadding,
                top = 20.dp,
                bottom = 28.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(layout.sectionSpacing),
    ) {
        PrayerTimetableHeader(state = state)

        state.failureDetail?.let { detail ->
            FailureBanner(detail = detail, onRetry = onRetry)
        }

        PrayerTimesList(state = state)

        // Without a schedule every remaining section would only repeat the same reason. The timetable
        // note above already states it, so the page stops there instead of stacking empty sections.
        if (state.timetableNote == null) {
            NaflTimetableSection(state = state)
            ProhibitedTimetableSection(section = state.prohibited)
            SawmTimetableSection(state = state)
            SunTimetableSection(state = state)
            NightTimetableSection(state = state)
        }
    }
}
