package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.discord.DiscordArtwork
import com.nuvio.app.features.discord.DiscordCardHeader
import com.nuvio.app.features.discord.DiscordConnectionState
import com.nuvio.app.features.discord.DiscordLoginDialog
import com.nuvio.app.features.discord.DiscordMemberListText
import com.nuvio.app.features.discord.DiscordPresenceRepository
import com.nuvio.app.features.discord.DiscordPresenceSettings
import com.nuvio.app.features.discord.DiscordPresenceUiState
import com.nuvio.app.features.discord.DiscordProgress
import com.nuvio.app.features.discord.DiscordQualityLine
import com.nuvio.app.features.discord.DiscordStatus
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.discordPresenceSettingsContent(isTablet: Boolean) {
    item {
        DiscordPresenceSettingsBody(isTablet = isTablet)
    }
}

@Composable
private fun DiscordPresenceSettingsBody(isTablet: Boolean) {
    LaunchedEffect(Unit) { DiscordPresenceRepository.ensureLoaded() }
    val uiState by DiscordPresenceRepository.uiState.collectAsState()
    val account = uiState.account

    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 24.dp else 20.dp)) {
        when {
            !uiState.isSupported -> SettingsSection(title = stringResource(Res.string.compose_settings_page_discord), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    BodyText(stringResource(Res.string.discord_unsupported), isTablet)
                }
            }
            account == null -> ConnectCard(uiState = uiState, isTablet = isTablet)
            else -> {
                AccountCard(uiState = uiState, isTablet = isTablet)
                PresenceSettings(settings = uiState.settings, isTablet = isTablet)
                OutlinedButton(
                    onClick = DiscordPresenceRepository::disconnect,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(Res.string.discord_disconnect), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun BodyText(text: String, isTablet: Boolean, color: Color = MaterialTheme.nuvio.colors.textMuted) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 16.dp else 14.dp),
    )
}

