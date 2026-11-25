package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BoundKey
import com.example.ButtonConfig
import com.example.ButtonConfigStore
import com.example.ControllerProfile
import com.example.KeyCatalog
import com.example.KeyCategory
import com.example.KeyInputType
import com.example.R
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.PrecisionAmber
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryCobalt
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlSettingsCard(
    activeProfile: ControllerProfile,
    buttonConfigs: List<ButtonConfig>,
    isOverlayRunning: Boolean,
    onSelectProfile: (ControllerProfile) -> Unit,
    onAddExtraButton: () -> Unit,
    onEditButton: (ButtonConfig) -> Unit
) {
    val extraCount = buttonConfigs.count { it.isExtra }
    val comboCount = buttonConfigs.count { it.boundKeys.size > 1 }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.section_control_settings).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Filled.Gamepad,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.control_settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Profile Switcher (Xbox / PS4 / PC) synced with in-overlay mini toolbar
            Text(
                text = stringResource(R.string.profile_selector_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControllerProfile.entries.forEach { profile ->
                    val selected = profile == activeProfile
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectProfile(profile) },
                        label = {
                            Text(
                                text = profile.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("profile_chip_${profile.id}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                            selectedLabelColor = ElectricCyan
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                            selectedBorderColor = ElectricCyan
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryBadge(
                    label = stringResource(R.string.control_settings_extra_count, extraCount),
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
                SummaryBadge(
                    label = stringResource(R.string.control_settings_combo_count, comboCount),
                    accent = PrecisionAmber,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onAddExtraButton,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("add_extra_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_add_extra_button),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (buttonConfigs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = if (isOverlayRunning) {
                        stringResource(R.string.control_settings_tap_hint_active)
                    } else {
                        stringResource(R.string.control_settings_tap_hint)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    buttonConfigs.forEach { cfg ->
                        ButtonMappingChip(
                            config = cfg,
                            profile = activeProfile,
                            onClick = { onEditButton(cfg) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryBadge(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ButtonMappingChip(
    config: ButtonConfig,
    profile: ControllerProfile,
    onClick: () -> Unit
) {
    val accent = when {
        config.isExtra -> PrecisionAmber
        config.boundKeys.size > 1 -> ElectricCyan
        config.boundKeys.any { it.inputType == KeyInputType.KEYBOARD } -> TelemetryCobalt
        config.boundKeys.any { it.inputType == KeyInputType.MOUSE } -> Color(0xFFC084FC)
        else -> SignalEmerald
    }
    val comboSummary = KeyCatalog.formatComboSummary(config.boundKeys, profile)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("button_chip_${config.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = config.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "→ $comboSummary",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeButtonConfigDialog(
    config: ButtonConfig,
    activeProfile: ControllerProfile,
    onDismiss: () -> Unit,
    onSave: (ButtonConfig) -> Unit,
    onDelete: (String) -> Unit
) {
    var labelText by remember(config.id) { mutableStateOf(config.label) }
    var sizeScale by remember(config.id) {
        mutableFloatStateOf(
            config.sizeScale.coerceIn(
                ButtonConfigStore.MIN_BUTTON_SIZE_SCALE,
                ButtonConfigStore.MAX_BUTTON_SIZE_SCALE
            )
        )
    }
    var searchQuery by remember(config.id) { mutableStateOf("") }
    var selectedCategory by remember(config.id) { mutableStateOf(KeyCategory.ALL) }
    val selectedKeys = remember(config.id) {
        mutableStateListOf<BoundKey>().apply {
            addAll(config.boundKeys.take(ButtonConfigStore.MAX_COMBO_KEYS))
            if (isEmpty()) add(KeyCatalog.XBOX_KEYS.first().toBoundKey())
        }
    }
    var limitWarning by remember { mutableStateOf(false) }

    val comboPreview = KeyCatalog.formatComboSummary(selectedKeys, activeProfile)
    val filteredEntries = remember(searchQuery, selectedCategory) {
        KeyCatalog.search(searchQuery, selectedCategory)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (config.isExtra) {
                    stringResource(R.string.dialog_btn_config_extra_title, config.label)
                } else {
                    stringResource(R.string.dialog_btn_config_title, config.label)
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Label row + Auto-fill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.field_button_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { labelText = comboPreview.take(12) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_autofill_combo),
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }

                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it.take(12) },
                    placeholder = { Text(stringResource(R.string.hint_button_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("config_button_label_input")
                )

                // Per-Button Size Slider
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.field_button_size, (sizeScale * 100f).roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = sizeScale,
                        onValueChange = { sizeScale = it },
                        valueRange = ButtonConfigStore.MIN_BUTTON_SIZE_SCALE..ButtonConfigStore.MAX_BUTTON_SIZE_SCALE,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricCyan,
                            activeTrackColor = ElectricCyan
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("config_button_size_slider")
                    )
                }

                // Selected keys counter + live removable pills
                Text(
                    text = stringResource(
                        R.string.multi_key_counter,
                        selectedKeys.size,
                        ButtonConfigStore.MAX_COMBO_KEYS,
                        comboPreview
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedKeys.forEach { boundKey ->
                        val pillLabel = KeyCatalog.shortLabelFor(boundKey, activeProfile)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(ElectricCyan.copy(alpha = 0.14f))
                                .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                .clickable {
                                    limitWarning = false
                                    if (selectedKeys.size > 1) {
                                        selectedKeys.remove(boundKey)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = pillLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                            if (selectedKeys.size > 1) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }

                if (limitWarning) {
                    Text(
                        text = stringResource(R.string.multi_key_max_warning, ButtonConfigStore.MAX_COMBO_KEYS),
                        style = MaterialTheme.typography.labelSmall,
                        color = PrecisionAmber
                    )
                }

                // Real-time Search Field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.hint_search_keys),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cd_clear_search),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("config_key_search_input")
                )

                // Category Filter Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KeyCategory.entries.forEach { category ->
                        val isSelected = category == selectedCategory
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategory = category },
                            label = {
                                Text(
                                    text = category.displayName,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                                selectedLabelColor = ElectricCyan
                            )
                        )
                    }
                }

                // Searchable Multi-Select Master Key List
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        filteredEntries.forEach { entry ->
                            val entryBoundKey = entry.toBoundKey()
                            val isChecked = selectedKeys.contains(entryBoundKey)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isChecked) ElectricCyan.copy(alpha = 0.08f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        limitWarning = false
                                        if (isChecked) {
                                            if (selectedKeys.size > 1) {
                                                selectedKeys.remove(entryBoundKey)
                                            }
                                        } else {
                                            if (selectedKeys.size < ButtonConfigStore.MAX_COMBO_KEYS) {
                                                selectedKeys.add(entryBoundKey)
                                            } else {
                                                limitWarning = true
                                            }
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = ElectricCyan,
                                        checkmarkColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = entry.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = entry.category.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (config.isExtra) {
                    OutlinedButton(
                        onClick = { onDelete(config.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("delete_extra_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AlertCrimson.copy(alpha = 0.7f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = AlertCrimson,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_delete_extra_button),
                            color = AlertCrimson
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val safeKeys = selectedKeys.toList()
                        .ifEmpty { listOf(KeyCatalog.XBOX_KEYS.first().toBoundKey()) }
                    val finalLabel = labelText.trim().ifBlank { comboPreview.take(12) }
                    onSave(
                        config.copy(
                            label = finalLabel,
                            boundKeys = safeKeys,
                            sizeScale = sizeScale
                        )
                    )
                },
                modifier = Modifier.testTag("save_button_config")
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
