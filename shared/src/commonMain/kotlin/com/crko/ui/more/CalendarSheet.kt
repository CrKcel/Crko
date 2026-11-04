package com.crko.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.SurfaceCard
import com.crko.ui.icons.AppIcons
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * Calendars, calendar subscriptions and ICS import/export, collected in one bottom sheet opened
 * from the "More" tab. Export without an id exports every calendar.
 */
@Composable
fun CalendarSheet(
    show: Boolean,
    state: AppStateHolder,
    strings: Strings,
    onDismiss: () -> Unit,
    onImportCalendar: () -> Unit,
    /** Null exports every calendar; a non-null id exports exactly that calendar. */
    onExportCalendar: (String?) -> Unit,
) {
    WindowBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = strings.calendarsAndData,
        endAction = {
            TextButton(
                text = strings.done,
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        },
        content = {
            LazyColumn(
                modifier = Modifier.fillMaxHeight(0.92f),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item("calendars") {
                    LinkedCardTitle(
                        icon = AppIcons.Week,
                        iconTint = MiuixTheme.colorScheme.primary,
                        title = strings.calendars,
                    )
                    Spacer(Modifier.height(12.dp))
                    CalendarsSection(
                        state = state,
                        strings = strings,
                        onExportCalendar = onExportCalendar,
                    )
                    Spacer(Modifier.height(20.dp))
                }
                item("data") {
                    LinkedCardTitle(
                        icon = AppIcons.Timeline,
                        iconTint = MiuixTheme.colorScheme.primary,
                        title = strings.calendarData,
                    )
                    Spacer(Modifier.height(12.dp))
                    SurfaceCard {
                        CalendarFileRow(
                            icon = AppIcons.Import,
                            title = strings.importIcs,
                            subtitle = strings.importIcsHint,
                            onClick = onImportCalendar,
                        )
                        CardRowDivider()
                        CalendarFileRow(
                            icon = AppIcons.Export,
                            title = strings.exportIcs,
                            subtitle = strings.exportIcsHint,
                            onClick = { onExportCalendar(null) },
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
                item("subscriptions") {
                    LinkedCardTitle(
                        icon = AppIcons.Export,
                        iconTint = MiuixTheme.colorScheme.primary,
                        title = strings.subscriptions,
                    )
                    Spacer(Modifier.height(12.dp))
                    SubscriptionsSection(state = state, strings = strings)
                }
            }
        },
    )
}

@Composable
internal fun LinkedCardTitle(icon: ImageVector, iconTint: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = 0.15f))
                .padding(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.width(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text = title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CalendarFileRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    BasicComponent(onClick = onClick, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(6.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
