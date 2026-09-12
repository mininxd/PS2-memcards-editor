package xyz.mininxd.ps2memcards.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.mininxd.ps2memcards.core.CardStats
import xyz.mininxd.ps2memcards.core.Ps2SuperBlock

@Composable
fun CardStatsDialog(
    superBlock: Ps2SuperBlock,
    stats: CardStats,
    onDismiss: () -> Unit,
    onOpenHex: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Memory Card Diagnostics",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatRow("Format Version", superBlock.version)
                        StatRow("Card Type", if (superBlock.cardType == 2) "PlayStation 2 (Standard)" else "Type ${superBlock.cardType}")
                        StatRow("Total Capacity", "${stats.totalSpaceKb / 1024} MB (${stats.totalSpaceKb} KB)")
                        StatRow("Page Size", "${stats.pageSize} bytes (${if (stats.hasEcc) "ECC Enabled" else "RAW"})")
                        StatRow("Cluster Size", "${stats.clusterSize} bytes (${superBlock.pagesPerCluster} pages)")
                        StatRow("Erase Block Size", "${superBlock.pagesPerBlock} pages (${superBlock.pagesPerBlock * superBlock.pageLen} bytes)")
                    }
                }

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatRow("Allocatable Clusters", "${stats.allocatableClusters}")
                        StatRow("Used Clusters", "${stats.allocatedClusters}")
                        StatRow("Free Clusters", "${stats.freeClusters}")
                        StatRow("Free Space", "${stats.freeSpaceKb} KB")
                        StatRow("Bad Blocks", "${stats.badBlocksCount}")
                        StatRow("Backup Blocks", "Block ${superBlock.backupBlock1} & ${superBlock.backupBlock2}")
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenHex != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenHex()
                        }
                    ) {
                        Text("Raw Hex")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
