package com.ps2.memcard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun HexViewerDialog(
    title: String,
    data: ByteArray,
    onDismiss: () -> Unit
) {
    val rowCount = (data.size + 15) / 16

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    text = "Hex Inspector",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$title (${data.size} bytes)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    LazyColumn(
                        modifier = Modifier.width(420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(rowCount) { rowIndex ->
                            val offset = rowIndex * 16
                            val hexBuilder = StringBuilder()
                            val asciiBuilder = StringBuilder()

                            for (i in 0 until 16) {
                                val byteIndex = offset + i
                                if (byteIndex < data.size) {
                                    val b = data[byteIndex].toInt() and 0xFF
                                    hexBuilder.append(String.format(Locale.US, "%02X ", b))
                                    if (b in 32..126) {
                                        asciiBuilder.append(b.toChar())
                                    } else {
                                        asciiBuilder.append('.')
                                    }
                                } else {
                                    hexBuilder.append("   ")
                                    asciiBuilder.append(' ')
                                }
                                if (i == 7) hexBuilder.append(" ")
                            }

                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = String.format(Locale.US, "%08X: ", offset),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = hexBuilder.toString(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = " |${asciiBuilder}|",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
