package app.xodos2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

fun Modifier.glassDialogStyle(): Modifier = composed {
    // Frosted glass feel — semi-transparent dark gradient with rounded corners and internal padding
    this
        .clip(RoundedCornerShape(12.dp))
        .background(
            Brush.verticalGradient(
                listOf(
                    Color(0xAA0B0B12), // top semi-transparent
                    Color(0x88030105)  // bottom slightly different opacity
                )
            )
        )
        .padding(12.dp)
}
