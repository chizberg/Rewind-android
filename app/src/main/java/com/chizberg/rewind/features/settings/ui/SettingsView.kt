package com.chizberg.rewind.features.settings.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chizberg.rewind.R
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.features.settings.SettingsAction
import com.chizberg.rewind.features.settings.SettingsModel
import com.chizberg.rewind.ui.toComposeColor

/**
 * The settings screen. Port of iOS `SettingsView`: the cluster-preview switch, the gradient-scheme
 * picker, and the two link sections with their footers and credits — in iOS's own order and with
 * its own labels.
 *
 * Divergences, all previously decided:
 * - **no "App Icon" section**: alternate app icons are a documented non-port, so the picker, its
 *   action and its failure alert are all absent (which is why this screen holds no alert at all).
 * - **no "View in App Store" row**: there is no Play listing to point at, and iOS's own label says
 *   "App Store". Inventing a URL for an unpublished app — or eight new translations for a
 *   differently-named store — is not a port, so the row is simply left out.
 * - iOS presents this from a `.sheet` inside a `NavigationStack`; here it is one more layer of the
 *   shared overlay stack (as search and the lists are), so back — gesture or the top bar's arrow —
 *   closes it. The design pack's container-transform morph from the gear button is not built: no
 *   screen in this port has a shared-element transition (the repo's decision outranks the
 *   per-screen doc), and the back arrow follows Material rather than iOS's chevron.
 * - the inset-grouped `List` becomes grouped `surfaceContainer` cards with `labelMedium` headers
 *   and `bodySmall` footers — the M3 rendering of the same structure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsView(
    model: SettingsModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            contentPadding =
                PaddingValues(
                    top = insets.calculateTopPadding() + ScreenPadding,
                    bottom = insets.calculateBottomPadding() + ScreenPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Section(header = R.string.settings_section_map) {
                    SwitchRow(
                        title = stringResource(R.string.settings_open_cluster_previews),
                        checked = state.stored.openClusterPreviews,
                        onCheckedChange = {
                            model(SettingsAction.SetOpenClusterPreviews(it))
                        },
                    )
                }
            }

            item {
                Section(header = R.string.settings_section_gradient_picker) {
                    GradientSchemePicker(
                        selected = state.stored.gradientScheme,
                        onSelect = { model(SettingsAction.GradientSchemeSelected(it)) },
                    )
                }
            }

            item {
                Section(
                    header = R.string.settings_section_pastvu,
                    footer = {
                        // iOS stacks the two sentences as separate lines in the footer's VStack.
                        Column {
                            FooterText(stringResource(R.string.settings_pastvu_api_footer))
                            FooterText(stringResource(R.string.settings_pastvu_thanks_footer))
                        }
                    },
                ) {
                    LinkRow(
                        title = stringResource(R.string.settings_view_pastvu_website),
                        icon = Icons.Rounded.OpenInNew,
                        onClick = { model(SettingsAction.OpenPastVu) },
                    )
                    RowDivider()
                    LinkRow(
                        title = stringResource(R.string.settings_pastvu_rules),
                        icon = Icons.Rounded.OpenInNew,
                        onClick = { model(SettingsAction.PastVuRules) },
                    )
                }
            }

            item {
                Section(
                    header = R.string.settings_section_about,
                    footer = { Credits() },
                ) {
                    LinkRow(
                        title = stringResource(R.string.settings_contact_developer),
                        // A mail affordance, not "open in new": this is an email, not a web page.
                        icon = Icons.Rounded.MailOutline,
                        onClick = { model(SettingsAction.Contact) },
                    )
                    RowDivider()
                    LinkRow(
                        title = stringResource(R.string.settings_view_source_code),
                        icon = Icons.Rounded.OpenInNew,
                        onClick = { model(SettingsAction.OpenRepo) },
                    )
                }
            }
        }
    }
}

/**
 * One inset-grouped section: a `labelMedium` header, a rounded `surfaceContainer` card holding the
 * rows, and an optional footer under it. Port of iOS's `Section { } header: { } footer: { }`.
 */
@Composable
private fun Section(
    @StringRes header: Int,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .padding(horizontal = ScreenPadding)
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HeaderGap),
    ) {
        Text(
            text = stringResource(header),
            modifier = Modifier.padding(horizontal = HeaderInset),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(CardCorner),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column { content() }
        }
        footer?.let {
            Box(Modifier.padding(horizontal = HeaderInset)) { it() }
        }
    }
}

@Composable
private fun FooterText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * A row that toggles a boolean. The row owns the toggle (`toggleable` with the `Switch` role) and
 * the `Switch` itself takes no callback, so there is one a11y node instead of two. No haptic — iOS
 * plays none here either.
 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = RowPaddingH, vertical = RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A row that opens something outside the app. Port of iOS's borderless `makeButton` rows. */
