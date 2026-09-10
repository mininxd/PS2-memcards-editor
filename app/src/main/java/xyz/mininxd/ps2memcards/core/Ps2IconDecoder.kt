package xyz.mininxd.ps2memcards.core

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes PS2 3D save icon files (.icn) to extract and render their 16-bit texture preview.
 */
object Ps2IconDecoder {

    private const val ICN_MAGIC = 0x00010000
    private const val TEX_WIDTH = 128
    private const val TEX_HEIGHT = 128
    private const val TEX_PIXEL_COUNT = TEX_WIDTH * TEX_HEIGHT

    fun decodeTexture(icnData: ByteArray): Bitmap? {
        if (icnData.size < 32) return null
        val buf = ByteBuffer.wrap(icnData).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.int
        if (magic != ICN_MAGIC) {
            return null
        }

        val animShapes = buf.int
        val texType = buf.int
        buf.int // reserved
        val vertexCount = buf.int

        // Calculate offset to texture data:
        // Vertex section: vertexCount * 3 * 4 (float3)
        // Texture UV section: vertexCount * 2 * 4 (float2)
        // Color section: vertexCount * 4 (byte4)
        // Animation shapes: (animShapes - 1) * vertexCount * 3 * 4 (float3)
        // Header size = 0x14 (20 bytes) or padded to 0x20
        val headerSize = 0x20
        val animOffset = headerSize + (vertexCount * 8) // float3 (12) + float2 (8) approx

        // Locate texture data (typically in the second half or at end of file)
        val pixels = IntArray(TEX_PIXEL_COUNT)
        var decoded = false

        if (texType == 0x07) {
            // Uncompressed 16-bit RGB1555 texture (32768 bytes)
            val texSize = TEX_PIXEL_COUNT * 2
            val texOffset = icnData.size - texSize
            if (texOffset >= 0 && texOffset + texSize <= icnData.size) {
                buf.position(texOffset)
                for (i in 0 until TEX_PIXEL_COUNT) {
                    val rawPixel = buf.short.toInt() and 0xFFFF
                    pixels[i] = rgb1555ToArgb8888(rawPixel)
                }
                decoded = true
            }
        } else {
            // RLE Compressed 16-bit texture
            // Search for RLE payload or try scanning from end of geometry
            val texOffset = maxOf(0, icnData.size - (TEX_PIXEL_COUNT * 2))
            if (texOffset < icnData.size) {
                decoded = decodeRleTexture(icnData, texOffset, pixels)
            }
        }

        if (!decoded) {
            // Fallback scan: check if there's an uncompressed texture block at the end
            val texSize = TEX_PIXEL_COUNT * 2
            if (icnData.size >= texSize) {
                val texOffset = icnData.size - texSize
                buf.position(texOffset)
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
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeRleTexture(data: ByteArray, startOffset: Int, outPixels: IntArray): Boolean {
        var srcPos = startOffset
        var dstPixel = 0

        while (srcPos + 1 < data.size && dstPixel < TEX_PIXEL_COUNT) {
            val rleCode = ((data[srcPos + 1].toInt() and 0xFF) shl 8) or (data[srcPos].toInt() and 0xFF)
            srcPos += 2

            if ((rleCode and 0x8000) == 0) {
                // Literal run of (rleCode) words
                val count = rleCode
                for (i in 0 until count) {
                    if (srcPos + 1 >= data.size || dstPixel >= TEX_PIXEL_COUNT) break
                    val rawPixel = ((data[srcPos + 1].toInt() and 0xFF) shl 8) or (data[srcPos].toInt() and 0xFF)
                    srcPos += 2
                    outPixels[dstPixel++] = rgb1555ToArgb8888(rawPixel)
                }
            } else {
                // Repeated pixel of (rleCode & 0x7FFF) times
                val count = rleCode and 0x7FFF
                if (srcPos + 1 >= data.size) break
                val rawPixel = ((data[srcPos + 1].toInt() and 0xFF) shl 8) or (data[srcPos].toInt() and 0xFF)
                srcPos += 2
                val color = rgb1555ToArgb8888(rawPixel)
                for (i in 0 until count) {
                    if (dstPixel >= TEX_PIXEL_COUNT) break
                    outPixels[dstPixel++] = color
                }
            }
        }

        return dstPixel >= (TEX_PIXEL_COUNT / 2)
    }

    private fun rgb1555ToArgb8888(pixel: Int): Int {
        val a = if ((pixel and 0x8000) != 0) 0xFF else 0x00
        val r = ((pixel and 0x001F) * 255) / 31
        val g = (((pixel shr 5) and 0x001F) * 255) / 31
        val b = (((pixel shr 10) and 0x001F) * 255) / 31
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
