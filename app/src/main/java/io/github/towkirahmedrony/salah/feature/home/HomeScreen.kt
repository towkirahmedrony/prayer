package io.github.towkirahmedrony.salah.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.feature.home.components.*
import org.koin.androidx.compose.koinViewModel

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        HomeUiState.Loading -> HomeSkeleton(modifier)
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "top-bar") {
            HomeTopBar(onOpenSettings = { onNavigate("settings") })
        }

        item(key = "location-header") {
            LocationAndDateHeader(
                state = state,
                onOpenLocation = { onNavigate("location") },
                onOpenCalendar = { onNavigate("calendar") },
            )
        }

        if (state.failureDetail != null) {
            item(key = "failure") {
                FailureBanner(detail = state.failureDetail, onRetry = onRetry)
            }
        }

        item(key = "current-hero") {
            CurrentPrayerHeroCard(state = state.currentPrayer)
        }

        item(key = "prayer-times") {
            TodayPrayerTimesSection(
                rows = state.prayerRows,
                currentPrayer = state.currentPrayer.prayer,
                onViewAll = { onNavigate("calendar") },
            )
        }

        item(key = "forbidden-times") {
            ForbiddenTimesCard(
                state = state,
                onViewDetails = { onNavigate("calendar") },
            )
        }

        item(key = "daily-tools") {
            DailyToolsSection(onNavigate = onNavigate)
        }

        item(key = "spacer-bottom") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
