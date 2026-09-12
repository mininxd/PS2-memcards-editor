package xyz.mininxd.ps2memcards.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.mininxd.ps2memcards.R
import xyz.mininxd.ps2memcards.core.RecentCard
import xyz.mininxd.ps2memcards.core.UpdateStatus

@Composable
fun EmptyStateScreen(
    onOpenCard: () -> Unit,
    onCreateCard: () -> Unit,
    recentCards: List<RecentCard> = emptyList(),
    onOpenRecentCard: (RecentCard) -> Unit = {},
    onRemoveRecentCard: (String) -> Unit = {},
    onClearRecentCards: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    updateStatus: UpdateStatus = UpdateStatus.Idle,
    onCheckUpdate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hasRecents = recentCards.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (hasRecents) {
            // Recent Cards Mode: Icon, title, and slogan are removed
            // Header Row with Settings Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Recent Cards",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // List of Recent Memory Cards
            recentCards.forEach { recent ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { onOpenRecentCard(recent) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.SdCard,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = recent.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val sizeText = if (recent.sizeBytes > 0) "${recent.sizeBytes / (1024 * 1024)} MB" else null
                            val saveText = if (recent.saveCount > 0) "${recent.saveCount} saves" else null
                            val subtitleText = listOfNotNull(sizeText, saveText).joinToString(" • ")
                            if (subtitleText.isNotBlank()) {
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = { onRemoveRecentCard(recent.uriString) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove from recents",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Compact Quick Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenCard,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Card", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }

                FilledTonalButton(
                    onClick = onCreateCard,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Card", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            // Large Memory Card Graphic
            Image(
                painter = painterResource(id = R.drawable.icon_transparent),
                contentDescription = null,
                modifier = Modifier.size(100.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "PS2 Memory Card Editor",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Effortless PS2 save management, beautifully designed for Android.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Quick Action Cards
            ActionCard(
                title = "Open Memory Card",
                subtitle = "Load a .ps2 image or PCSX2 folder card (_pcsx2_superblock)",
                icon = Icons.Default.FolderOpen,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = onOpenCard
            )

            Spacer(modifier = Modifier.height(10.dp))

            ActionCard(
                title = "Create New Card",
                subtitle = "Create a standard 8MB - 128MB .ps2 memory card with ECC",
                icon = Icons.Default.Add,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onCreateCard
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Settings Card (Above Check Updates)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenSettings() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Export filename format & app preferences",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Check Update Card (Compact)
        val context = LocalContext.current

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (updateStatus is UpdateStatus.UpdateAvailable) {
                        val url = updateStatus.releaseUrl
                        if (url.isNotBlank()) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        }
                    } else if (updateStatus !is UpdateStatus.Checking) {
                        onCheckUpdate()
                    }
                },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (updateStatus is UpdateStatus.UpdateAvailable) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (updateStatus is UpdateStatus.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = if (updateStatus is UpdateStatus.UpdateAvailable) {
                            Icons.Default.Download
                        } else {
                            Icons.Default.SystemUpdate
                        },
                        contentDescription = "Check Update",
                        modifier = Modifier.size(24.dp),
                        tint = if (updateStatus is UpdateStatus.UpdateAvailable) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (updateStatus is UpdateStatus.UpdateAvailable) {
                            "Update available ${updateStatus.tagName}"
                        } else {
                            "Check Update"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (updateStatus is UpdateStatus.UpdateAvailable) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    Text(
                        text = when (updateStatus) {
                            is UpdateStatus.Idle -> "Fetch latest releases from GitHub"
                            is UpdateStatus.Checking -> "Connecting to GitHub..."
                            is UpdateStatus.UpdateAvailable -> "Tap to open and download on GitHub"
                            is UpdateStatus.UpToDate -> "You're on the latest version (v${updateStatus.currentVersion})"
                            is UpdateStatus.Error -> "Error: ${updateStatus.message}. Tap to retry."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateStatus is UpdateStatus.UpdateAvailable) {
                            MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        }
                    )
                }

                if (updateStatus is UpdateStatus.UpdateAvailable) {
                    IconButton(onClick = onCheckUpdate, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Check Again",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = contentColor
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}
