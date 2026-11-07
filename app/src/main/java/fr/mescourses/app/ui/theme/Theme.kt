package fr.mescourses.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.mescourses.core.visual.DishVisuals

/** Palette « Potager » : vert sauge, terracotta, moutarde, crème, ardoise. */
object Potager {
    val Sage = Color(0xFF4F7A4A)
    val Terracotta = Color(0xFFC8643B)
    val Mustard = Color(0xFFE0A93B)
    val Cream = Color(0xFFFBF8F1)
    val Slate = Color(0xFF2F3A33)
}

private val Light = lightColorScheme(
    primary = Potager.Sage, onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8CF), onPrimaryContainer = Color(0xFF10200E),
    secondary = Potager.Terracotta, onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8DCCD), onSecondaryContainer = Color(0xFF3A1607),
    tertiary = Potager.Mustard, onTertiary = Color(0xFF2B1E00),
    tertiaryContainer = Color(0xFFFBE7B8), onTertiaryContainer = Color(0xFF3A2A00),
    background = Potager.Cream, onBackground = Potager.Slate,
    surface = Potager.Cream, onSurface = Potager.Slate,
    surfaceVariant = Color(0xFFECE6DA), onSurfaceVariant = Color(0xFF5B6358),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F2E9),
    surfaceContainer = Color(0xFFF1ECE2), surfaceContainerHigh = Color(0xFFECE6DA),
    surfaceContainerHighest = Color(0xFFE6DFD2),
    outline = Color(0xFF8B9286), outlineVariant = Color(0xFFD7D0C3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA5CF9C), onPrimary = Color(0xFF173816),
    primaryContainer = Color(0xFF375F33), onPrimaryContainer = Color(0xFFD6E8CF),
    secondary = Color(0xFFFFB59A), onSecondary = Color(0xFF5A1F05),
    secondaryContainer = Color(0xFF7C3A1C), onSecondaryContainer = Color(0xFFFFDBCD),
    tertiary = Color(0xFFF2C46A), onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300), onTertiaryContainer = Color(0xFFFBE7B8),
    background = Color(0xFF151A16), onBackground = Color(0xFFE4E3DC),
    surface = Color(0xFF151A16), onSurface = Color(0xFFE4E3DC),
    surfaceVariant = Color(0xFF3F4A40), onSurfaceVariant = Color(0xFFBFC9BB),
    surfaceContainerLowest = Color(0xFF101410), surfaceContainerLow = Color(0xFF1D221D),
    surfaceContainer = Color(0xFF212621), surfaceContainerHigh = Color(0xFF2B312B),
    surfaceContainerHighest = Color(0xFF363C36),
    outline = Color(0xFF899387), outlineVariant = Color(0xFF3F4A40),
)

private val Base = Typography()
private val Rounded = FontFamily.SansSerif

private val AppTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontFamily = Rounded, fontWeight = FontWeight.Bold),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Rounded, fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Rounded, fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Rounded, fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontFamily = Rounded, fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontFamily = Rounded, fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontFamily = Rounded, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 20.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun MesCoursesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Dégradé d'illustration selon la catégorie du plat. */
fun gradientFor(category: DishVisuals.Category, dark: Boolean): List<Color> = when (category) {
    DishVisuals.Category.LEGUMES -> listOf(Color(0xFFCFE6C4), Color(0xFF8DBF7F))
    DishVisuals.Category.POISSON -> listOf(Color(0xFFCFE6EA), Color(0xFF7FB2BE))
    DishVisuals.Category.VIANDE -> listOf(Color(0xFFF6D3C2), Color(0xFFD98B66))
    DishVisuals.Category.VEGETARIEN -> listOf(Color(0xFFFBE7B8), Color(0xFFE6B95A))
    DishVisuals.Category.SOUPE -> listOf(Color(0xFFFCE0C2), Color(0xFFEFA567))
    DishVisuals.Category.DESSERT -> listOf(Color(0xFFF8D6DE), Color(0xFFE29AAE))
    DishVisuals.Category.AUTRE -> listOf(Color(0xFFEDE6D6), Color(0xFFC9BC9C))
}.let { colors -> if (dark) colors.map { it.copy(alpha = 0.55f) } else colors }

val TitleStyle: TextStyle @Composable get() = MaterialTheme.typography.titleMedium
