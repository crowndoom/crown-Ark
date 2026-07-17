package app.xodos2.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Sleek modern minimal theme */
@Composable
fun xodos2Theme(
    content: @Composable () -> Unit
) {
    val modernDarkScheme = darkColorScheme(
        background = Color(0xFF0B0F19),          
        surface = Color(0xFF131A2A),             
        surfaceVariant = Color(0xFF333333), 
            primary = Color(0xFF7502D1),         
        onPrimary = Color.White,           
        secondary = Color(0xFF10B981), // Emerald Accent
        onSecondary = Color.Black,
        onBackground = Color(0xFFF8FAFC),       
        onSurface = Color(0xFFF1F5F9),
        outline = Color(0xFF334155),
        error = Color(0xFFEF4444),
        onError = Color.White
    )

    val modernTypography = Typography(
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            letterSpacing = (-0.5).sp,
            color = Color(0xFFF8FAFC)
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            letterSpacing = (-0.25).sp,
            color = Color(0xFFF8FAFC)
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            color = Color(0xFFCBD5E1)
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            color = Color(0xFF94A3B8)
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp,
            color = Color(0xFF3B82F6)
        )
    )

    MaterialTheme(
        colorScheme = modernDarkScheme,
        typography = modernTypography,
        content = content
    )
}
