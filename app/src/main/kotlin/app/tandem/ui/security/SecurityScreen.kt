// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.designsystem.component.accessibleName
import app.tandem.designsystem.component.FingerprintText
import app.tandem.designsystem.component.LeadingTile
import app.tandem.designsystem.component.SectionHeader
import app.tandem.designsystem.component.TandemListRow
import app.tandem.designsystem.component.TandemTag
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.sample.SampleData
import app.tandem.state.ConnectionRoute

/** Route entry for the connection and security screen. */
@Composable
fun SecurityEntry(
    onBack: () -> Unit,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    SecurityScreen(
        state = state,
        onBack = onBack,
        onSelectRoute = viewModel::selectRoute,
        onLinksFromBrowser = viewModel::setLinksFromBrowser,
        onBrowserMedia = viewModel::setBrowserMedia,
    )
}

/** Connection and security: radio route list, TLS card with the fingerprint, browser extension switches. */
@Composable
fun SecurityScreen(
    state: SecurityUiState,
    onBack: () -> Unit,
    onSelectRoute: (String) -> Unit,
    onLinksFromBrowser: (Boolean) -> Unit,
    onBrowserMedia: (Boolean) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.security_title)) },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(
                    padding,
                ).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            SectionHeader(stringResource(R.string.security_reach_header))
            state.routes.forEach { route ->
                RouteRow(
                    route = route,
                    selected = route.id == state.selectedRouteId,
                    onSelect = { onSelectRoute(route.id) },
                )
            }

            SectionHeader(stringResource(R.string.security_encryption_header))
            EncryptionCard(state = state, modifier = Modifier.padding(horizontal = 16.dp))

            SectionHeader(stringResource(R.string.security_extension_header))
            TandemListRow(
                headline = stringResource(R.string.security_links_title),
                supporting = stringResource(R.string.security_links_body),
                leading = { LeadingTile(icon = Icons.Filled.Lock, contentDescription = null) },
                trailing = {
                    Switch(
                        checked = state.linksFromBrowser,
                        onCheckedChange = onLinksFromBrowser,
                        modifier = Modifier.accessibleName(stringResource(R.string.security_links_title)),
                    )
                },
            )
            TandemListRow(
                headline = stringResource(R.string.security_media_title),
                supporting = stringResource(R.string.security_media_body),
                leading = { LeadingTile(icon = TandemIcons.SmartDisplay, contentDescription = null) },
                trailing = {
                    Switch(
                        checked = state.browserMedia,
                        onCheckedChange = onBrowserMedia,
                        modifier = Modifier.accessibleName(stringResource(R.string.security_media_title)),
                    )
                },
            )
        }
    }
}

@Composable
private fun RouteRow(
    route: ConnectionRoute,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        headlineContent = { Text(route.name) },
        supportingContent = { Text(route.detail) },
        leadingContent = {
            RadioButton(selected = selected, onClick = null)
        },
        trailingContent = {
            Icon(
                if (route.viaVpn) Icons.Filled.Lock else TandemIcons.Wifi,
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun EncryptionCard(
    state: SecurityUiState,
    modifier: Modifier = Modifier,
) {
    val device = state.device
    val fingerprint = device?.certSha256?.chunked(24)?.joinToString("\n") ?: SampleData.FINGERPRINT
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    device?.tlsVersion?.let {
                        stringResource(R.string.security_tls_version, it)
                    } ?: stringResource(R.string.security_tls_default),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (device?.paired == true) {
                    TandemTag(
                        label = stringResource(R.string.security_verified),
                        icon = Icons.Filled.Check,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            FingerprintText(fingerprint)
            Text(
                stringResource(R.string.security_matches, device?.name ?: stringResource(R.string.home_no_device)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
