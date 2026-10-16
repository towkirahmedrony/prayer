package io.github.towkirahmedrony.salah.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.components.CurrentPrayerHeroCard
import io.github.towkirahmedrony.salah.feature.home.components.NightPeriodsCard
import io.github.towkirahmedrony.salah.feature.home.components.SolarGeometryCard
import io.github.towkirahmedrony.salah.feature.home.components.TimetableCard
import org.koin.androidx.compose.koinViewModel

@Composable
fun PrayerScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_prayer),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        when (val state = uiState) {
            HomeUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            is HomeUiState.Failed -> Text(
                text = stringResource(state.reason.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            is HomeUiState.Ready -> {
                CurrentPrayerHeroCard(state = state)
                Spacer(modifier = Modifier.height(4.dp))
                TimetableCard(state = state)
                Spacer(modifier = Modifier.height(4.dp))
                NightPeriodsCard(state = state)
                Spacer(modifier = Modifier.height(4.dp))
                SolarGeometryCard(state = state)
            }
        }
    }
}
