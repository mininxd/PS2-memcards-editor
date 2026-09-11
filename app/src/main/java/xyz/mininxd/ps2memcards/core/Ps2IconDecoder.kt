package xyz.mininxd.ps2memcards.core

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
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
 * hardware-accelerated OpenGL ES 2.0 offscreen rasterization (falling back
 * to software rasterization when unavailable), depth buffering, and in-memory LRU caching.
 */
object Ps2IconDecoder {

    private const val ICN_MAGIC = 0x00010000
    private const val TEX_WIDTH = 128
    private const val TEX_HEIGHT = 128
    private const val TEX_PIXEL_COUNT = TEX_WIDTH * TEX_HEIGHT

    // In-memory LRU cache holding up to 256 rendered icon bitmaps
    private val iconCache = LruCache<String, Bitmap>(256)

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
     * Clears the entire icon cache and releases OpenGL resources.
     */
    fun clearCache() {
        synchronized(iconCache) {
            iconCache.evictAll()
        }
        OpenGlIconRenderer.destroy()
    }

    /**
     * Decodes and renders a PS2 3D save icon (.icn) into a 2D static Bitmap.
     * Renders shape 0 at a standard PS2 BIOS isometric viewing angle with
     * 3-point lighting and texture mapping via hardware-accelerated OpenGL ES 2.0
     * (falling back to software rasterization), then stores it in the LRU cache.
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

        val mesh = parseIconMesh(icnData)
        val renderedBitmap = if (mesh != null) {
            (try {
                render3dIconOpenGl(mesh, iconSys)
            } catch (_: Throwable) {
                null
            } ?: try {
                render3dIconSoftware(mesh, iconSys)
            } catch (_: Throwable) {
                null
            }) ?: decodeTexture(icnData)
        } else {
            decodeTexture(icnData)
        }

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

    private class ParsedIconMesh(
        val vertexCount: Int,
        val posX: FloatArray,
        val posY: FloatArray,
        val posZ: FloatArray,
        val normX: FloatArray,
        val normY: FloatArray,
        val normZ: FloatArray,
        val uvU: FloatArray,
        val uvV: FloatArray,
        val colR: FloatArray,
        val colG: FloatArray,
        val colB: FloatArray,
        val texPixels: IntArray,
        val centerX: Float,
        val centerY: Float,
        val centerZ: Float,
        val scale: Float
    )

    private fun parseIconMesh(icnData: ByteArray): ParsedIconMesh? {
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

        return ParsedIconMesh(
            vertexCount = vertexCount,
            posX = posX,
            posY = posY,
            posZ = posZ,
            normX = normX,
            normY = normY,
            normZ = normZ,
            uvU = uvU,
            uvV = uvV,
            colR = colR,
            colG = colG,
            colB = colB,
            texPixels = texPixels,
            centerX = centerX,
            centerY = centerY,
            centerZ = centerZ,
            scale = scale
        )
    }

    private fun render3dIconOpenGl(mesh: ParsedIconMesh, iconSys: Ps2IconSys?): Bitmap? {
        return OpenGlIconRenderer.render(mesh, iconSys)
    }

    private fun render3dIcon(icnData: ByteArray, iconSys: Ps2IconSys?): Bitmap? {
        val mesh = parseIconMesh(icnData) ?: return null
        return (try {
            render3dIconOpenGl(mesh, iconSys)
        } catch (_: Throwable) {
            null
        }) ?: render3dIconSoftware(mesh, iconSys)
    }

    private fun render3dIconSoftware(mesh: ParsedIconMesh, iconSys: Ps2IconSys?): Bitmap? {
        val vertexCount = mesh.vertexCount
        val posX = mesh.posX
        val posY = mesh.posY
        val posZ = mesh.posZ
        val normX = mesh.normX
        val normY = mesh.normY
        val normZ = mesh.normZ
        val uvU = mesh.uvU
        val uvV = mesh.uvV
        val colR = mesh.colR
        val colG = mesh.colG
        val colB = mesh.colB
        val texPixels = mesh.texPixels
        val centerX = mesh.centerX
        val centerY = mesh.centerY
        val centerZ = mesh.centerZ
        val scale = mesh.scale

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

        // Software Triangle Rasterizer with Z-buffer
        val outPixels = IntArray(TEX_PIXEL_COUNT)
        val depthBuffer = FloatArray(TEX_PIXEL_COUNT).apply { fill(Float.POSITIVE_INFINITY) }
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

            val minXVal = minOf(x0, minOf(x1, x2))
            val maxXVal = maxOf(x0, maxOf(x1, x2))
            val minYVal = minOf(y0, minOf(y1, y2))
            val maxYVal = maxOf(y0, maxOf(y1, y2))

            val minPx = maxOf(0, minXVal.toInt())
            val maxPx = minOf(127, maxXVal.toInt() + 1)
            val minPy = maxOf(0, minYVal.toInt())
            val maxPy = minOf(127, maxYVal.toInt() + 1)

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

            val dw0_dx = (y1 - y2) * invDenom
            val dw0_dy = (x2 - x1) * invDenom
            val dw1_dx = (y2 - y0) * invDenom
            val dw1_dy = (x0 - x2) * invDenom

            val startCx = minPx + 0.5f
            val startCy = minPy + 0.5f
            var rowW0 = ((y1 - y2) * (startCx - x2) + (x2 - x1) * (startCy - y2)) * invDenom
            var rowW1 = ((y2 - y0) * (startCx - x2) + (x0 - x2) * (startCy - y2)) * invDenom

            for (py in minPy..maxPy) {
                var curW0 = rowW0
                var curW1 = rowW1
                val rowOffset = py shl 7

                for (px in minPx..maxPx) {
                    val w0 = curW0
                    val w1 = curW1
                    curW0 += dw0_dx
                    curW1 += dw1_dx

                    if (w0 < 0f || w1 < 0f) continue
                    val w2 = 1.0f - w0 - w1
                    if (w2 < 0f) continue

                    val interpInvZ = w0 * invZ0 + w1 * invZ1 + w2 * invZ2
                    val z = 1.0f / interpInvZ
                    val pIdx = rowOffset or px

                    if (z < depthBuffer[pIdx]) {
                        depthBuffer[pIdx] = z

                        val u = (w0 * uOverZ0 + w1 * uOverZ1 + w2 * uOverZ2) * z
                        val v = (w0 * vOverZ0 + w1 * vOverZ1 + w2 * vOverZ2) * z
                        val r = (w0 * rOverZ0 + w1 * rOverZ1 + w2 * rOverZ2) * z
                        val g = (w0 * gOverZ0 + w1 * gOverZ1 + w2 * gOverZ2) * z
                        val b = (w0 * bOverZ0 + w1 * bOverZ1 + w2 * bOverZ2) * z

                        val uFrac = u - u.toInt()
                        val uNorm = if (uFrac < 0f) uFrac + 1f else uFrac
                        val tx = (uNorm * 127.99f).toInt().coerceIn(0, 127)

                        val vFrac = v - v.toInt()
                        val vNorm = if (vFrac < 0f) vFrac + 1f else vFrac
                        val ty = (vNorm * 127.99f).toInt().coerceIn(0, 127)

                        val texCol = texPixels[(ty shl 7) or tx]

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
                rowW0 += dw0_dy
                rowW1 += dw1_dy
            }
        }

        if (drawnPixelCount < 10) return null

        val bitmap = Bitmap.createBitmap(TEX_WIDTH, TEX_HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(outPixels, 0, TEX_WIDTH, 0, 0, TEX_WIDTH, TEX_HEIGHT)
        return bitmap
    }

    /**
     * Hardware-accelerated OpenGL ES 2.0 offscreen Pbuffer renderer.
     * Compiles GLSL shaders once and renders 3D polygonal icons directly into a 128x128
     * static 2D bitmap on the GPU, achieving ~50x speedup over CPU software rasterization.
     */
    private object OpenGlIconRenderer {
        private val lock = Any()
        private var isInitialized = false
        private var isFailed = false

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var program: Int = 0
        private var uMVPMatrixHandle: Int = -1
        private var uModelMatrixHandle: Int = -1
        private var uAmbientHandle: Int = -1
        private var uTextureHandle: Int = -1

