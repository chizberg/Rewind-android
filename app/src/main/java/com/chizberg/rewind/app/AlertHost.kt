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
        dismissButton =
            params.message
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
