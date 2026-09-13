package com.deepseekbuddy.app.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 圆角输入框：全局统一柔和圆角（默认 16dp，聊天输入框可用 20dp） */
@Composable
fun RoundTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: @Composable (() -> Unit)? = null,
    cornerRadius: Dp = 16.dp,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        supportingText = supportingText,
        shape = RoundedCornerShape(cornerRadius),
    )
}
