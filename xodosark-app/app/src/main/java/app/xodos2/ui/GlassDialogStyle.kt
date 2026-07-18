package app.xodos2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

fun Modifier.glassDialogStyle(): Modifier = this
    .background(color = Color(0x22000000), shape = RoundedCornerShape(12.dp))
    .padding(12.dp)
