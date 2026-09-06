package com.escpr.usbprint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.escpr.usbprint.escpr.PaperSize
import kotlin.math.roundToInt

/**
 * Pratinjau satu halaman cetak.
 *
 * Penempatan isi di sini menyalin persis apa yang dilakukan saat mencetak:
 * area cetak = kertas dikurangi margin di keempat sisi, lalu isi diskalakan
 * seragam agar muat seluruhnya dan diletakkan di tengah. Karena semua itu
 * cuma aritmetika, mengubah kertas atau menggeser margin langsung terlihat
 * tanpa perlu me-render ulang dokumennya.
 */
@Composable
fun PagePreview(
    paper: PaperSize,
    marginMm: Float,
    content: ImageBitmap?,
    monochrome: Boolean,
    modifier: Modifier = Modifier
) {
    val monoFilter = remember(monochrome) {
        if (monochrome) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        else null
    }
    val outline = MaterialTheme.colorScheme.primary
    val emptyText = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Kertas selalu tampil utuh: sisi mana pun yang lebih dulu mentok, itu
        // yang menentukan skala.
        val paperAspect = paper.widthMm / paper.heightMm
        val boxAspect = maxWidth / maxHeight
        val paperModifier = if (boxAspect > paperAspect) {
            Modifier.fillMaxHeight().aspectRatio(paperAspect)
        } else {
            Modifier.fillMaxWidth().aspectRatio(paperAspect)
        }

        Box(
            paperModifier
                .shadow(6.dp, RoundedCornerShape(3.dp))
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val layout = computePreviewLayout(
                    paperWidthMm = paper.widthMm,
                    paperHeightMm = paper.heightMm,
                    marginMm = marginMm,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    contentWidth = content?.width ?: 0,
                    contentHeight = content?.height ?: 0
                )

                if (content != null) {
                    drawImage(
                        image = content,
                        dstOffset = IntOffset(
                            layout.contentLeft.roundToInt(),
                            layout.contentTop.roundToInt()
                        ),
                        dstSize = IntSize(
                            layout.contentWidth.roundToInt().coerceAtLeast(1),
                            layout.contentHeight.roundToInt().coerceAtLeast(1)
                        ),
                        colorFilter = monoFilter,
                        filterQuality = FilterQuality.Medium
                    )
                }

                // Garis putus-putus menandai batas area cetak.
                val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 7f), 0f)
                drawRect(
                    color = outline.copy(alpha = 0.55f),
                    topLeft = Offset(layout.insetX, layout.insetY),
                    size = Size(layout.printableWidth, layout.printableHeight),
                    style = Stroke(width = 2f, pathEffect = dash)
                )
            }

            if (content == null) {
                Text(
                    text = "Belum ada dokumen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = emptyText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            }
        }
    }
}
