package dev.devstudio.ide.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * TreeOcr — screenshot → text lines → TreeReader.
 *
 * Two things make this work on real phone screenshots:
 *
 *  1. **Region decoding.** A share/screenshot of a whole project tree can be tens
 *     of thousands of pixels tall (the one this was built from is 720 × 46,063 — a
 *     single decoded bitmap is ~130 MB and is killed instantly). BitmapRegionDecoder
 *     cuts the image into vertical strips, OCR runs on one strip at a time, with a
 *     small overlap so a line is never severed, and the overlapping line is dropped.
 *  2. **Pixel positions.** ML Kit reports a bounding box per line. TreeReader uses
 *     the left edge as a second depth signal instead of trusting │ to survive OCR.
 *
 * Everything runs on-device (bundled model, no Play Services download, no network).
 */
object TreeOcr {

    private const val TILE = 2600          // source px per OCR pass
    private const val OVERLAP = 64         // px shared between consecutive tiles

    private val main = Handler(Looper.getMainLooper())

    data class Progress(val done: Int, val total: Int)

    /**
     * Reads [uri] on a worker thread and calls [onResult] on the main thread with
     * either a parsed tree or a human reason it could not be read.
     */
    fun read(
        ctx: Context,
        uri: Uri,
        onProgress: (Progress) -> Unit = {},
        onResult: (TreeReader.Result?, String?) -> Unit
    ) {
        Thread {
            val out = runCatching { decode(ctx, uri, onProgress) }
            val res = out.getOrNull()
            val err = out.exceptionOrNull()?.let { it.message ?: "could not read that image" }
            main.post {
                if (res != null) onResult(res, if (res.ok) null else res.reason)
                else onResult(null, err ?: "could not read that image")
            }
        }.start()
    }

    /**
     * Reads **several** screenshots of one capture.
     *
     * A long file does not fit on one screen, so people take two or three shots that
     * overlap. Each is decoded on the same worker thread, then the raw texts are
     * joined by their shared lines ([OcrShape.stitchAll]) before parsing — otherwise
     * the seam lines show up twice and the tree comes back with phantom entries.
     * The returned [TreeReader.Result.rawText] is the stitched text.
     */
    fun readMany(
        ctx: Context,
        uris: List<Uri>,
        onProgress: (Progress) -> Unit = {},
        onResult: (TreeReader.Result?, String?) -> Unit
    ) {
        if (uris.isEmpty()) { onResult(null, "no image was picked"); return }
        Thread {
            val out = runCatching {
                var stitched = ""
                var shots = 0
                for ((i, uri) in uris.withIndex()) {
                    val one = decode(ctx, uri) { p ->
                        // report the whole pick, not each image as if it were the first
                        onProgress(Progress(i * 100 + p.done, uris.size * 100))
                    }
                    stitched = OcrShape.stitchAll(listOf(stitched, one.rawText))
                    shots++
                }
                val joined = OcrShape.block(stitched)
                val parsed = TreeReader.parse(joined.text)
                parsed.copy(
                    rawText = joined.text,
                    report = joined,
                    shots = shots
                )
            }
            val res = out.getOrNull()
            val err = out.exceptionOrNull()?.let { it.message ?: "could not read that image" }
            main.post {
                if (res != null) onResult(res, if (res.ok) null else res.reason)
                else onResult(null, err ?: "could not read that image")
            }
        }.start()
    }

    private fun decode(ctx: Context, uri: Uri, onProgress: (Progress) -> Unit): TreeReader.Result {
        val cr = ctx.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        val w = bounds.outWidth
        val h = bounds.outHeight
        require(w > 0 && h > 0) { "that file is not a readable image" }

        val decoder = openDecoder(cr.openFileDescriptor(uri, "r")?.fileDescriptor)
            ?: cr.openInputStream(uri)?.use { BitmapRegionDecoder.newInstance(it, false) }
            ?: throw IllegalStateException("that image could not be opened")

        val sample = if (w > 2000) 2 else 1          // keep text sharp, keep memory low
        val tiles = ((h + TILE - 1) / TILE).coerceAtLeast(1)
        val lines = ArrayList<TreeReader.OcrLine>()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        try {
            for (t in 0 until tiles) {
                val top = t * TILE
                val bottom = (top + TILE + OVERLAP).coerceAtMost(h)
                if (bottom <= top) continue
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val tile = runCatching {
                    decoder.decodeRegion(Rect(0, top, w, bottom), opts)
                }.getOrNull() ?: continue

                val before = lines.size
                val latch = java.util.concurrent.CountDownLatch(1)
                var failure: String? = null
                recognizer.process(InputImage.fromBitmap(tile, 0))
                    .addOnSuccessListener { text ->
                        // reading order, top to bottom, so depth stays monotonic
                        val fresh = text.textBlocks.flatMap { it.lines }
                            .sortedBy { it.boundingBox?.top ?: 0 }
                        fresh.forEachIndexed { i, line ->
                            val body = line.text.trim()
                            if (body.isEmpty()) return@forEachIndexed
                            val dup = before > 0 && i == 0 && lines.lastOrNull()?.text == body
                            if (!dup) lines.add(TreeReader.OcrLine(body, line.boundingBox?.left?.toFloat() ?: -1f))
                        }
                        latch.countDown()
                    }
                    .addOnFailureListener { e ->
                        failure = e.message ?: "OCR failed on one part of the image"
                        latch.countDown()
                    }
                latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
                tile.recycle()
                if (failure != null) throw IllegalStateException(failure)
                onProgress(Progress(t + 1, tiles))
            }
        } finally {
            runCatching { recognizer.close() }
            runCatching { decoder.recycle() }
        }
        return TreeReader.parse(lines)
    }

    /** Region decoding straight from the file descriptor avoids re-reading the file per tile. */
    private fun openDecoder(fd: java.io.FileDescriptor?): BitmapRegionDecoder? =
        if (fd == null) null
        else runCatching { BitmapRegionDecoder.newInstance(fd, false) }.getOrNull()
}
