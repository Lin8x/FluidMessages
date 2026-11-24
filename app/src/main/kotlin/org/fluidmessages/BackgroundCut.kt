package org.fluidmessages

import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Background removal on the phone, with no network and no Play services. It works on plain ARGB pixel arrays,
 * so it is tested on the JVM; MediaEditor does the Bitmap side.
 *
 * How it works: colors are compared in Oklab, where distance tracks how different two colors look. The colors
 * along the picture's edge are grouped into a few background colors (groups that only touch one stretch of the
 * edge, like a shirt at the bottom, aren't counted). The background then grows inward from the edge, and from
 * any spot you tap: a pixel joins when it looks like a background color, or when it is only a small step from
 * the background pixel next to it and still fairly close (so shaded walls and skies come along, but it stops
 * at the subject's outline). Small leftover specks are cleared, and the outline is feathered, with the
 * background's color taken back out of the soft edge so there's no light or dark halo.
 */
internal object BackgroundCut {
    /** The removed parts as alpha (0 = removed, 255 = kept), at [width] × [height], and the background colors found. */
    class Mask(val alpha: ByteArray, val width: Int, val height: Int, val background: IntArray) {
        /** How much of the picture was removed, 0 to 1. */
        val removed: Float get() {
            var sum = 0L
            for (value in alpha) sum += 255 - (value.toInt() and 0xFF)
            return sum / (255f * alpha.size.coerceAtLeast(1))
        }
    }

    private val linear = FloatArray(256) { index ->
        val c = index / 255f
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    /** sRGB to Oklab (Björn Ottosson's matrices). */
    fun oklab(rgb: Int, out: FloatArray, at: Int) {
        val r = linear[(rgb shr 16) and 0xFF]; val g = linear[(rgb shr 8) and 0xFF]; val b = linear[rgb and 0xFF]
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[at] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[at + 1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[at + 2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** The Oklab distance that counts as "the same color" for a tolerance from 0 to 1. */
    fun threshold(tolerance: Float) = 0.03f + tolerance.coerceIn(0f, 1f) * 0.17f

    /**
     * Finds the background of [pixels] (ARGB, [width] × [height]). [fromEdges] grows it from the picture's edge;
     * [taps] are extra (x, y) spots that are background. [softness] 0 to 1 feathers the outline by up to 4 pixels.
     * [progress] gets 0 to 1; [stop] is checked now and then so a newer request can take over.
     */
    fun find(pixels: IntArray, width: Int, height: Int, tolerance: Float, softness: Float = .35f, fromEdges: Boolean = true,
             taps: List<Pair<Int, Int>> = emptyList(), progress: (Float) -> Unit = {}, stop: () -> Boolean = { false }): Mask {
        val n = width * height
        require(n > 0 && pixels.size >= n)
        val limit = threshold(tolerance)
        val lab = FloatArray(n * 3)
        for (i in 0 until n) {
            oklab(pixels[i], lab, i * 3)
            if (i and 0xFFFF == 0) { progress(.25f * i / n); if (stop()) throw CancellationException() }
        }
        val clear = BooleanArray(n) { (pixels[it] ushr 24) < 128 }

        // Background colors: the edge's main color groups, plus the color around each tap.
        val palette = ArrayList<FloatArray>()
        if (fromEdges) palette += edgeColors(lab, clear, width, height, limit)
        val tapSeeds = ArrayList<Int>()
        for ((x, y) in taps) {
            if (x !in 0 until width || y !in 0 until height) continue
            val sum = FloatArray(3); var count = 0
            for (dy in -2..2) for (dx in -2..2) {
                val px = x + dx; val py = y + dy
                if (px !in 0 until width || py !in 0 until height) continue
                val at = (py * width + px) * 3
                sum[0] += lab[at]; sum[1] += lab[at + 1]; sum[2] += lab[at + 2]; count++
            }
            palette += floatArrayOf(sum[0] / count, sum[1] / count, sum[2] / count)
            tapSeeds += y * width + x
        }
        progress(.3f)

        // How far each pixel is from the nearest background color.
        val far = FloatArray(n)
        for (i in 0 until n) {
            var best = Float.MAX_VALUE
            val at = i * 3
            for (color in palette) {
                val dl = lab[at] - color[0]; val da = lab[at + 1] - color[1]; val db = lab[at + 2] - color[2]
                best = min(best, dl * dl + da * da + db * db)
            }
            far[i] = if (palette.isEmpty()) Float.MAX_VALUE else sqrt(best)
            if (i and 0xFFFF == 0) { progress(.3f + .2f * i / n); if (stop()) throw CancellationException() }
        }

        // Grow the background from the edge and the taps.
        val gone = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0; var tail = 0
        fun seed(i: Int) { if (!gone[i]) { gone[i] = true; queue[tail++] = i } }
        for (i in 0 until n) if (clear[i]) seed(i)
        if (fromEdges && palette.isNotEmpty()) {
            for (x in 0 until width) { if (far[x] < limit) seed(x); val b = (height - 1) * width + x; if (far[b] < limit) seed(b) }
            for (y in 0 until height) { val l = y * width; if (far[l] < limit) seed(l); val r = l + width - 1; if (far[r] < limit) seed(r) }
        }
        for (i in tapSeeds) seed(i)
        val step = limit * .3f
        val near = limit * 1.7f
        var visited = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            for (k in 0 until 4) {
                val j = when (k) {
                    0 -> if (x > 0) i - 1 else -1
                    1 -> if (x < width - 1) i + 1 else -1
                    2 -> if (y > 0) i - width else -1
                    else -> if (y < height - 1) i + width else -1
                }
                if (j < 0 || gone[j]) continue
                val join = clear[j] || far[j] < limit || far[j] < near && distance(lab, i, j) < step
                if (join) { gone[j] = true; queue[tail++] = j }
            }
            if (++visited and 0xFFFF == 0) { progress(.5f + .25f * tail / n); if (stop()) throw CancellationException() }
        }
        progress(.75f)

        // Specks of "kept" left in the background (noise, dust, texture) go too.
        clearSpecks(gone, width, height, max(6, n / 2500), queue)
        if (stop()) throw CancellationException()
        progress(.85f)

        val alpha = feather(gone, far, width, height, softness, limit)
        progress(1f)
        val colors = IntArray(palette.size) { fromOklab(palette[it]) }
        return Mask(alpha, width, height, colors)
    }

    private fun distance(lab: FloatArray, i: Int, j: Int): Float {
        val a = i * 3; val b = j * 3
        val dl = lab[a] - lab[b]; val da = lab[a + 1] - lab[b + 1]; val db = lab[a + 2] - lab[b + 2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /**
     * The edge's background colors: edge pixels are grouped by color (k-means), and a group counts when it is
     * the biggest, or runs along at least three sides, or covers much of the top (photos usually have the
     * subject at the bottom, touching the edge).
     */
    internal fun edgeColors(lab: FloatArray, clear: BooleanArray, width: Int, height: Int, limit: Float): List<FloatArray> {
        val samples = ArrayList<Int>()
        val sides = ArrayList<Int>()
        val stride = max(1, (width + height) / 600)
        fun add(i: Int, side: Int) { if (!clear[i]) { samples += i; sides += side } }
        for (x in 0 until width step stride) { add(x, 0); add((height - 1) * width + x, 2) }
        for (y in 0 until height step stride) { add(y * width, 3); add(y * width + width - 1, 1) }
        if (samples.isEmpty()) return emptyList()
        val k = min(5, samples.size)
        // Start from colors far apart, so a small but distinct group gets its own center.
        val centers = ArrayList<FloatArray>()
        centers += lab.copyOfRange(samples[0] * 3, samples[0] * 3 + 3)
        while (centers.size < k) {
            var bestIndex = -1; var bestDistance = -1f
            for (s in samples) {
                val d = centers.minOf { c -> labDistance(lab, s, c) }
                if (d > bestDistance) { bestDistance = d; bestIndex = s }
            }
            if (bestDistance < limit * .5f) break
            centers += lab.copyOfRange(bestIndex * 3, bestIndex * 3 + 3)
        }
        val owner = IntArray(samples.size)
        repeat(8) {
            val sums = Array(centers.size) { FloatArray(4) }
            for ((index, s) in samples.withIndex()) {
                var best = 0; var bestDistance = Float.MAX_VALUE
                for ((c, center) in centers.withIndex()) { val d = labDistance(lab, s, center); if (d < bestDistance) { bestDistance = d; best = c } }
                owner[index] = best
                val sum = sums[best]
                sum[0] += lab[s * 3]; sum[1] += lab[s * 3 + 1]; sum[2] += lab[s * 3 + 2]; sum[3] += 1f
            }
            for ((c, sum) in sums.withIndex()) if (sum[3] > 0) centers[c] = floatArrayOf(sum[0] / sum[3], sum[1] / sum[3], sum[2] / sum[3])
        }
        val total = IntArray(centers.size)
        val perSide = Array(centers.size) { IntArray(4) }
        val sideTotal = IntArray(4)
        for ((index, c) in owner.withIndex()) { total[c]++; perSide[c][sides[index]]++; sideTotal[sides[index]]++ }
        val biggest = total.indices.maxBy { total[it] }
        return centers.filterIndexed { c, _ ->
            val share = total[c].toFloat() / samples.size
            val along = (0 until 4).count { side -> sideTotal[side] > 0 && perSide[c][side] >= sideTotal[side] * .15f }
            val top = if (sideTotal[0] > 0) perSide[c][0].toFloat() / sideTotal[0] else 0f
            c == biggest || share >= .08f && (along >= 3 || top >= .25f)
        }
    }

    private fun labDistance(lab: FloatArray, i: Int, color: FloatArray): Float {
        val dl = lab[i * 3] - color[0]; val da = lab[i * 3 + 1] - color[1]; val db = lab[i * 3 + 2] - color[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Kept areas smaller than [least] pixels, surrounded by removed ones, are removed too. */
    internal fun clearSpecks(gone: BooleanArray, width: Int, height: Int, least: Int, queue: IntArray) {
        val n = width * height
        val seen = BooleanArray(n)
        for (start in 0 until n) {
            if (gone[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val i = queue[head++]
                val x = i % width; val y = i / width
                if (x > 0 && !gone[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                if (x < width - 1 && !gone[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                if (y > 0 && !gone[i - width] && !seen[i - width]) { seen[i - width] = true; queue[tail++] = i - width }
                if (y < height - 1 && !gone[i + width] && !seen[i + width]) { seen[i + width] = true; queue[tail++] = i + width }
            }
            if (tail < least) for (index in 0 until tail) gone[queue[index]] = true
        }
    }

    /**
     * Soft alpha from the hard mask: a box blur of up to 4 pixels, pulled slightly inward so no background is
     * brought back, and edge pixels that still look like the background are faded by how close they are.
     */
    internal fun feather(gone: BooleanArray, far: FloatArray, width: Int, height: Int, softness: Float, limit: Float): ByteArray {
        val n = width * height
        val radius = (softness.coerceIn(0f, 1f) * 4f).toInt()
        val out = ByteArray(n)
        if (radius == 0) { for (i in 0 until n) out[i] = (if (gone[i]) 0 else 255).toByte(); return out }
        // Separable box blur of the kept area, as fractions. The picture's own edge is extended outward, so a
        // subject running off the side keeps a hard edge there.
        val horizontal = FloatArray(n)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0
                for (dx in -radius..radius) if (!gone[row + (x + dx).coerceIn(0, width - 1)]) sum++
                horizontal[row + x] = sum.toFloat()
            }
        }
        val span = (2 * radius + 1).toFloat()
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0f
            for (dy in -radius..radius) sum += horizontal[(y + dy).coerceIn(0, height - 1) * width + x]
            val i = y * width + x
            var a = (sum / (span * span) - .35f) / .65f
            if (a < 1f) {
                // On the outline: fade what still looks like background.
                val look = ((far[i] - limit * .6f) / (limit * .9f)).coerceIn(0f, 1f)
                a = min(a, look * look * (3 - 2 * look) + if (gone[i]) 0f else .25f)
            }
            out[i] = (a.coerceIn(0f, 1f) * 255f + .5f).toInt().toByte()
        }
        return out
    }

    /**
     * Takes the background's color back out of soft edge pixels of [pixels] (full size), so a subject cut from
     * a white wall has no white rim. [mask] may be smaller; it is sampled smoothly.
     */
    fun decontaminate(pixels: IntArray, width: Int, height: Int, mask: Mask) {
        if (mask.background.isEmpty()) return
        val sx = mask.width.toFloat() / width
        val sy = mask.height.toFloat() / height
        for (y in 0 until height) for (x in 0 until width) {
            val a = sample(mask, (x + .5f) * sx - .5f, (y + .5f) * sy - .5f)
            if (a <= .03f || a >= .97f) continue
            val i = y * width + x
            val c = pixels[i]
            val bg = mask.background.minBy { rgbDistance(it, c) }
            fun unmix(shift: Int): Int {
                val value = (c shr shift) and 0xFF
                val back = (bg shr shift) and 0xFF
                return ((value - (1 - a) * back) / a).toInt().coerceIn(0, 255)
            }
            pixels[i] = (c and -0x1000000) or (unmix(16) shl 16) or (unmix(8) shl 8) or unmix(0)
        }
    }

    /** The mask's alpha at a point, 0 to 1, blended between the four nearest pixels. */
    fun sample(mask: Mask, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, mask.width - 1f); val cy = y.coerceIn(0f, mask.height - 1f)
        val x0 = cx.toInt(); val y0 = cy.toInt()
        val x1 = min(x0 + 1, mask.width - 1); val y1 = min(y0 + 1, mask.height - 1)
        val fx = cx - x0; val fy = cy - y0
        fun at(px: Int, py: Int) = (mask.alpha[py * mask.width + px].toInt() and 0xFF) / 255f
        return (at(x0, y0) * (1 - fx) + at(x1, y0) * fx) * (1 - fy) + (at(x0, y1) * (1 - fx) + at(x1, y1) * fx) * fy
    }

    private fun rgbDistance(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    /** Oklab back to an opaque sRGB color. */
    fun fromOklab(lab: FloatArray): Int {
        val l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
        val m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
        val s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
        val l3 = l * l * l; val m3 = m * m * m; val s3 = s * s * s
        fun encode(v: Float): Int {
            val c = v.coerceIn(0f, 1f)
            val e = if (c <= 0.0031308f) 12.92f * c else 1.055f * Math.pow(c.toDouble(), 1 / 2.4).toFloat() - 0.055f
            return (e * 255f + .5f).toInt().coerceIn(0, 255)
        }
        val r = encode(4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3)
        val g = encode(-1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3)
        val b = encode(-0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** The smallest box (left, top, right, bottom; right and bottom exclusive) holding every pixel at least [least] opaque, or null. */
    fun opaqueBounds(pixels: IntArray, width: Int, height: Int, least: Int = 16): IntArray? {
        var left = width; var top = height; var right = -1; var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if ((pixels[y * width + x] ushr 24) >= least) {
                if (x < left) left = x; if (x > right) right = x
                if (y < top) top = y; if (y > bottom) bottom = y
            }
        }
        return if (right < 0) null else intArrayOf(left, top, right + 1, bottom + 1)
    }

    class CancellationException : RuntimeException()
}