@Composable
private fun LinkRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = RowPaddingH, vertical = RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The five schemes as single-choice rows. Port of iOS `gradientSchemePicker`: each row is the whole
 * ramp drawn as a swatch with the scheme's name **on top of it** (bold once selected) — the
 * screen's one recognisable Rewind detail — plus a selection indicator. iOS's
 * `checkmark.circle.fill`/`circle` pair becomes a `RadioButton`, which is what M3 uses for single
 * choice, and the row itself carries the `selectable` semantics so the indicator is not a second
 * tap target.
 *
 * The 1dp `outlineVariant` ring is not decoration: a swatch is the most saturated thing on the
 * screen and the light-ended ramps (Black & White, Warm) otherwise bleed into the card behind them.
 */
@Composable
private fun GradientSchemePicker(
    selected: GradientScheme,
    onSelect: (GradientScheme) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        GradientScheme.entries.forEach { scheme ->
            val isSelected = scheme == selected
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelect(scheme) },
                        ).padding(horizontal = RowPaddingH, vertical = RowPaddingV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SwatchGap),
            ) {
                GradientSwatch(
                    scheme = scheme,
                    isSelected = isSelected,
                    modifier = Modifier.weight(1f),
                )
                RadioButton(selected = isSelected, onClick = null)
            }
        }
    }
}

/** iOS `GradientSchemeView` + its overlaid title: the whole ramp, left-aligned label over it. */
@Composable
private fun GradientSwatch(
    scheme: GradientScheme,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val stops =
        remember(scheme) {
            scheme.value.map { it.position.toFloat() to it.value.toComposeColor() }.toTypedArray()
        }
    // The label sits over the ramp's leading end, so its legibility is decided by that stop — the
    // same `foreground` rule the map's badges use (white unless the tint is light).
    val labelColor = remember(scheme) { scheme.foreground(scheme.value.first().value) }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(SwatchCorner)
    Box(
        modifier
            .height(SwatchHeight)
            .background(Brush.horizontalGradient(colorStops = stops), shape)
            .border(SwatchBorder, outline, shape)
            .padding(horizontal = SwatchPaddingH),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = scheme.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = labelColor.toComposeColor(),
        )
    }
}

/**
 * The About footer. Port of iOS's `credits` VStack, markdown links and all: the author, the
 * honourable mentions, then the sign-off. The links are plain [LinkAnnotation.Url]s, opened by the
 * platform's own URI handler — SwiftUI's markdown links behave the same way, so no reducer action
 * carries them.
 */
@Composable
private fun Credits() {
    val linkColor = MaterialTheme.colorScheme.primary
    val madeBy = stringResource(R.string.settings_made_by)
    val withHelp = stringResource(R.string.settings_with_help_from)
    val text =
        remember(linkColor, madeBy, withHelp) {
            val linkStyles =
                TextLinkStyles(
                    style =
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                )
            buildAnnotatedString {
                append(madeBy)
                appendContributor(CHIZBERG, linkStyles)
                append("\n")
                append(withHelp)
                HONORABLE_MENTIONS.forEach { contributor ->
                    append("\n")
                    append(BULLET)
                    appendContributor(contributor, linkStyles)
                }
                append("\n\n")
                append(SIGN_OFF)
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun AnnotatedString.Builder.appendContributor(
    contributor: Contributor,
    linkStyles: TextLinkStyles,
) {
    withLink(LinkAnnotation.Url(url = contributor.url, styles = linkStyles)) {
        append("@${contributor.username}")
    }
}

/** iOS `Contributor`: a username rendered as a markdown link to [url]. */
private data class Contributor(
    val username: String,
    val url: String,
)

private val CHIZBERG = Contributor("chizberg", "https://github.com/chizberg")

private val HONORABLE_MENTIONS =
    listOf(
        Contributor("lisa.iso", "https://www.instagram.com/l.chizberg"),
        Contributor("dmitriitrif", "https://github.com/dmitriitrif"),
        Contributor("Xelwow", "https://github.com/xelwow"),
    )

// Credits copy that iOS keeps as literals in the view (only "Made by " and "with a little help
// from:" are localized — see the strings file); the rest is punctuation and the sign-off.
private const val BULLET = "• "
private const val SIGN_OFF = "☮️ & ❤️\nRewind\n2026"

private val ScreenPadding = 16.dp
private val SectionSpacing = 24.dp
private val HeaderGap = 8.dp
private val HeaderInset = 12.dp

// A settings card is a "large" M3 container; the swatch keeps the app's 10dp badge corner.
private val CardCorner = 16.dp
private val SwatchCorner = 10.dp
private val SwatchBorder = 1.dp

private val RowPaddingH = 16.dp
private val RowPaddingV = 12.dp
private val SwatchGap = 10.dp
private val SwatchHeight = 36.dp
private val SwatchPaddingH = 10.dp

/** On a wide screen the list stops growing and stays a readable column (foundation's rule). */
private val MaxContentWidth = 640.dp
