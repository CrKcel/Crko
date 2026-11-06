package com.crko.ui.more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import com.crko.i18n.AppLanguage
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.state.DarkModeSetting
import com.crko.state.PaletteSetting
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.GlassNavigationBarDefaults
import com.crko.ui.components.SectionTitle
import com.crko.ui.components.SurfaceCard
import com.crko.ui.icons.AppIcons
import com.crko.ui.theme.SeedPalette
import kotlinx.datetime.LocalTime
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Settings as a full page above the tab shell, opened from the "More" tab. The sections mirror the
 * former settings sheet; only the container changed.
 */
@Composable
fun SettingsPage(show: Boolean, state: AppStateHolder, strings: Strings, onBack: () -> Unit) {
    var showHourPicker by remember { mutableStateOf(false) }
    AnimatedVisibility(
        visible = show,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
                // Only the status bar is padded out: like the tab pages, the scroll content runs to
                // the window bottom and the trailing padding below keeps the last card clear of the
                // system gesture area when fully scrolled.
                .statusBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 16.dp, bottom = 4.dp),
            ) {
                IconButton(onClick = onBack, backgroundColor = Color.Transparent) {
                    Icon(
                        imageVector = AppIcons.Back,
                        contentDescription = strings.back,
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = strings.settings,
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = GlassNavigationBarDefaults.bottomMargin() + 12.dp),
            ) {
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
                SectionTitle(strings.gestures)
                SurfaceCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SwitchRow(
                        text = strings.gesturePinchOverview,
                        checked = state.pinchOverviewEnabled,
                    ) { state.setPinchOverview(it) }
                    CardRowDivider()
                    SwitchRow(
                        text = strings.gestureSwipeSwitch,
                        checked = state.swipeSwitchEnabled,
                    ) { state.setSwipeSwitch(it) }
                }
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
                SectionTitle(strings.cloudSync)
                CloudSyncSection(state = state, strings = strings)
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

    if (show && showHourPicker) {
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
private fun SwitchRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    BasicComponent(
        onClick = { onCheckedChange(!checked) },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = text, style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
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