        private var aPositionHandle: Int = -1
        private var aNormalHandle: Int = -1
        private var aTexCoordHandle: Int = -1
        private var aColorHandle: Int = -1

        private var textureId: Int = 0

        private val pixelBuffer: ByteBuffer = ByteBuffer.allocateDirect(TEX_WIDTH * TEX_HEIGHT * 4)
            .order(ByteOrder.nativeOrder())

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uModelMatrix;
            uniform vec3 uAmbient;

            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec2 aTexCoord;
            attribute vec3 aColor;

            varying vec2 vTexCoord;
            varying vec3 vLitColor;

            const vec3 lightDir0 = vec3(0.577, 0.577, 0.577);
            const vec3 lightCol0 = vec3(0.9, 0.9, 0.85);
            const vec3 lightDir1 = vec3(-0.577, 0.577, 0.577);
            const vec3 lightCol1 = vec3(0.5, 0.55, 0.6);
            const vec3 lightDir2 = vec3(0.0, -0.707, 0.707);
            const vec3 lightCol2 = vec3(0.3, 0.3, 0.3);

            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;

                vec3 normal = (uModelMatrix * vec4(aNormal, 0.0)).xyz;
                float nLen = length(normal);
                vec3 n = nLen > 0.0001 ? (normal / nLen) : vec3(0.0, 1.0, 0.0);

