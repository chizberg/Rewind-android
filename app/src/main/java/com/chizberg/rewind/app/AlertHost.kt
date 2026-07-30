package com.chizberg.rewind.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.chizberg.rewind.R

/**
 * The one renderer for an [AlertParams]. Port of iOS `AlertPresenter`: the stock buttons come from
 * the alert's kind rather than from the call site — an error offers "Copy to clipboard" next to
 * "OK", an info alert only "OK" — and, as with every `UIAlertAction`, tapping either dismisses.
 *
 * Every screen holding an alert in its state (the app, image details, search) renders it through
 * here, so the button set can't drift between them.
 */
@Composable
fun RewindAlert(
    params: AlertParams,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = params.title?.let { { Text(it) } },
        text = params.message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
        // The one non-stock slot: an alert's own action if it carries one (M13.5's "Go to
        // Settings"), otherwise an error's "Copy to clipboard". Either way it dismisses, as every
        // `UIAlertAction` does.
        dismissButton =
            params.action?.let { action ->
                {
                    TextButton(onClick = {
                        action.handler()
                        onDismiss()
                    }) { Text(stringResource(action.kind.labelRes)) }
                }
            } ?: params.message
                ?.takeIf { params.isError }
                ?.let { message ->
                    {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(message))
                            onDismiss()
                        }) { Text(stringResource(R.string.copy_to_clipboard)) }
                    }
                },
    )
}

/** The label an [AlertParams.Action] is rendered with — resolved here, never in the reducer. */
private val AlertParams.Action.Kind.labelRes: Int
    get() =
        when (this) {
            AlertParams.Action.Kind.OpenSettings -> R.string.go_to_settings
        }