@Composable
private fun ConnectCard(uiState: DiscordPresenceUiState, isTablet: Boolean) {
    var showLogin by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SettingsSection(title = stringResource(Res.string.discord_connect_title), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            Column(
                modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 18.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.discord_connect_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.discord_connect_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { showLogin = true },
                    enabled = !uiState.isSigningIn,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = DiscordBlurple, contentColor = Color.White),
                ) {
                    Text(
                        stringResource(if (uiState.isSigningIn) Res.string.discord_connecting_button else Res.string.discord_connect_button),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                uiState.errorMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showLogin) {
        DiscordLoginDialog(
            onToken = { token ->
                showLogin = false
                scope.launch { DiscordPresenceRepository.signIn(token) }
            },
            onDismiss = {
                showLogin = false
                DiscordPresenceRepository.signInCancelled()
            },
        )
    }
}

private val DiscordBlurple = Color(0xFF5865F2)

@Composable
private fun AccountCard(uiState: DiscordPresenceUiState, isTablet: Boolean) {
    val account = uiState.account ?: return
    val (statusText, statusColor) = when (uiState.connection) {
        DiscordConnectionState.CONNECTED -> stringResource(Res.string.discord_status_connected) to Color(0xFF23A55A)
        DiscordConnectionState.CONNECTING -> stringResource(Res.string.discord_status_connecting) to Color(0xFFF0B232)
        DiscordConnectionState.ERROR -> stringResource(Res.string.discord_status_error) to MaterialTheme.colorScheme.error
        DiscordConnectionState.DISCONNECTED -> stringResource(Res.string.discord_status_idle) to MaterialTheme.nuvio.colors.textMuted
    }
    SettingsGroup(isTablet = isTablet) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(if (isTablet) 20.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                AsyncImage(
                    model = account.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(CircleShape),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                Text(
                    text = "@${account.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
                Text(text = statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    title: StringResource,
    description: StringResource,
    isTablet: Boolean,
    options: List<Pair<T, StringResource>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    SettingsChipRow(
        title = stringResource(title),
        description = stringResource(description),
        isTablet = isTablet,
    ) {
        options.forEach { (value, label) ->
            Chip(label = stringResource(label), selected = value == selected, onClick = { onSelect(value) })
        }
    }
}

private fun update(transform: (DiscordPresenceSettings) -> DiscordPresenceSettings) =
    DiscordPresenceRepository.updateSettings(transform)

@Composable
private fun PresenceSettings(settings: DiscordPresenceSettings, isTablet: Boolean) {
    val enabled = settings.enabled

    SettingsSection(title = stringResource(Res.string.discord_section_rich_presence), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_enabled),
                checked = settings.enabled,
                isTablet = isTablet,
                onCheckedChange = { value -> update { it.copy(enabled = value) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_title_line),
                description = stringResource(Res.string.discord_title_line_description),
                checked = settings.showTitleLine,
                enabled = enabled,
                isTablet = isTablet,
                onCheckedChange = { value -> update { it.copy(showTitleLine = value) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_second_line),
                description = stringResource(Res.string.discord_second_line_description),
                checked = settings.showSecondLine,
                enabled = enabled,
                isTablet = isTablet,
                onCheckedChange = { value -> update { it.copy(showSecondLine = value) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            ChoiceRow(
                title = Res.string.discord_quality_line,
                description = Res.string.discord_quality_line_description,
                isTablet = isTablet,
                options = listOf(
                    DiscordQualityLine.ALWAYS to Res.string.discord_quality_always,
                    DiscordQualityLine.PREMIUM to Res.string.discord_quality_premium,
                    DiscordQualityLine.NEVER to Res.string.discord_quality_never,
                ),
                selected = settings.qualityLine,
                onSelect = { value -> update { it.copy(qualityLine = value) } },
            )
        }
    }

    SettingsSection(title = stringResource(Res.string.discord_section_artwork), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            ChoiceRow(
                title = Res.string.discord_artwork,
                description = Res.string.discord_artwork_description,
                isTablet = isTablet,
                options = listOf(
                    DiscordArtwork.BETTER_POSTERS to Res.string.discord_artwork_betterposters,
                    DiscordArtwork.POSTER to Res.string.discord_artwork_poster,
                    DiscordArtwork.BACKDROP to Res.string.discord_artwork_backdrop,
                ),
                selected = settings.artwork,
                onSelect = { value -> update { it.copy(artwork = value) } },
            )
        }
    }

    SettingsSection(title = stringResource(Res.string.discord_section_presence), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            ChoiceRow(
                title = Res.string.discord_status,
                description = Res.string.discord_status_description,
                isTablet = isTablet,
                options = listOf(
                    DiscordStatus.ONLINE to Res.string.discord_status_online,
                    DiscordStatus.IDLE to Res.string.discord_status_idle_choice,
                    DiscordStatus.DND to Res.string.discord_status_dnd,
                ),
                selected = settings.status,
                onSelect = { value -> update { it.copy(status = value) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            ChoiceRow(
                title = Res.string.discord_header,
                description = Res.string.discord_header_description,
                isTablet = isTablet,
                options = listOf(
                    DiscordCardHeader.APP to Res.string.discord_header_app,
                    DiscordCardHeader.TITLE to Res.string.discord_header_title,
                ),
                selected = settings.header,
                onSelect = { value -> update { it.copy(header = value) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            ChoiceRow(
                title = Res.string.discord_member_list,
                description = Res.string.discord_member_list_description,
                isTablet = isTablet,
                options = listOf(
                    DiscordMemberListText.NAME to Res.string.discord_member_list_name,
                    DiscordMemberListText.DETAILS to Res.string.discord_member_list_details,
                    DiscordMemberListText.STATE to Res.string.discord_member_list_state,
                ),
                selected = settings.memberListText,
                onSelect = { value -> update { it.copy(memberListText = value) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            ChoiceRow(
                title = Res.string.discord_progress,
                description = Res.string.discord_progress_description,
                isTablet = isTablet,
                options = listOf(
                    DiscordProgress.BAR to Res.string.discord_progress_bar,
                    DiscordProgress.ELAPSED to Res.string.discord_progress_elapsed,
                    DiscordProgress.NONE to Res.string.discord_progress_none,
                ),
                selected = settings.progress,
                onSelect = { value -> update { it.copy(progress = value) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            ChoiceRow(
                title = Res.string.discord_paused,
                description = Res.string.discord_paused_description,
                isTablet = isTablet,
                options = listOf(
                    0 to Res.string.discord_paused_hide,
                    5 to Res.string.discord_paused_5,
                    15 to Res.string.discord_paused_15,
                    60 to Res.string.discord_paused_60,
                    -1 to Res.string.discord_paused_forever,
                ),
                selected = settings.pauseMinutes,
                onSelect = { value -> update { it.copy(pauseMinutes = value) } },
            )
        }
    }

    SettingsSection(title = stringResource(Res.string.discord_section_buttons), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_bitrate_button),
                description = stringResource(Res.string.discord_bitrate_button_description),
                checked = settings.bitrateButton,
                enabled = enabled,
                isTablet = isTablet,
                onCheckedChange = { value -> update { it.copy(bitrateButton = value) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.discord_imdb_button),
                description = stringResource(Res.string.discord_imdb_button_description),
                checked = settings.imdbButton,
                enabled = enabled,
                isTablet = isTablet,
                onCheckedChange = { value -> update { it.copy(imdbButton = value) } },
            )
        }
        Text(
            text = stringResource(Res.string.discord_buttons_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }

    SettingsSection(title = stringResource(Res.string.discord_section_preview), isTablet = isTablet) {
        PresencePreview(settings = settings, isTablet = isTablet)
    }
}

/** A Discord-style card using sample data, so changes are visible without starting playback. */
@Composable
private fun PresencePreview(settings: DiscordPresenceSettings, isTablet: Boolean) {
    val title = "Game of Thrones"
    val header = if (settings.header == DiscordCardHeader.TITLE) title else "Nuvio"
    val lines = buildList {
        if (settings.showTitleLine && settings.header != DiscordCardHeader.TITLE) add(title)
        if (settings.showSecondLine) add("S01E01 · Winter Is Coming")
        if (settings.qualityLine != DiscordQualityLine.NEVER) add("4K · Dolby Vision · Dolby Atmos 7.1")
    }.take(2)
    val buttons = buildList {
        if (settings.bitrateButton) add("Bitrate: 61 Mbps | Size: 29 GB")
        if (settings.imdbButton) add("View on IMDb")
    }
    val posterUrl = when (settings.artwork) {
        DiscordArtwork.BETTER_POSTERS -> "https://btttr.cc/poster-r/imdb/poster-default/tt0944947.jpg"
        DiscordArtwork.POSTER -> "https://image.tmdb.org/t/p/w500/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg"
        DiscordArtwork.BACKDROP -> "https://image.tmdb.org/t/p/w500/zZqpAXxVSBtxV9qPBcscfXBcL2w.jpg"
    }

    SettingsGroup(isTablet = isTablet) {
        Column(
            modifier = Modifier.padding(if (isTablet) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.discord_preview_watching, header).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.nuvio.colors.textPrimary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    lines.forEachIndexed { index, line ->
                        Text(
                            text = line,
                            style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == 0) MaterialTheme.nuvio.colors.textPrimary else MaterialTheme.nuvio.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (settings.progress == DiscordProgress.BAR) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { 0.42f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("26:12", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
                            Text("1:01:42", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
                        }
                    } else if (settings.progress == DiscordProgress.ELAPSED) {
                        Text("26:12 elapsed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted)
                    }
                }
            }
            buttons.forEach { label ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.nuvio.colors.textPrimary.copy(alpha = 0.08f))
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
