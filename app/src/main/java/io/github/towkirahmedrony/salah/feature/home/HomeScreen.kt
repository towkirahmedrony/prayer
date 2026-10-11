package io.github.towkirahmedrony.salah.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.feature.home.components.BrandHeader
import io.github.towkirahmedrony.salah.feature.home.components.CalendarCard
import io.github.towkirahmedrony.salah.feature.home.components.FailureBanner
import io.github.towkirahmedrony.salah.feature.home.components.LocationCard
import io.github.towkirahmedrony.salah.feature.home.components.NextPrayerCard
import io.github.towkirahmedrony.salah.feature.home.components.ProhibitedTimesCard
import io.github.towkirahmedrony.salah.feature.home.components.ProvenanceCard
import io.github.towkirahmedrony.salah.feature.home.components.ShortcutsCard
import io.github.towkirahmedrony.salah.feature.home.components.SolarEventsCard
import io.github.towkirahmedrony.salah.feature.home.components.TimetableCard
import org.koin.androidx.compose.koinViewModel

/**
 * The Salah Home screen.
 *
 * It renders [HomeUiState] and nothing else: no repository access, no date math and no
 * countdown logic live here. Every section with missing data explains itself.
 */
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        HomeUiState.Loading -> HomeLoading(modifier)

        is HomeUiState.Failed -> HomeFailure(
            detail = state.detail,
            onRetry = viewModel::onRetry,
            modifier = modifier,
        )

        is HomeUiState.Ready -> HomeContent(
            state = state,
            onNavigate = onNavigate,
            onRetry = viewModel::onRetry,
            modifier = modifier,
        )
    }
}

@Composable
private fun HomeLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.state_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeFailure(
    detail: String,
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
            text = detail,
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

@Composable
private fun HomeContent(
    state: HomeUiState.Ready,
    onNavigate: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "brand") {
            BrandHeader()
        }

        if (state.failureDetail != null) {
            item(key = "failure") {
                FailureBanner(detail = state.failureDetail, onRetry = onRetry)
            }
        }

        item(key = "dates") {
            CalendarCard(state = state)
        }

        item(key = "location") {
            LocationCard(
                location = state.location,
                onOpenSettings = { onNavigate("settings") },
            )
        }

        item(key = "next-prayer") {
            NextPrayerCard(state = state.nextPrayer)
        }

        item(key = "timetable") {
            TimetableCard(state = state)
        }

        item(key = "solar") {
            SolarEventsCard(state = state)
        }

        item(key = "prohibited") {
            ProhibitedTimesCard(state = state.prohibited)
        }

        item(key = "shortcuts") {
            ShortcutsCard(onNavigate = onNavigate)
        }

        item(key = "provenance") {
            ProvenanceCard(state = state.provenance)
        }

        item(key = "footer-spacer") {
            Spacer(modifier = Modifier.fillMaxWidth().height(8.dp))
        }
    }
}
