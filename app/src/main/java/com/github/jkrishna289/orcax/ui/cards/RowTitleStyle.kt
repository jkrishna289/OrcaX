package com.github.jkrishna289.orcax.ui.cards

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import com.github.jkrishna289.orcax.R

/**
 * The title style of a home row, by the engine's stable row id: each themed row gets a typeface
 * that fits what it holds ("Coming Soon" futuristic, "Crime Drama" a typewriter, …). The viewer's
 * own rows (Continue Watching, Recently Added, the library rows) and any id not listed here keep
 * the plain theme title. Fonts are Google Fonts in res/font (SIL OFL; Special Elite Apache 2.0).
 */
@Composable
fun rowTitleStyle(rowId: String): TextStyle {
    val base = MaterialTheme.typography.titleLarge
    // "Because You Watched X" rows are one per seed title: byw:<group>.
    val key = if (rowId.startsWith("byw:")) "becauseyouwatched" else rowId
    return RowTitleStyles[key]?.let(base::merge) ?: base
}

private fun font(
    resId: Int,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
) = FontFamily(Font(resId, weight, style))

private val RowTitleStyles: Map<String, TextStyle> =
    mapOf(
        "comingsoon" to TextStyle(fontFamily = font(R.font.row_orbitron, FontWeight.Bold), fontSize = 20.sp, letterSpacing = 3.sp),
        "trending" to TextStyle(fontFamily = font(R.font.row_bebas_neue), fontSize = 30.sp, letterSpacing = 2.sp),
        "spotlight" to TextStyle(fontFamily = font(R.font.row_limelight), fontSize = 24.sp),
        "foryou" to TextStyle(fontFamily = font(R.font.row_comfortaa, FontWeight.Bold), fontSize = 22.sp),
        "youmightlike" to TextStyle(fontFamily = font(R.font.row_righteous), fontSize = 23.sp),
        "becauseyouwatched" to
            TextStyle(
                fontFamily = font(R.font.row_lora_italic, FontWeight.SemiBold, FontStyle.Italic),
                fontSize = 23.sp,
            ),
        "country" to TextStyle(fontFamily = font(R.font.row_alfa_slab_one), fontSize = 21.sp, letterSpacing = .5.sp),
        "mood:mindbending" to TextStyle(fontFamily = font(R.font.row_monoton), fontSize = 24.sp, letterSpacing = 2.sp),
        "mood:darkthrillers" to TextStyle(fontFamily = font(R.font.row_creepster), fontSize = 27.sp, letterSpacing = 1.5.sp),
        "mood:feelgood" to TextStyle(fontFamily = font(R.font.row_pacifico), fontSize = 22.sp),
        "mood:actionpacked" to TextStyle(fontFamily = font(R.font.row_black_ops_one), fontSize = 23.sp, letterSpacing = 1.sp),
        "mood:crimedrama" to TextStyle(fontFamily = font(R.font.row_special_elite), fontSize = 23.sp),
        "mood:weekendbinge" to TextStyle(fontFamily = font(R.font.row_fredoka, FontWeight.SemiBold), fontSize = 24.sp),
        "mood:quickwatch" to TextStyle(fontFamily = font(R.font.row_racing_sans_one), fontSize = 24.sp),
        "mood:acclaimed" to
            TextStyle(
                fontFamily = font(R.font.row_playfair_italic, FontWeight.Bold, FontStyle.Italic),
                fontSize = 24.sp,
            ),
    )
