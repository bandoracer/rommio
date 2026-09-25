package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/**
 * A text field in the theme (docs/theme-layouts.md "Setup"): Aurora a glass field (`surfaceStrong`,
 * radius `field`); Riso white paper in a 1.5 dp ink box with `numeral` text.
 */
@Composable
fun ThemedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    label: String? = null,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val riso = theme.isRiso
    val textStyle = if (riso) theme.type.numeral.style.sized(15f) else theme.type.body.style
    val shape = if (riso) RoundedCornerShape(0.dp) else theme.radius.fieldShape
    TextField(
        value = value,
        onValueChange = onValueChange,
        // Aurora: glass with a hairline edge, so the field still reads on a white sheet.
        modifier = modifier.then(
            if (riso) {
                Modifier.border(1.5.dp, c.ink, shape)
            } else {
                Modifier.themedSurface(theme, theme.radius.field, fill = c.surfaceStrong, nested = true).border(0.5.dp, c.hairline, shape)
            },
        ),
        singleLine = singleLine,
        enabled = enabled,
        textStyle = textStyle.copy(color = c.ink),
        label = label?.let { { Text(it, style = theme.type.caption.style) } },
        placeholder = placeholder?.let { { Text(it, style = textStyle, color = c.inkTertiary) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = if (riso) c.surface else Color.Transparent,
            unfocusedContainerColor = if (riso) c.surface else Color.Transparent,
            disabledContainerColor = if (riso) c.surface else Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = c.ink,
            unfocusedTextColor = c.ink,
            focusedPlaceholderColor = c.inkTertiary,
            unfocusedPlaceholderColor = c.inkTertiary,
            focusedLeadingIconColor = c.inkSecondary,
            unfocusedLeadingIconColor = c.inkSecondary,
            focusedTrailingIconColor = c.inkSecondary,
            unfocusedTrailingIconColor = c.inkSecondary,
            focusedLabelColor = c.inkSecondary,
            unfocusedLabelColor = c.inkSecondary,
            cursorColor = if (riso) c.ink else RommioTheme.light.tintText,
        ),
    )
}
