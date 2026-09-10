package xyz.mininxd.ps2memcards.core

import android.graphics.Bitmap
import android.util.LruCache
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decodes PS2 3D save icon files (.icn) and PlayStation 1 save headers,
 * rendering 3D polygonal save icons as 2D static image previews with
 * software rasterization, depth buffering, and in-memory LRU caching.
 */
object Ps2IconDecoder {

    private const val ICN_MAGIC = 0x00010000
    private const val TEX_WIDTH = 128
    private const val TEX_HEIGHT = 128
    private const val TEX_PIXEL_COUNT = TEX_WIDTH * TEX_HEIGHT

    // In-memory LRU cache holding up to 128 rendered icon bitmaps
    private val iconCache = LruCache<String, Bitmap>(128)

    /**
     * Retrieves an icon bitmap directly from cache if present.
     */
    fun getCached(cacheKey: String): Bitmap? {
        if (cacheKey.isBlank()) return null
        return synchronized(iconCache) {
            iconCache.get(cacheKey)
        }
    }

    /**
     * Removes an entry or all entries matching a key/prefix from the LRU cache.
     */
    fun invalidate(keyPrefix: String) {
        if (keyPrefix.isBlank()) return
        synchronized(iconCache) {
            val matchingKeys = iconCache.snapshot().keys.filter { it.contains(keyPrefix) }
            for (k in matchingKeys) {
                iconCache.remove(k)
            }
        }
    }

    /**
     * Clears the entire icon cache.
     */
    fun clearCache() {
        synchronized(iconCache) {
            iconCache.evictAll()
        }
    }

    /**
     * Decodes and renders a PS2 3D save icon (.icn) into a 2D static Bitmap.
     * Renders shape 0 at a standard PS2 BIOS isometric viewing angle with
     * 3-point lighting and texture mapping, then stores it in the LRU cache.
     */
    fun decodePs2Icon(
        icnData: ByteArray,
        iconSys: Ps2IconSys? = null,
        cacheKey: String = ""
    ): Bitmap? {
        if (cacheKey.isNotBlank()) {
            getCached(cacheKey)?.let { return it }
        }
        if (icnData.size < 20) return null

        val renderedBitmap = try {
            render3dIcon(icnData, iconSys)
        } catch (_: Throwable) {
            null
        } ?: decodeTexture(icnData)

        if (renderedBitmap != null && cacheKey.isNotBlank()) {
            synchronized(iconCache) {
                iconCache.put(cacheKey, renderedBitmap)
            }
        }
        return renderedBitmap
    }

