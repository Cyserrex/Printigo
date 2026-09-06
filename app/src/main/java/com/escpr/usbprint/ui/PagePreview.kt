package com.escpr.usbprint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.escpr.usbprint.layout.RectMm
import kotlin.math.min
import kotlin.math.roundToInt

/** Satu isi yang digambar di atas kertas pratinjau. */
data class PreviewItem(
    val id: Long,
    val rect: RectMm,
    val image: ImageBitmap?,
    val selected: Boolean = false,
)

/**
 * Pratinjau satu halaman cetak.
 *
 * Menerima daftar isi, bukan satu isi saja, sehingga bentuk yang sama dipakai
 * untuk dokumen tunggal maupun lembar berisi banyak foto. Semua posisi datang
 * dalam milimeter dari model tata letak -- fungsi yang sama yang dipakai jalur
 * cetak -- jadi yang terlihat di sini bukan perkiraan.
 */
@Composable
fun PagePreview(
    paperWidthMm: Float,
    paperHeightMm: Float,
    printable: RectMm,
    items: List<PreviewItem>,
    monochrome: Boolean,
    interactive: Boolean,
    onTapMm: (xMm: Float, yMm: Float) -> Unit,
    onGesture: (panXmm: Float, panYmm: Float, zoom: Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "Belum ada dokumen",
) {
    val monoFilter = remember(monochrome) {
        if (monochrome) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        else null
    }
    val measurer = rememberTextMeasurer()

    val scheme = MaterialTheme.colorScheme
    val rulerColor = scheme.onSurfaceVariant
    val safeColor = scheme.primary
    val cutColor = scheme.error
    val paperEdge = scheme.outlineVariant
    val emptyColor = scheme.onSurfaceVariant

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Skala kertas dihitung di luar Canvas supaya gestur bisa mengubah
        // geseran piksel menjadi milimeter memakai angka yang sama.
        val density = LocalDensity.current
        val gutterPx = with(density) { RULER_GUTTER.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val paperScale = min(
            (widthPx - gutterPx) / paperWidthMm,
            (heightPx - gutterPx) / paperHeightMm,
        ).coerceAtLeast(0.01f)

        val paperWidthPx = paperWidthMm * paperScale
        val paperHeightPx = paperHeightMm * paperScale
        val originX = gutterPx + (widthPx - gutterPx - paperWidthPx) / 2f
        val originY = gutterPx + (heightPx - gutterPx - paperHeightPx) / 2f

        val gestureModifier = if (!interactive) Modifier else Modifier
            .pointerInput(paperScale, originX, originY) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onGesture(pan.x / paperScale, pan.y / paperScale, zoom)
                }
            }
            .pointerInput(paperScale, originX, originY) {
                detectTapGestures(
                    onTap = { offset ->
                        onTapMm(
                            (offset.x - originX) / paperScale,
                            (offset.y - originY) / paperScale,
                        )
                    },
                    onDoubleTap = { onReset() },
                )
            }

        Canvas(Modifier.fillMaxSize().then(gestureModifier)) {
            fun x(mm: Float) = originX + mm * paperScale
            fun y(mm: Float) = originY + mm * paperScale

            val paperRect = Rect(originX, originY, originX + paperWidthPx, originY + paperHeightPx)
            val printableRect = Rect(
                x(printable.left), y(printable.top), x(printable.right), y(printable.bottom)
            )

            fun isClipped(rect: RectMm) =
                rect.left < printable.left - TOLERANCE ||
                    rect.top < printable.top - TOLERANCE ||
                    rect.right > printable.right + TOLERANCE ||
                    rect.bottom > printable.bottom + TOLERANCE

            drawRect(Color.White, paperRect.topLeft, paperRect.size)

            val anyClipped = items.any { it.image != null && isClipped(it.rect) }

            clipRect(paperRect.left, paperRect.top, paperRect.right, paperRect.bottom) {
                for (item in items) {
                    val image = item.image ?: continue
                    val itemRect = Rect(
                        x(item.rect.left), y(item.rect.top),
                        x(item.rect.right), y(item.rect.bottom),
                    )
                    if (itemRect.width < 1f || itemRect.height < 1f) continue

                    drawImage(
                        image = image,
                        dstOffset = IntOffset(
                            itemRect.left.roundToInt(), itemRect.top.roundToInt()
                        ),
                        dstSize = IntSize(
                            itemRect.width.roundToInt().coerceAtLeast(1),
                            itemRect.height.roundToInt().coerceAtLeast(1),
                        ),
                        colorFilter = monoFilter,
                        filterQuality = FilterQuality.Medium,
                    )

                    // Bagian yang keluar area cetak diwarnai merah: itulah yang
                    // tidak akan tercetak.
                    if (isClipped(item.rect)) {
                        clipRect(
                            printableRect.left, printableRect.top,
                            printableRect.right, printableRect.bottom,
                            clipOp = ClipOp.Difference,
                        ) {
                            drawRect(
                                color = cutColor.copy(alpha = 0.45f),
                                topLeft = itemRect.topLeft,
                                size = itemRect.size,
                            )
                        }
                    }

                    if (item.selected) {
                        drawRect(
                            color = safeColor,
                            topLeft = itemRect.topLeft,
                            size = itemRect.size,
                            style = Stroke(width = 3f),
                        )
                    }
                }
            }

            drawRect(
                color = paperEdge,
                topLeft = paperRect.topLeft,
                size = paperRect.size,
                style = Stroke(width = 1f),
            )

            val boundary = if (anyClipped) cutColor else safeColor
            drawRect(
                color = boundary.copy(alpha = if (anyClipped) 0.95f else 0.55f),
                topLeft = printableRect.topLeft,
                size = printableRect.size,
                style = Stroke(
                    width = if (anyClipped) 2.5f else 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f), 0f),
                ),
            )

            drawRulers(
                paperWidthMm = paperWidthMm,
                paperHeightMm = paperHeightMm,
                paperScale = paperScale,
                originX = originX,
                originY = originY,
                gutter = gutterPx,
                color = rulerColor,
                measurer = measurer,
            )
        }

        // Keadaan kosong ditulis sebagai komponen, bukan digambar ke kanvas:
        // teks di dalam Canvas tidak terbaca pembaca layar.
        if (items.none { it.image != null }) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = emptyColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = RULER_GUTTER / 2, y = RULER_GUTTER / 2),
            )
        }
    }
}

