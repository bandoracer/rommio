package io.github.mattsays.rommnative.ui.screen.platform

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformScreen(
    container: AppContainer,
    platformId: Int,
    platformName: String,
    onBack: () -> Unit,
    onRomSelected: (RomDto) -> Unit,
) {
    val viewModel: PlatformViewModel = viewModel(
        key = "platform-$platformId",
        factory = viewModelFactory {
            initializer { PlatformViewModel(container.repository, platformId) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(java.net.URLDecoder.decode(platformName, Charsets.UTF_8.name())) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.roms, key = { it.id }) { rom ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onRomSelected(rom) }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(rom.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("${rom.files.size} files", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            state.errorMessage?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
