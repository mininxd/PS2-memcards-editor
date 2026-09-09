package com.armsx2.memcards.core

/**
 * Pure Kotlin implementation of Haruhiko Okumura's LZARI data compression algorithm.
 * Used by PlayStation 2 Action Replay MAX (.max / MAX Drive) game save archives.
 */
object Ps2Lzari {

    private const val N = 4096 // Ring buffer size
    private const val F = 60 // Upper limit for match length
    private const val THRESHOLD = 2 // Min match length threshold
    private const val NIL = N
    private const val M = 15
    private const val Q1 = 1L shl M // 32768
    private const val Q2 = 2L * Q1 // 65536
    private const val Q3 = 3L * Q1 // 98304
    private const val Q4 = 4L * Q1 // 131072
    private const val MAX_CUM = (Q1 - 1L).toInt() // 32767
    private const val N_CHAR = 256 - THRESHOLD + F // 314

    /**
     * Decompresses an LZARI-compressed byte array into its original uncompressed form.
     */
    fun decompress(compressed: ByteArray, uncompressedSize: Int): ByteArray {
        if (uncompressedSize <= 0) return ByteArray(0)
        val decoder = Decoder(compressed, uncompressedSize)
        return decoder.decode()
    }

    /**
     * Compresses a byte array using the LZARI algorithm.
     */
    fun compress(input: ByteArray): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val encoder = Encoder(input)
        return encoder.encode()
    }

    private class Decoder(
        private val inData: ByteArray,
        private val uncompressedSize: Int
    ) {
        private var inPos = 0
        private var bufferGetBit = 0
        private var maskGetBit = 0

        private var low = 0L
        private var high = Q4
        private var value = 0L

        private val symCum = IntArray(N_CHAR + 1)
        private val charToSym = IntArray(N_CHAR)
        private val symToChar = IntArray(N_CHAR + 1)
        private val symFreq = IntArray(N_CHAR + 1)
        private val positionCum = IntArray(N + 1)
        private val textBuf = ByteArray(N)

        private fun getBit(): Int {
            maskGetBit = maskGetBit ushr 1
            if (maskGetBit == 0) {
                bufferGetBit = if (inPos < inData.size) inData[inPos++].toInt() and 0xFF else 0
                maskGetBit = 128
            }
            return if ((bufferGetBit and maskGetBit) != 0) 1 else 0
        }

        private fun startModel() {
            symCum[N_CHAR] = 0
            for (sym in N_CHAR downTo 1) {
                val ch = sym - 1
                charToSym[ch] = sym
                symToChar[sym] = ch
                symFreq[sym] = 1
                symCum[sym - 1] = symCum[sym] + symFreq[sym]
            }
            symFreq[0] = 0
            positionCum[N] = 0
            for (i in N downTo 1) {
                positionCum[i - 1] = positionCum[i] + 10000 / (i + 200)
            }
        }

        private fun updateModel(sym: Int) {
            if (symCum[0] >= MAX_CUM) {
                var c = 0
                for (i in N_CHAR downTo 1) {
                    symCum[i] = c
                    symFreq[i] = (symFreq[i] + 1) ushr 1
                    c += symFreq[i]
                }
                symCum[0] = c
            }
            var i = sym
            while (symFreq[i] == symFreq[i - 1]) {
                i--
            }
            if (i < sym) {
                val chI = symToChar[i]
                val chSym = symToChar[sym]
                symToChar[i] = chSym
                symToChar[sym] = chI
                charToSym[chI] = sym
                charToSym[chSym] = i
            }
            symFreq[i]++
            while (--i >= 0) {
                symCum[i]++
            }
        }

        private fun binarySearchSym(x: Long): Int {
            var i = 1
            var j = N_CHAR
            while (i < j) {
                val k = (i + j) / 2
                if (symCum[k].toLong() > x) {
                    i = k + 1
                } else {
                    j = k
                }
            }
            return i
        }

        private fun binarySearchPos(x: Long): Int {
            var i = 1
            var j = N
            while (i < j) {
                val k = (i + j) / 2
                if (positionCum[k].toLong() > x) {
                    i = k + 1
                } else {
                    j = k
                }
            }
            return i - 1
        }

        private fun decodeChar(): Int {
            val range = high - low
            if (range <= 0L) return 0
            val x = (((value - low + 1L) * symCum[0].toLong() - 1L) / range)
            val sym = binarySearchSym(x)
            high = low + (range * symCum[sym - 1].toLong()) / symCum[0].toLong()
            low += (range * symCum[sym].toLong()) / symCum[0].toLong()
            while (true) {
                if (low >= Q2) {
                    value -= Q2
                    low -= Q2
                    high -= Q2
                } else if (low >= Q1 && high <= Q3) {
                    value -= Q1
                    low -= Q1
                    high -= Q1
                } else if (high > Q2) {
                    break
                }
                low += low
                high += high
                value = (value shl 1) or getBit().toLong()
            }
            val ch = symToChar[sym]
            updateModel(sym)
            return ch
        }

        private fun decodePosition(): Int {
            val range = high - low
            if (range <= 0L) return 0
            val x = (((value - low + 1L) * positionCum[0].toLong() - 1L) / range)
            val pos = binarySearchPos(x)
            high = low + (range * positionCum[pos].toLong()) / positionCum[0].toLong()
            low += (range * positionCum[pos + 1].toLong()) / positionCum[0].toLong()
            while (true) {
                if (low >= Q2) {
                    value -= Q2
                    low -= Q2
                    high -= Q2
                } else if (low >= Q1 && high <= Q3) {
                    value -= Q1
                    low -= Q1
                    high -= Q1
                } else if (high > Q2) {
                    break
                }
                low += low
                high += high
                value = (value shl 1) or getBit().toLong()
            }
            return pos
        }

        fun decode(): ByteArray {
            low = 0L
            high = Q4
            value = 0L
            for (i in 0 until M + 2) {
                value = (value shl 1) or getBit().toLong()
            }
            startModel()

            for (i in 0 until N - F) {
                textBuf[i] = ' '.code.toByte()
            }
            var r = N - F

            val out = ByteArray(uncompressedSize)
            var count = 0

            while (count < uncompressedSize) {
                val c = decodeChar()
                if (c < 256) {
                    val b = c.toByte()
                    out[count++] = b
                    textBuf[r] = b
                    r = (r + 1) and (N - 1)
                } else {
                    val pos = (r - decodePosition() - 1) and (N - 1)
                    val matchLen = c - 255 + THRESHOLD
                    for (k in 0 until matchLen) {
                        if (count < uncompressedSize) {
                            val b = textBuf[(pos + k) and (N - 1)]
                            out[count++] = b
                            textBuf[r] = b
                            r = (r + 1) and (N - 1)
                        }
                    }
                }
            }
            return out
        }
    }

    private class Encoder(private val inData: ByteArray) {
        private val out = java.io.ByteArrayOutputStream()
        private var bufferPutBit = 0
        private var maskPutBit = 128
        private var low = 0L
        private var high = Q4
        private var shifts = 0

        private val symCum = IntArray(N_CHAR + 1)
        private val charToSym = IntArray(N_CHAR)
        private val symToChar = IntArray(N_CHAR + 1)
        private val symFreq = IntArray(N_CHAR + 1)
        private val positionCum = IntArray(N + 1)
        private val textBuf = ByteArray(N + F - 1)

        private val lson = IntArray(N + 1)
        private val rson = IntArray(N + 257)
        private val dad = IntArray(N + 1)
        private var matchPosition = 0
        private var matchLength = 0

        private fun putBit(bit: Int) {
            if (bit != 0) bufferPutBit = bufferPutBit or maskPutBit
            maskPutBit = maskPutBit ushr 1
            if (maskPutBit == 0) {
                out.write(bufferPutBit)
                bufferPutBit = 0
                maskPutBit = 128
            }
        }

        private fun flushBitBuffer() {
            for (i in 0 until 7) putBit(0)
        }

        private fun output(bit: Int) {
            putBit(bit)
            while (shifts > 0) {
                putBit(if (bit == 0) 1 else 0)
                shifts--
            }
        }

        private fun startModel() {
            symCum[N_CHAR] = 0
            for (sym in N_CHAR downTo 1) {
                val ch = sym - 1
                charToSym[ch] = sym
                symToChar[sym] = ch
                symFreq[sym] = 1
                symCum[sym - 1] = symCum[sym] + symFreq[sym]
            }
            symFreq[0] = 0
            positionCum[N] = 0
            for (i in N downTo 1) {
                positionCum[i - 1] = positionCum[i] + 10000 / (i + 200)
            }
        }

        private fun updateModel(sym: Int) {
            if (symCum[0] >= MAX_CUM) {
                var c = 0
                for (i in N_CHAR downTo 1) {
                    symCum[i] = c
                    symFreq[i] = (symFreq[i] + 1) ushr 1
                    c += symFreq[i]
                }
                symCum[0] = c
            }
            var i = sym
            while (symFreq[i] == symFreq[i - 1]) i--
            if (i < sym) {
                val chI = symToChar[i]
                val chSym = symToChar[sym]
                symToChar[i] = chSym
                symToChar[sym] = chI
                charToSym[chI] = sym
                charToSym[chSym] = i
            }
            symFreq[i]++
            while (--i >= 0) symCum[i]++
        }

        private fun encodeChar(ch: Int) {
            val sym = charToSym[ch]
            val range = high - low
            high = low + (range * symCum[sym - 1].toLong()) / symCum[0].toLong()
            low += (range * symCum[sym].toLong()) / symCum[0].toLong()
            while (true) {
                if (high <= Q2) {
                    output(0)
                } else if (low >= Q2) {
                    output(1)
                    low -= Q2
                    high -= Q2
                } else if (low >= Q1 && high <= Q3) {
                    shifts++
                    low -= Q1
                    high -= Q1
                } else {
                    break
                }
                low += low
                high += high
            }
            updateModel(sym)
        }

        private fun encodePosition(position: Int) {
            val range = high - low
            high = low + (range * positionCum[position].toLong()) / positionCum[0].toLong()
            low += (range * positionCum[position + 1].toLong()) / positionCum[0].toLong()
            while (true) {
                if (high <= Q2) {
                    output(0)
                } else if (low >= Q2) {
                    output(1)
                    low -= Q2
                    high -= Q2
                } else if (low >= Q1 && high <= Q3) {
                    shifts++
                    low -= Q1
                    high -= Q1
                } else {
                    break
                }
                low += low
                high += high
            }
        }

        private fun encodeEnd() {
            shifts++
            if (low < Q1) output(0) else output(1)
            flushBitBuffer()
        }

        private fun initTree() {
            for (i in N + 1..N + 256) rson[i] = NIL
            for (i in 0 until N) dad[i] = NIL
        }

        private fun insertNode(r: Int) {
            val key0 = textBuf[r].toInt() and 0xFF
            var p = N + 1 + key0
            rson[r] = NIL
            lson[r] = NIL
            matchLength = 0
            var cmp = 1

            while (true) {
                if (cmp >= 0) {
                    if (rson[p] != NIL) p = rson[p]
                    else {
                        rson[p] = r
                        dad[r] = p
                        return
                    }
                } else {
                    if (lson[p] != NIL) p = lson[p]
                    else {
                        lson[p] = r
                        dad[r] = p
                        return
                    }
                }
                var i = 1
                while (i < F) {
                    val kVal = textBuf[r + i].toInt() and 0xFF
                    val pVal = textBuf[p + i].toInt() and 0xFF
                    cmp = kVal - pVal
                    if (cmp != 0) break
                    i++
                }
                if (i > THRESHOLD) {
                    if (i > matchLength) {
                        matchPosition = (r - p) and (N - 1)
                        matchLength = i
                        if (matchLength >= F) break
                    } else if (i == matchLength) {
                        val temp = (r - p) and (N - 1)
                        if (temp < matchPosition) matchPosition = temp
                    }
                }
            }
            dad[r] = dad[p]
            lson[r] = lson[p]
            rson[r] = rson[p]
            dad[lson[p]] = r
            dad[rson[p]] = r
            if (rson[dad[p]] == p) rson[dad[p]] = r
            else lson[dad[p]] = r
            dad[p] = NIL
        }

        private fun deleteNode(p: Int) {
            if (dad[p] == NIL) return
            val q: Int
            if (rson[p] == NIL) {
                q = lson[p]
            } else if (lson[p] == NIL) {
                q = rson[p]
            } else {
                var cur = lson[p]
                if (rson[cur] != NIL) {
                    do {
                        cur = rson[cur]
                    } while (rson[cur] != NIL)
                    rson[dad[cur]] = lson[cur]
                    dad[lson[cur]] = dad[cur]
                    lson[cur] = lson[p]
                    dad[lson[p]] = cur
                }
                rson[cur] = rson[p]
                dad[rson[p]] = cur
                q = cur
            }
            dad[q] = dad[p]
            if (rson[dad[p]] == p) rson[dad[p]] = q
            else lson[dad[p]] = q
            dad[p] = NIL
        }

        fun encode(): ByteArray {
            startModel()
            initTree()

            var s = 0
            var r = N - F
            for (i in s until r) textBuf[i] = ' '.code.toByte()
            var inCur = 0
            var len = 0
            while (len < F && inCur < inData.size) {
                textBuf[r + len] = inData[inCur++]
                len++
            }

            for (i in 1..F) insertNode(r - i)
            insertNode(r)

            do {
                if (matchLength > len) matchLength = len
                if (matchLength <= THRESHOLD) {
                    matchLength = 1
                    encodeChar(textBuf[r].toInt() and 0xFF)
                } else {
                    encodeChar(255 - THRESHOLD + matchLength)
                    encodePosition(matchPosition - 1)
                }
                val lastMatchLength = matchLength
                var i = 0
                while (i < lastMatchLength && inCur < inData.size) {
                    deleteNode(s)
                    val c = inData[inCur++]
                    textBuf[s] = c
                    if (s < F - 1) textBuf[s + N] = c
                    s = (s + 1) and (N - 1)
                    r = (r + 1) and (N - 1)
                    insertNode(r)
                    i++
                }
                while (i++ < lastMatchLength) {
                    deleteNode(s)
                    s = (s + 1) and (N - 1)
                    r = (r + 1) and (N - 1)
                    if (--len != 0) insertNode(r)
                }
            } while (len > 0)

            encodeEnd()
            return out.toByteArray()
        }
    }
}
