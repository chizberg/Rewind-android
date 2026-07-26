package com.chizberg.rewind.features.search.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.SubdirectoryArrowLeft
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chizberg.rewind.R
import com.chizberg.rewind.app.RewindAlert
import com.chizberg.rewind.features.search.SearchAction
import com.chizberg.rewind.features.search.SearchModel
import com.chizberg.rewind.features.search.SearchState

/** The docked field's radius: `RewindShapes.largeIncreased` (design foundation's search-bar shape). */
private val FieldCorner = 20.dp

/** M3 level 3 — the field floats over the suggest list (design/01-map.md §6, реш. #11). */
private val FieldElevation = 6.dp

private val ScreenPadding = 16.dp
private val FieldPadding = 4.dp

/**
 * The place-search screen. Port of iOS `SearchView`: a live suggest list with the input field docked
 * at the bottom edge, autofocused on arrival, submitting on the keyboard's search key, and an alert
 * for whatever the lookup has to say.
 *
 * Divergences from the iOS view, all already decided in the design pack or by this port's shell:
 * - iOS presents this as a `.sheet` with a nav bar; here it is a full-screen layer of the shared
 *   overlay stack (like details and the list), so back — gesture or button — closes it. The design
 *   pack's zoom-morph container transform (реш. #5) is *not* built: no screen in this port has a
 *   shared-element transition, and Search is not the place to introduce a second navigation
 *   mechanism (the repo's own decision outranks the per-screen doc).
 * - the back affordance moves into the field's leading slot, which is where Material's own expanded
 *   search bar puts it, instead of iOS's nav-bar button. The design pack drops the affordance
 *   entirely and leans on predictive back; with no morph to reverse, an explicit way out is kept.
 * - an empty result for a typed query shows the same "nothing here yet" placeholder the lists use.
 *   iOS shows a stale list instead (its suggests survive the query that produced them).
 */
@Composable
fun SearchView(
    model: SearchModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                // The field rides above the keyboard, as iOS's `.safeAreaInset(.bottom)` does.
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.suggests.isNotEmpty() ->
                        SuggestList(
                            suggests = state.suggests,
                            onSelected = { model(SearchAction.External.SuggestSelected(it)) },
                            onAddToQuery = { model(SearchAction.External.AddSuggestToQuery(it)) },
                        )

                    state.query.isBlank() ->
                        EmptyState(
                            emoji = "🔎",
                            text = stringResource(R.string.search_start_typing),
                        )

                    else ->
                        EmptyState(emoji = "🗺️", text = stringResource(R.string.list_empty))
                }
            }

            SearchField(
                query = state.query,
                onQueryChange = { model(SearchAction.External.UpdateQuery(it)) },
                onSubmit = {
                    keyboard?.hide()
                    model(SearchAction.External.Submit)
                },
                onBack = onDismiss,
                focusRequester = focusRequester,
                modifier = Modifier.padding(ScreenPadding),
            )
        }
    }

    // iOS `.onAppear { searchFieldFocused = true }`: the keyboard is up before the screen settles.
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    state.alert?.let { params ->
        RewindAlert(params = params, onDismiss = { model(SearchAction.External.DismissAlert) })
    }
}

/**
 * The suggest list. Port of iOS `List(store.suggests)` + `SuggestCell`: a two-line row that searches
 * the place on tap, plus the circular button that drops its text into the field instead. Keyed by
 * place id — iOS regenerates a `UUID` per fetch, which is exactly what a Compose key must not be.
 */
@Composable
private fun SuggestList(
    suggests: List<SearchState.Suggest>,
    onSelected: (SearchState.Suggest) -> Unit,
    onAddToQuery: (SearchState.Suggest) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = ScreenPadding),
    ) {
        items(suggests, key = { it.placeId }) { suggest ->
            ListItem(
                headlineContent = {
                    Text(
                        text = suggest.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent =
                    suggest.subtitle
                        .takeIf { it.isNotEmpty() }
                        ?.let {
                            {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                trailingContent = {
                    FilledTonalIconButton(onClick = { onAddToQuery(suggest) }) {
                        Icon(
                            Icons.Rounded.SubdirectoryArrowLeft,
                            contentDescription = stringResource(R.string.search_insert_suggest),
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onSelected(suggest) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
    }
}

/**
 * The docked input. Port of iOS's bottom `searchBar` (реш. #12 keeps it at the bottom): a tonal
 * "glass" pill carrying a back button, the text field, and the clear button that scales in with the
 * text (iOS `.transition(.scale)`). Built by hand rather than from `SearchBar`/`InputField`, whose
 * fixed stadium shape and own overlay behaviour fight both the 20dp identity and this shell.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FieldCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = FieldElevation,
    ) {
        Row(
            modifier = Modifier.padding(FieldPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                decorationBox = { field ->
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_for_location),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    field()
                },
            )
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Rounded.Cancel,
                        contentDescription = stringResource(R.string.search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** iOS `overlayView`: an emoji over a line of text, centred in whatever space is left. */
@Composable
private fun EmptyState(
    emoji: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(ScreenPadding), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(emoji, style = MaterialTheme.typography.displaySmall)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
