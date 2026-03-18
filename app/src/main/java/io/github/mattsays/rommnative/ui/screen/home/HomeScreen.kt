package io.github.mattsays.rommnative.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.model.PlatformDto
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onPlatformSelected: (PlatformDto) -> Unit,
    onRomSelected: (RomDto) -> Unit,
    onLogout: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.repository) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                container.repository.logout()
                                onLogout()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Logout")
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(horizontal = 24.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text("Platforms", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                items(state.platforms, key = { it.id }) { platform ->
                    PlatformCard(platform = platform, onClick = { onPlatformSelected(platform) })
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Recently added", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                items(state.recentRoms, key = { it.id }) { rom ->
                    RomCard(rom = rom, onClick = { onRomSelected(rom) })
                }
                state.errorMessage?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformCard(platform: PlatformDto, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AsyncImage(model = platform.urlLogo, contentDescription = null, modifier = Modifier.height(44.dp))
            Column {
                Text(platform.name, style = MaterialTheme.typography.titleMedium)
                Text("${platform.romCount} ROMs", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RomCard(rom: RomDto, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AsyncImage(model = rom.urlCover, contentDescription = null, modifier = Modifier.height(72.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(rom.displayName, style = MaterialTheme.typography.titleMedium)
                Text(rom.platformName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${rom.files.size} file variant(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
