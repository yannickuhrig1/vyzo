package fr.yannickuhrig.vyzo.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Modules du QR code (1 case = 1 module), marge réglementaire comprise. */
fun qrMatrix(text: String): BitMatrix =
    QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )

/** QR code noir sur blanc, dessiné module par module (net à toutes les tailles). */
@Composable
fun QrImage(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(2.dp)
            .semantics { contentDescription = "QR code" },
    ) {
        val n = matrix.width
        val cell = minOf(this.size.width, this.size.height) / n
        val origin = Offset((this.size.width - cell * n) / 2f, (this.size.height - cell * n) / 2f)
        // +0.5 px pour éviter les liserés entre modules voisins.
        val cellSize = Size(cell + 0.5f, cell + 0.5f)
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(origin.x + x * cell, origin.y + y * cell), cellSize)
        }
    }
}

/**
 * Lecture d'un QR code. Sur Android : caméra arrière en plein écran.
 * Sur PC : champ où coller l'adresse affichée sous le QR code de la TV.
 */
@Composable
expect fun QrScannerDialog(title: String, hint: String, onResult: (String) -> Unit, onDismiss: () -> Unit)
