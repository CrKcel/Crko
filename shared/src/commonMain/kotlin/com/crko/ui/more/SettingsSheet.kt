package com.crko.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.i18n.AppLanguage
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.state.DarkModeSetting
import com.crko.state.PaletteSetting
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.SectionTitle
import com.crko.ui.components.SurfaceCard
import com.crko.ui.theme.SeedPalette
import kotlinx.datetime.LocalTime
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

@Composable
fun SettingsSheet(show: Boolean, state: AppStateHolder, strings: Strings, onDismiss: () -> Unit) {
    var showHourPicker by remember { mutableStateOf(false) }
    WindowBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = strings.settings,
        endAction = {
            TextButton(
                text = strings.done,
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(0.92f),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    item("language") {
                        SectionTitle(strings.language)
                        SurfaceCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                            OptionRow(
                                text = strings.languageSystem,
                                selected = state.languageSetting == AppLanguage.System,
                            ) { state.setLanguage(AppLanguage.System) }
                            CardRowDivider()
                            OptionRow(
                                text = strings.languageChinese,
                                selected = state.languageSetting == AppLanguage.Chinese,
                            ) { state.setLanguage(AppLanguage.Chinese) }
                            CardRowDivider()
                            OptionRow(
                                text = strings.languageEnglish,
                                selected = state.languageSetting == AppLanguage.English,
                            ) { state.setLanguage(AppLanguage.English) }
                        }
                    }
                    item("appearance") {
                        SectionTitle(strings.appearance)
                        SurfaceCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                            OptionRow(
                                text = strings.modeSystem,
                                selected = state.darkModeSetting == DarkModeSetting.System,
                            ) { state.setDarkMode(DarkModeSetting.System) }
                            CardRowDivider()
                            OptionRow(
                                text = strings.modeLight,
                                selected = state.darkModeSetting == DarkModeSetting.Light,
                            ) { state.setDarkMode(DarkModeSetting.Light) }
                            CardRowDivider()
                            OptionRow(
                                text = strings.modeDark,
                                selected = state.darkModeSetting == DarkModeSetting.Dark,
                            ) { state.setDarkMode(DarkModeSetting.Dark) }
                        }
                    }
                    item("palette") {
                        SectionTitle(strings.colorPalette)
                        SurfaceCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                            OptionRow(
                                text = strings.paletteMonet,
                                selected = state.paletteSetting == PaletteSetting.Monet,
                            ) { state.setPalette(PaletteSetting.Monet) }
                            CardRowDivider()
                            OptionRow(
                                text = strings.paletteMonochrome,
                                selected = state.paletteSetting == PaletteSetting.Monochrome,
                            ) { state.setPalette(PaletteSetting.Monochrome) }
                            CardRowDivider()
                            OptionRow(
                                text = strings.paletteCustom,
                                selected = state.paletteSetting == PaletteSetting.Custom,
                            ) { state.setPalette(PaletteSetting.Custom) }
                            if (state.paletteSetting == PaletteSetting.Custom) {
                                CardRowDivider()
                                ColorRow(
                                    selected = state.settings.keyColor,
                                    onSelect = { state.setKeyColor(it) },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                    item("defaultHour") {
                        SectionTitle(strings.timelineStart)
                        SurfaceCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                            BasicComponent(
                                onClick = { showHourPicker = true },
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(strings.startHour, style = MiuixTheme.textStyles.body1)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = strings.time(LocalTime(state.settings.agendaScrollHour, 0)),
                                        style = MiuixTheme.textStyles.body1,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    item("about") {
                        SectionTitle(strings.about)
                        SurfaceCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                            BasicComponent(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(strings.version, style = MiuixTheme.textStyles.body1)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = "1.0.0",
                                        style = MiuixTheme.textStyles.body1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                            CardRowDivider()
                            BasicComponent(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    text = strings.storageNote,
                                    style = MiuixTheme.textStyles.body1,
                                )
                            }
                        }
                    }
                }
            }
        },
    )

    if (showHourPicker) {
        HourPickerDialog(
            initialHour = state.settings.agendaScrollHour,
            strings = strings,
            onDismiss = { showHourPicker = false },
            onConfirm = {
                state.setDefaultHour(it)
                showHourPicker = false
            },
        )
    }
}

@Composable
private fun OptionRow(text: String, selected: Boolean, onClick: () -> Unit) {
    BasicComponent(
        onClick = onClick,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body1,
                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            Spacer(Modifier.weight(1f))
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun ColorRow(selected: Long, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeedPalette.forEach { color ->
            val argb = color.value.toLong()
            val isSelected = (argb and 0xFFFFFFFFL) == (selected and 0xFFFFFFFFL)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MiuixTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(argb) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HourPickerDialog(
    initialHour: Int,
    strings: Strings,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val picker = rememberTimePickerState(initialHour = initialHour, initialMinute = 0, is24Hour = true)
    WindowDialog(show = true, onDismissRequest = onDismiss, title = strings.timelineStart) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TimePicker(state = picker)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(text = strings.cancel, onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = strings.save,
                    onClick = { onConfirm(picker.hour) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
