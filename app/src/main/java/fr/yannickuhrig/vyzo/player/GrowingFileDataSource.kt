package fr.yannickuhrig.vyzo.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/** Lit un fichier qui grossit encore : en fin de fichier, attend la suite tant que [growing] est vrai. */
class GrowingFileDataSource(private val file: File, private val growing: () -> Boolean) : BaseDataSource(false) {
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        raf = RandomAccessFile(file, "r").also { it.seek(dataSpec.position) }
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val r = raf ?: return C.RESULT_END_OF_INPUT
        var waited = 0L
        while (true) {
            val n = r.read(buffer, offset, length)
            if (n > 0) {
                bytesTransferred(n)
                return n
            }
            if (!growing()) return C.RESULT_END_OF_INPUT
            try {
                Thread.sleep(40)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
            waited += 40
            if (waited > 30_000) throw IOException("Le flux en pause ne reçoit plus de données")
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { raf?.close() }
        raf = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