private val RULER_GUTTER = 18.dp
private const val TOLERANCE = 0.05f

/**
 * Penggaris milimeter di tepi atas dan kiri.
 *
 * Jarak antar garis menyesuaikan skala: pada kertas besar yang ditampilkan
 * kecil, garis tiap 5 mm akan menyatu jadi blok abu-abu.
 */
private fun DrawScope.drawRulers(
    paperWidthMm: Float,
    paperHeightMm: Float,
    paperScale: Float,
    originX: Float,
    originY: Float,
    gutter: Float,
    color: Color,
    measurer: TextMeasurer,
) {
    val minorMm = when {
        paperScale > 3f -> 5f
        paperScale > 1.2f -> 10f
        else -> 20f
    }
    val majorEvery = if (minorMm >= 20f) 100f else 50f
    val labelStyle = TextStyle(fontSize = 8.sp, color = color)

    fun tick(mm: Float, horizontal: Boolean) {
        val major = (mm % majorEvery) < 0.01f
        val length = if (major) gutter * 0.62f else gutter * 0.3f
        if (horizontal) {
            val px = originX + mm * paperScale
            drawLine(
                color = color.copy(alpha = if (major) 0.9f else 0.45f),
                start = Offset(px, originY - length),
                end = Offset(px, originY),
                strokeWidth = 1f,
            )
            if (major && mm > 0f) {
                drawText(
                    measurer.measure(mm.roundToInt().toString(), labelStyle),
                    topLeft = Offset(px + 2f, originY - gutter),
                )
            }
        } else {
            val px = originY + mm * paperScale
            drawLine(
                color = color.copy(alpha = if (major) 0.9f else 0.45f),
                start = Offset(originX - length, px),
                end = Offset(originX, px),
                strokeWidth = 1f,
            )
            if (major && mm > 0f) {
                drawText(
                    measurer.measure(mm.roundToInt().toString(), labelStyle),
                    topLeft = Offset(originX - gutter, px + 1f),
                )
            }
        }
    }

    var mm = 0f
    while (mm <= paperWidthMm + 0.01f) {
        tick(mm, horizontal = true)
        mm += minorMm
    }
    mm = 0f
    while (mm <= paperHeightMm + 0.01f) {
        tick(mm, horizontal = false)
        mm += minorMm
    }

    drawLine(
        color = color.copy(alpha = 0.5f),
        start = Offset(originX, originY),
        end = Offset(originX + paperWidthMm * paperScale, originY),
        strokeWidth = 1f,
    )
    drawLine(
        color = color.copy(alpha = 0.5f),
        start = Offset(originX, originY),
        end = Offset(originX, originY + paperHeightMm * paperScale),
        strokeWidth = 1f,
    )
}