    /**
     * Decodes a PlayStation 1 (PSX) save icon (16x16 CLUT4) into an upscaled
     * 64x64 crisp 2D bitmap, caching the result.
     */
    fun decodePs1Icon(data: ByteArray, cacheKey: String = ""): Bitmap? {
        if (cacheKey.isNotBlank()) {
            getCached(cacheKey)?.let { return it }
        }
        if (data.size < 256) return null
        // Header magic "SC"
        if (data[0] != 0x53.toByte() || data[1] != 0x43.toByte()) return null

        // PS1 CLUT: 16 entries * 2 bytes at offset 0x60 (96)
        val clut = IntArray(16)
        val clutOffset = 0x60
        for (i in 0 until 16) {
            val o = clutOffset + (i * 2)
            val raw = ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o].toInt() and 0xFF)
            val r = ((raw and 0x1F) * 255) / 31
            val g = (((raw shr 5) and 0x1F) * 255) / 31
            val b = (((raw shr 10) and 0x1F) * 255) / 31
            val a = if (i == 0 && (raw and 0x8000) == 0) 0 else 0xFF
            clut[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        // 16x16 4-bit pixel data at offset 0x80 (128 bytes)
        val iconOffset = 0x80
        val scale = 4
        val outWidth = 16 * scale
        val outHeight = 16 * scale
        val outPixels = IntArray(outWidth * outHeight)

        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val bytePos = iconOffset + (y * 8) + (x / 2)
                if (bytePos >= data.size) break
                val byteVal = data[bytePos].toInt() and 0xFF
                val colorIdx = if (x % 2 == 0) (byteVal and 0x0F) else ((byteVal shr 4) and 0x0F)
                val color = clut[colorIdx]

                for (sy in 0 until scale) {
                    val row = (y * scale + sy) * outWidth
                    for (sx in 0 until scale) {
                        outPixels[row + (x * scale + sx)] = color
                    }
                }
            }
        }

        return try {
            val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(outPixels, 0, outWidth, 0, 0, outWidth, outHeight)
            if (cacheKey.isNotBlank()) {
                synchronized(iconCache) {
                    iconCache.put(cacheKey, bitmap)
                }
            }
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extracts the Shift-JIS game title from a PlayStation 1 save file header.
     */
    fun extractPs1Title(data: ByteArray): String? {
        if (data.size < 68) return null
        if (data[0] != 0x53.toByte() || data[1] != 0x43.toByte()) return null
        return try {
            val title = Ps2ShiftJis.decode(data, 4, 64).trim()
            title.ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Decodes the raw 128x128 texture atlas from a PS2 .icn file.
     */
    fun decodeTexture(icnData: ByteArray, cacheKey: String = ""): Bitmap? {
        if (cacheKey.isNotBlank()) {
            getCached(cacheKey)?.let { return it }
        }
        if (icnData.size < 32) return null
        val buf = ByteBuffer.wrap(icnData).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.int
        if (magic != ICN_MAGIC && magic != 0x010000) {
            return null
        }

        val animShapes = buf.int
        val texFlags = buf.int
        buf.int // reserved
        val vertexCount = buf.int

        val pixels = IntArray(TEX_PIXEL_COUNT)
        var decoded = false

        // Calculate offset to texture data past vertex and animation data
        val stride = 8 * animShapes + 16
        var texOffset = 20 + (vertexCount * stride)

        if (icnData.size >= texOffset + 20) {
            buf.position(texOffset)
            val animIdTag = buf.int
            buf.int // frameLength
            buf.float // animSpeed
            buf.int // playOffset
            val frameCount = buf.int
            texOffset += 20
            if (animIdTag == 0x01 && frameCount in 1..10000) {
                for (f in 0 until frameCount) {
                    if (texOffset + 8 > icnData.size) break
                    buf.position(texOffset)
                    buf.int // shapeId
                    val rawKeyCount = buf.int
                    val keyCount = if (rawKeyCount > 0) rawKeyCount - 1 else 0
                    texOffset += 16 + keyCount * 8
                }
            }
        }

        val isCompressed = (texFlags and 0x08) != 0

        if (texOffset < icnData.size) {
            if (isCompressed && texOffset + 4 <= icnData.size) {
                buf.position(texOffset)
                val compressedSize = buf.int
                texOffset += 4
                val rleEnd = minOf(texOffset + compressedSize, icnData.size)
                decoded = decodeRleTexture(icnData, texOffset, rleEnd, pixels)
            } else if (!isCompressed && texOffset + (TEX_PIXEL_COUNT * 2) <= icnData.size) {
                buf.position(texOffset)
                for (i in 0 until TEX_PIXEL_COUNT) {
                    val rawPixel = buf.short.toInt() and 0xFFFF
                    pixels[i] = rgb1555ToArgb8888(rawPixel)
                }
                decoded = true
            }
        }

        if (!decoded) {
            // Fallback scan: check if there's an uncompressed texture block at the end
            val texSize = TEX_PIXEL_COUNT * 2
            if (icnData.size >= texSize) {
                val fallbackOffset = icnData.size - texSize
                buf.position(fallbackOffset)
                for (i in 0 until TEX_PIXEL_COUNT) {
                    val rawPixel = buf.short.toInt() and 0xFFFF
                    pixels[i] = rgb1555ToArgb8888(rawPixel)
                }
                decoded = true
            }
        }

        if (!decoded) return null

        return try {
            val bitmap = Bitmap.createBitmap(TEX_WIDTH, TEX_HEIGHT, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, TEX_WIDTH, 0, 0, TEX_WIDTH, TEX_HEIGHT)
            if (cacheKey.isNotBlank()) {
                synchronized(iconCache) {
                    iconCache.put(cacheKey, bitmap)
                }
            }
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun render3dIcon(icnData: ByteArray, iconSys: Ps2IconSys?): Bitmap? {
        if (icnData.size < 32) return null
        val buf = ByteBuffer.wrap(icnData).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.int
        if (magic != ICN_MAGIC && magic != 0x010000) return null

        val animShapes = buf.int
        val texFlags = buf.int
        buf.int // reserved
        val vertexCount = buf.int

        if (vertexCount <= 0 || vertexCount % 3 != 0 || animShapes < 1) return null

        val stride = 8 * animShapes + 16
        val totalVertexBytes = vertexCount * stride
        if (icnData.size < 20 + totalVertexBytes) return null

        // 1. Read vertices, normals, UVs, and colors
        val posX = FloatArray(vertexCount)
        val posY = FloatArray(vertexCount)
        val posZ = FloatArray(vertexCount)
        val normX = FloatArray(vertexCount)
        val normY = FloatArray(vertexCount)
        val normZ = FloatArray(vertexCount)
        val uvU = FloatArray(vertexCount)
        val uvV = FloatArray(vertexCount)
        val colR = FloatArray(vertexCount)
        val colG = FloatArray(vertexCount)
        val colB = FloatArray(vertexCount)

        var anyNonZeroColor = false

        for (i in 0 until vertexCount) {
            // Shape 0 position
            val x = buf.short.toInt()
            val y = buf.short.toInt()
            val z = buf.short.toInt()
            buf.short // pad

            // Skip additional animation shapes for this vertex
            if (animShapes > 1) {
                buf.position(buf.position() + (animShapes - 1) * 8)
            }

            // Normal
            val nx = buf.short.toInt()
            val ny = buf.short.toInt()
            val nz = buf.short.toInt()
            buf.short // pad

            // UVs
            val u = buf.short.toInt()
            val v = buf.short.toInt()

            // Colors
            val cr = buf.get().toInt() and 0xFF
            val cg = buf.get().toInt() and 0xFF
            val cb = buf.get().toInt() and 0xFF
            buf.get() // alpha

            if (cr > 0 || cg > 0 || cb > 0) {
                anyNonZeroColor = true
            }

            // Coordinates matching icon.vert: x, -y, -z divided by 4096.0f
            posX[i] = x / 4096.0f
            posY[i] = -y / 4096.0f
            posZ[i] = -z / 4096.0f

            normX[i] = nx / 4096.0f
            normY[i] = -ny / 4096.0f
            normZ[i] = -nz / 4096.0f

            uvU[i] = u / 4096.0f
            uvV[i] = v / 4096.0f

            colR[i] = (cr / 128.0f).coerceIn(0f, 1f)
            colG[i] = (cg / 128.0f).coerceIn(0f, 1f)
            colB[i] = (cb / 128.0f).coerceIn(0f, 1f)
        }

        if (!anyNonZeroColor) {
            for (i in 0 until vertexCount) {
                colR[i] = 1.0f
                colG[i] = 1.0f
                colB[i] = 1.0f
            }
        }

        // 2. Decode texture
        val texPixels = IntArray(TEX_PIXEL_COUNT)
        var hasValidTexture = false

        // Calculate texture offset past animation section
        var texOffset = 20 + totalVertexBytes
        if (icnData.size >= texOffset + 20) {
            buf.position(texOffset)
            val animIdTag = buf.int
            buf.int // frameLength
            buf.float // animSpeed
            buf.int // playOffset
            val frameCount = buf.int
            texOffset += 20

            if (animIdTag == 0x01 && frameCount in 1..10000) {
                for (f in 0 until frameCount) {
                    if (texOffset + 8 > icnData.size) break
                    buf.position(texOffset)
                    buf.int // shapeId
                    val rawKeyCount = buf.int
                    val keyCount = if (rawKeyCount > 0) rawKeyCount - 1 else 0
                    texOffset += 16 + keyCount * 8
                }
            }
        }

        val hasTextureFlag = (texFlags and 0x04) != 0 || texFlags == 0x07 || texFlags == 0x06
        val isCompressed = (texFlags and 0x08) != 0

        if (hasTextureFlag && texOffset < icnData.size) {
            if (isCompressed && texOffset + 4 <= icnData.size) {
                buf.position(texOffset)
                val compressedSize = buf.int
                texOffset += 4
                val rleEnd = minOf(texOffset + compressedSize, icnData.size)
                hasValidTexture = decodeRleTexture(icnData, texOffset, rleEnd, texPixels)
            } else if (!isCompressed && texOffset + (TEX_PIXEL_COUNT * 2) <= icnData.size) {
                buf.position(texOffset)
                for (k in 0 until TEX_PIXEL_COUNT) {
                    val rawPixel = buf.short.toInt() and 0xFFFF
                    texPixels[k] = rgb1555ToArgb8888(rawPixel)
                }
                hasValidTexture = true
            }
        }

        // Texture fallback scan at end of file if needed
        if (!hasValidTexture && icnData.size >= (TEX_PIXEL_COUNT * 2)) {
            val fallbackOffset = icnData.size - (TEX_PIXEL_COUNT * 2)
            buf.position(fallbackOffset)
            for (k in 0 until TEX_PIXEL_COUNT) {
                val rawPixel = buf.short.toInt() and 0xFFFF
                texPixels[k] = rgb1555ToArgb8888(rawPixel)
            }
            hasValidTexture = true
        }

        // If no texture at all, fill with white so vertex color and lighting show
        if (!hasValidTexture) {
            texPixels.fill(0xFFFFFFFF.toInt())
        }

        // 3. Compute Bounding Box, Center, and Scale
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        for (i in 0 until vertexCount) {
            val x = posX[i]
            val y = posY[i]
            val z = posZ[i]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (z < minZ) minZ = z
            if (z > maxZ) maxZ = z
        }

        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val centerZ = (minZ + maxZ) * 0.5f

        val extentX = maxX - minX
        val extentY = maxY - minY
        val extentZ = maxZ - minZ
        val maxExtent = maxOf(extentX, maxOf(extentY, extentZ))
        val scale = if (maxExtent > 0.001f) (3.2f / maxExtent) else 1.0f

        // 4. Setup Isometric Camera & Lighting
        // PS2 memory card standard 3/4 viewing angle
        val pitch = 0.26f // ~15 degrees down
        val yaw = -0.44f  // ~-25 degrees turn
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)

        val lightDir0 = floatArrayOf(0.577f, 0.577f, 0.577f)
        val lightCol0 = floatArrayOf(0.9f, 0.9f, 0.85f)
        val lightDir1 = floatArrayOf(-0.577f, 0.577f, 0.577f)
        val lightCol1 = floatArrayOf(0.5f, 0.55f, 0.6f)
        val lightDir2 = floatArrayOf(0f, -0.707f, 0.707f)
        val lightCol2 = floatArrayOf(0.3f, 0.3f, 0.3f)

        val ambR = iconSys?.ambientR?.coerceIn(0.4f, 0.8f) ?: 0.55f
        val ambG = iconSys?.ambientG?.coerceIn(0.4f, 0.8f) ?: 0.55f
        val ambB = iconSys?.ambientB?.coerceIn(0.4f, 0.8f) ?: 0.55f

        val screenX = FloatArray(vertexCount)
        val screenY = FloatArray(vertexCount)
        val depthZ = FloatArray(vertexCount)
        val litR = FloatArray(vertexCount)
        val litG = FloatArray(vertexCount)
        val litB = FloatArray(vertexCount)

        val cameraDistance = 5.0f
        val focalLength = 140.0f // FoV approx 50 degrees on 128px canvas

        for (i in 0 until vertexCount) {
            // Center and scale position
            val px0 = (posX[i] - centerX) * scale
            val py0 = (posY[i] - centerY) * scale
            val pz0 = (posZ[i] - centerZ) * scale

            // Rotate around Y then X
            val px1 = px0 * cosYaw + pz0 * sinYaw
            val py1 = py0
            val pz1 = -px0 * sinYaw + pz0 * cosYaw

            val rx = px1
            val ry = py1 * cosPitch - pz1 * sinPitch
            val rz = py1 * sinPitch + pz1 * cosPitch

            // Rotate normal
            val nx0 = normX[i]
            val ny0 = normY[i]
            val nz0 = normZ[i]

            val nx1 = nx0 * cosYaw + nz0 * sinYaw
            val ny1 = ny0
            val nz1 = -nx0 * sinYaw + nz0 * cosYaw

            val rnx = nx1
            val rny = ny1 * cosPitch - nz1 * sinPitch
            val rnz = ny1 * sinPitch + nz1 * cosPitch

            val nLen = sqrt(rnx * rnx + rny * rny + rnz * rnz)
            val nnx = if (nLen > 1e-4f) rnx / nLen else 0f
            val nny = if (nLen > 1e-4f) rny / nLen else 1f
            val nnz = if (nLen > 1e-4f) rnz / nLen else 0f

            // Directional + ambient lighting
            var lr = ambR
            var lg = ambG
            var lb = ambB

            val dot0 = maxOf(0f, nnx * lightDir0[0] + nny * lightDir0[1] + nnz * lightDir0[2])
            lr += dot0 * lightCol0[0]
            lg += dot0 * lightCol0[1]
            lb += dot0 * lightCol0[2]

            val dot1 = maxOf(0f, nnx * lightDir1[0] + nny * lightDir1[1] + nnz * lightDir1[2])
            lr += dot1 * lightCol1[0]
            lg += dot1 * lightCol1[1]
            lb += dot1 * lightCol1[2]

            val dot2 = maxOf(0f, nnx * lightDir2[0] + nny * lightDir2[1] + nnz * lightDir2[2])
            lr += dot2 * lightCol2[0]
            lg += dot2 * lightCol2[1]
            lb += dot2 * lightCol2[2]

            litR[i] = (colR[i] * lr.coerceIn(0f, 1.4f)).coerceIn(0f, 1f)
            litG[i] = (colG[i] * lg.coerceIn(0f, 1.4f)).coerceIn(0f, 1f)
            litB[i] = (colB[i] * lb.coerceIn(0f, 1.4f)).coerceIn(0f, 1f)

            // Perspective project
            val vz = cameraDistance - rz
            depthZ[i] = vz
            screenX[i] = (rx / vz) * focalLength + 64.0f
            screenY[i] = (-ry / vz) * focalLength + 64.0f
        }

        // 5. Software Triangle Rasterizer with Z-buffer
        val outPixels = IntArray(TEX_PIXEL_COUNT)
        val depthBuffer = FloatArray(TEX_PIXEL_COUNT) { Float.POSITIVE_INFINITY }
        var drawnPixelCount = 0

        val triangleCount = vertexCount / 3
        for (t in 0 until triangleCount) {
            val i0 = t * 3
            val i1 = t * 3 + 1
            val i2 = t * 3 + 2

            var x0 = screenX[i0]; var y0 = screenY[i0]; var z0 = depthZ[i0]
            var x1 = screenX[i1]; var y1 = screenY[i1]; var z1 = depthZ[i1]
            var x2 = screenX[i2]; var y2 = screenY[i2]; var z2 = depthZ[i2]

            if (z0 <= 0.1f || z1 <= 0.1f || z2 <= 0.1f) continue

            var u0 = uvU[i0]; var v0 = uvV[i0]
            var u1 = uvU[i1]; var v1 = uvV[i1]
            var u2 = uvU[i2]; var v2 = uvV[i2]

            var r0 = litR[i0]; var g0 = litG[i0]; var b0 = litB[i0]
            var r1 = litR[i1]; var g1 = litG[i1]; var b1 = litB[i1]
            var r2 = litR[i2]; var g2 = litG[i2]; var b2 = litB[i2]

            var denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if (abs(denom) < 1e-5f) continue

            // Ensure consistent winding order
            if (denom < 0f) {
                val tx = x1; x1 = x2; x2 = tx
                val ty = y1; y1 = y2; y2 = ty
                val tz = z1; z1 = z2; z2 = tz
                val tu = u1; u1 = u2; u2 = tu
                val tv = v1; v1 = v2; v2 = tv
                val tr = r1; r1 = r2; r2 = tr
                val tg = g1; g1 = g2; g2 = tg
                val tb = b1; b1 = b2; b2 = tb
                denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            }
            val invDenom = 1.0f / denom

            val minPx = maxOf(0, floor(minOf(x0, minOf(x1, x2))).toInt())
            val maxPx = minOf(127, ceil(maxOf(x0, maxOf(x1, x2))).toInt())
            val minPy = maxOf(0, floor(minOf(y0, minOf(y1, y2))).toInt())
            val maxPy = minOf(127, ceil(maxOf(y0, maxOf(y1, y2))).toInt())

            if (minPx > maxPx || minPy > maxPy) continue

            val invZ0 = 1.0f / z0
            val invZ1 = 1.0f / z1
            val invZ2 = 1.0f / z2

            val uOverZ0 = u0 * invZ0; val vOverZ0 = v0 * invZ0
            val rOverZ0 = r0 * invZ0; val gOverZ0 = g0 * invZ0; val bOverZ0 = b0 * invZ0

            val uOverZ1 = u1 * invZ1; val vOverZ1 = v1 * invZ1
            val rOverZ1 = r1 * invZ1; val gOverZ1 = g1 * invZ1; val bOverZ1 = b1 * invZ1

            val uOverZ2 = u2 * invZ2; val vOverZ2 = v2 * invZ2
            val rOverZ2 = r2 * invZ2; val gOverZ2 = g2 * invZ2; val bOverZ2 = b2 * invZ2

            for (py in minPy..maxPy) {
                val cy = py + 0.5f
                val rowOffset = py * 128
                for (px in minPx..maxPx) {
                    val cx = px + 0.5f

                    val w0 = ((y1 - y2) * (cx - x2) + (x2 - x1) * (cy - y2)) * invDenom
                    if (w0 < 0f) continue
                    val w1 = ((y2 - y0) * (cx - x2) + (x0 - x2) * (cy - y2)) * invDenom
                    if (w1 < 0f) continue
                    val w2 = 1.0f - w0 - w1
                    if (w2 < 0f) continue

                    val interpInvZ = w0 * invZ0 + w1 * invZ1 + w2 * invZ2
                    val z = 1.0f / interpInvZ
                    val pIdx = rowOffset + px

                    if (z < depthBuffer[pIdx]) {
                        depthBuffer[pIdx] = z

                        val u = (w0 * uOverZ0 + w1 * uOverZ1 + w2 * uOverZ2) * z
                        val v = (w0 * vOverZ0 + w1 * vOverZ1 + w2 * vOverZ2) * z
                        val r = (w0 * rOverZ0 + w1 * rOverZ1 + w2 * rOverZ2) * z
                        val g = (w0 * gOverZ0 + w1 * gOverZ1 + w2 * gOverZ2) * z
                        val b = (w0 * bOverZ0 + w1 * bOverZ1 + w2 * bOverZ2) * z

                        val tx = ((u - floor(u)) * 128f).toInt().coerceIn(0, 127)
                        val ty = ((v - floor(v)) * 128f).toInt().coerceIn(0, 127)
                        val texCol = texPixels[ty * 128 + tx]

                        val texR = (texCol shr 16) and 0xFF
                        val texG = (texCol shr 8) and 0xFF
                        val texB = texCol and 0xFF

                        val outR = (texR * r).toInt().coerceIn(0, 255)
                        val outG = (texG * g).toInt().coerceIn(0, 255)
                        val outB = (texB * b).toInt().coerceIn(0, 255)

                        outPixels[pIdx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                        drawnPixelCount++
                    }
                }
            }
        }

        if (drawnPixelCount < 10) return null

        val bitmap = Bitmap.createBitmap(TEX_WIDTH, TEX_HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(outPixels, 0, TEX_WIDTH, 0, 0, TEX_WIDTH, TEX_HEIGHT)
        return bitmap
    }

    private fun decodeRleTexture(
        data: ByteArray,
        startOffset: Int,
        endOffset: Int,
        outPixels: IntArray
    ): Boolean {
        var srcPos = startOffset
        var dstPixel = 0
        val maxDst = minOf(outPixels.size, TEX_PIXEL_COUNT)

        while (srcPos + 1 < endOffset && dstPixel < maxDst) {
            val rleCode = ((data[srcPos + 1].toInt() and 0xFF) shl 8) or (data[srcPos].toInt() and 0xFF)
            srcPos += 2

            if ((rleCode and 0x8000) != 0) {
                // Literal run of (0x10000 - rleCode) words
                val count = 0x10000 - rleCode
                for (i in 0 until count) {
                    if (srcPos + 1 >= endOffset || dstPixel >= maxDst) break
                    val rawPixel = ((data[srcPos + 1].toInt() and 0xFF) shl 8) or (data[srcPos].toInt() and 0xFF)
                    srcPos += 2
                    outPixels[dstPixel++] = rgb1555ToArgb8888(rawPixel)
                }
            } else {
                // Repeated pixel of rleCode times
                val count = rleCode
                if (count > 0) {
                    if (srcPos + 1 >= endOffset) break
                    val rawPixel = ((data[srcPos + 1].toInt() and 0xFF) shl 8) or (data[srcPos].toInt() and 0xFF)
                    srcPos += 2
                    val color = rgb1555ToArgb8888(rawPixel)
                    for (i in 0 until count) {
                        if (dstPixel >= maxDst) break
                        outPixels[dstPixel++] = color
                    }
                }
            }
        }

        return dstPixel >= (TEX_PIXEL_COUNT / 4)
    }

    private fun rgb1555ToArgb8888(pixel: Int): Int {
        val r = ((pixel and 0x001F) * 255) / 31
        val g = (((pixel shr 5) and 0x001F) * 255) / 31
        val b = (((pixel shr 10) and 0x001F) * 255) / 31
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