                vec3 light = uAmbient;
                light += max(0.0, dot(n, lightDir0)) * lightCol0;
                light += max(0.0, dot(n, lightDir1)) * lightCol1;
                light += max(0.0, dot(n, lightDir2)) * lightCol2;
                light = clamp(light, 0.0, 1.4);

                vLitColor = clamp(aColor * light, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;

            varying vec2 vTexCoord;
            varying vec3 vLitColor;

            uniform sampler2D uTexture;

            void main() {
                vec4 tex = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(clamp(tex.rgb * vLitColor, 0.0, 1.0), 1.0);
            }
        """

        fun destroy() {
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (textureId != 0) {
                        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                        textureId = 0
                    }
                    if (program != 0) {
                        GLES20.glDeleteProgram(program)
                        program = 0
                    }
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        eglSurface = EGL14.EGL_NO_SURFACE
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                        eglContext = EGL14.EGL_NO_CONTEXT
                    }
                }
            } catch (_: Throwable) {
            } finally {
                isInitialized = false
                isFailed = false
            }
        }

        private fun initEgl(): Boolean {
            try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

                val version = IntArray(2)
                if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

                val configAttribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16,
                    EGL14.EGL_NONE
                )

                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                var configChosen = EGL14.eglChooseConfig(
                    eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0
                )
                if (!configChosen || numConfigs[0] <= 0) {
                    val fallbackAttribs = intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_DEPTH_SIZE, 16,
                        EGL14.EGL_NONE
                    )
                    configChosen = EGL14.eglChooseConfig(
                        eglDisplay, fallbackAttribs, 0, configs, 0, 1, numConfigs, 0
                    )
                }

                if (!configChosen || numConfigs[0] <= 0 || configs[0] == null) return false
                val eglConfig = configs[0]!!

                val contextAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
                )
                eglContext = EGL14.eglCreateContext(
                    eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
                )
                if (eglContext == EGL14.EGL_NO_CONTEXT) return false

                val pbufferAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, TEX_WIDTH,
                    EGL14.EGL_HEIGHT, TEX_HEIGHT,
                    EGL14.EGL_NONE
                )
                eglSurface = EGL14.eglCreatePbufferSurface(
                    eglDisplay, eglConfig, pbufferAttribs, 0
                )
                if (eglSurface == EGL14.EGL_NO_SURFACE) {
                    destroy()
                    return false
                }

                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    destroy()
                    return false
                }

                program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                if (program == 0) {
                    destroy()
                    return false
                }

                uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
                uModelMatrixHandle = GLES20.glGetUniformLocation(program, "uModelMatrix")
                uAmbientHandle = GLES20.glGetUniformLocation(program, "uAmbient")
                uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")

                aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
                aNormalHandle = GLES20.glGetAttribLocation(program, "aNormal")
                aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
                aColorHandle = GLES20.glGetAttribLocation(program, "aColor")

                val texIds = IntArray(1)
                GLES20.glGenTextures(1, texIds, 0)
                textureId = texIds[0]
                if (textureId == 0) {
                    destroy()
                    return false
                }

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)

                isInitialized = true
                return true
            } catch (_: Throwable) {
                destroy()
                return false
            } finally {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                }
            }
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) return 0
            GLES20.glShaderSource(shader, shaderCode.trimIndent())
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        private fun createProgram(vertexCode: String, fragmentCode: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
            if (vertexShader == 0) return 0
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
            if (fragmentShader == 0) {
                GLES20.glDeleteShader(vertexShader)
                return 0
            }

            val prog = GLES20.glCreateProgram()
            if (prog == 0) {
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
                return 0
            }

            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, fragmentShader)
            GLES20.glLinkProgram(prog)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)

            if (linkStatus[0] == 0) {
                GLES20.glDeleteProgram(prog)
                return 0
            }
            return prog
        }

        fun render(mesh: ParsedIconMesh, iconSys: Ps2IconSys?): Bitmap? {
            synchronized(lock) {
                if (isFailed) return null
                if (!isInitialized) {
                    if (!initEgl()) {
                        isFailed = true
                        return null
                    }
                }

                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    destroy()
                    isFailed = true
                    return null
                }

                try {
                    GLES20.glViewport(0, 0, TEX_WIDTH, TEX_HEIGHT)
                    GLES20.glClearColor(0f, 0f, 0f, 0f)
                    GLES20.glClearDepthf(1.0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                    GLES20.glDepthFunc(GLES20.GL_LEQUAL)
                    GLES20.glDepthMask(true)
                    GLES20.glDisable(GLES20.GL_CULL_FACE)

                    GLES20.glUseProgram(program)

                    // Upload texture
                    val texBitmap = Bitmap.createBitmap(TEX_WIDTH, TEX_HEIGHT, Bitmap.Config.ARGB_8888)
                    texBitmap.setPixels(mesh.texPixels, 0, TEX_WIDTH, 0, 0, TEX_WIDTH, TEX_HEIGHT)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, texBitmap, 0)
                    texBitmap.recycle()
                    GLES20.glUniform1i(uTextureHandle, 0)

                    // Model Matrix
                    val pitchDeg = 0.26f * (180f / Math.PI.toFloat())
                    val yawDeg = -0.44f * (180f / Math.PI.toFloat())

                    val modelMatrix = FloatArray(16)
                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.rotateM(modelMatrix, 0, pitchDeg, 1f, 0f, 0f)
                    Matrix.rotateM(modelMatrix, 0, yawDeg, 0f, 1f, 0f)
                    Matrix.scaleM(modelMatrix, 0, mesh.scale, mesh.scale, mesh.scale)
                    Matrix.translateM(modelMatrix, 0, -mesh.centerX, -mesh.centerY, -mesh.centerZ)

                    // View Matrix (camera at 5.0 distance looking at origin)
                    val viewMatrix = FloatArray(16)
                    Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 5.0f, 0f, 0f, 0f, 0f, 1f, 0f)

                    // Projection Matrix (focalLength = 140 on 128px canvas -> near = 1.0, right = 64/140)
                    val near = 1.0f
                    val far = 20.0f
                    val right = (64.0f / 140.0f) * near
                    val left = -right
                    val top = right
                    val bottom = -top

                    val projMatrix = FloatArray(16)
                    Matrix.frustumM(projMatrix, 0, left, right, bottom, top, near, far)

                    val viewProjMatrix = FloatArray(16)
                    val mvpMatrix = FloatArray(16)
                    Matrix.multiplyMM(viewProjMatrix, 0, projMatrix, 0, viewMatrix, 0)
                    Matrix.multiplyMM(mvpMatrix, 0, viewProjMatrix, 0, modelMatrix, 0)

                    GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
                    GLES20.glUniformMatrix4fv(uModelMatrixHandle, 1, false, modelMatrix, 0)

                    val ambR = iconSys?.ambientR?.coerceIn(0.4f, 0.8f) ?: 0.55f
                    val ambG = iconSys?.ambientG?.coerceIn(0.4f, 0.8f) ?: 0.55f
                    val ambB = iconSys?.ambientB?.coerceIn(0.4f, 0.8f) ?: 0.55f
                    GLES20.glUniform3f(uAmbientHandle, ambR, ambG, ambB)

                    // Upload vertex data
                    val vertexCount = mesh.vertexCount
                    val vertexBuffer = ByteBuffer.allocateDirect(vertexCount * 11 * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()

                    for (i in 0 until vertexCount) {
                        vertexBuffer.put(mesh.posX[i])
                        vertexBuffer.put(mesh.posY[i])
                        vertexBuffer.put(mesh.posZ[i])

                        vertexBuffer.put(mesh.normX[i])
                        vertexBuffer.put(mesh.normY[i])
                        vertexBuffer.put(mesh.normZ[i])

                        vertexBuffer.put(mesh.uvU[i])
                        vertexBuffer.put(mesh.uvV[i])

                        vertexBuffer.put(mesh.colR[i])
                        vertexBuffer.put(mesh.colG[i])
                        vertexBuffer.put(mesh.colB[i])
                    }

                    val stride = 11 * 4
                    vertexBuffer.position(0)
                    GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer)
                    GLES20.glEnableVertexAttribArray(aPositionHandle)

                    vertexBuffer.position(3)
                    GLES20.glVertexAttribPointer(aNormalHandle, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer)
                    GLES20.glEnableVertexAttribArray(aNormalHandle)

                    vertexBuffer.position(6)
                    GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
                    GLES20.glEnableVertexAttribArray(aTexCoordHandle)

                    vertexBuffer.position(8)
                    GLES20.glVertexAttribPointer(aColorHandle, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer)
                    GLES20.glEnableVertexAttribArray(aColorHandle)

                    // Draw
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

                    GLES20.glDisableVertexAttribArray(aPositionHandle)
                    GLES20.glDisableVertexAttribArray(aNormalHandle)
                    GLES20.glDisableVertexAttribArray(aTexCoordHandle)
                    GLES20.glDisableVertexAttribArray(aColorHandle)

                    // Readback
                    pixelBuffer.position(0)
                    GLES20.glReadPixels(0, 0, TEX_WIDTH, TEX_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)

                    val outPixels = IntArray(TEX_PIXEL_COUNT)
                    var drawnCount = 0

                    for (y in 0 until TEX_HEIGHT) {
                        val glY = TEX_HEIGHT - 1 - y
                        val srcRowOffset = glY * TEX_WIDTH * 4
                        val dstRowOffset = y * TEX_WIDTH
                        for (x in 0 until TEX_WIDTH) {
                            val srcIdx = srcRowOffset + (x * 4)
                            val r = pixelBuffer.get(srcIdx).toInt() and 0xFF
                            val g = pixelBuffer.get(srcIdx + 1).toInt() and 0xFF
                            val b = pixelBuffer.get(srcIdx + 2).toInt() and 0xFF
                            val a = pixelBuffer.get(srcIdx + 3).toInt() and 0xFF
                            if (a > 0) {
                                drawnCount++
                            }
                            outPixels[dstRowOffset + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }

                    if (drawnCount < 10) return null

                    val bitmap = Bitmap.createBitmap(TEX_WIDTH, TEX_HEIGHT, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(outPixels, 0, TEX_WIDTH, 0, 0, TEX_WIDTH, TEX_HEIGHT)
                    return bitmap
                } catch (_: Throwable) {
                    return null
                } finally {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                }
            }
        }
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
        val r5 = pixel and 0x1F
        val g5 = (pixel shr 5) and 0x1F
        val b5 = (pixel shr 10) and 0x1F
        val r = (r5 shl 3) or (r5 shr 2)
        val g = (g5 shl 3) or (g5 shr 2)
        val b = (b5 shl 3) or (b5 shr 2)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
