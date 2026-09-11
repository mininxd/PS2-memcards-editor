package xyz.mininxd.ps2memcards.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.mininxd.ps2memcards.R
import xyz.mininxd.ps2memcards.core.UpdateStatus

@Composable
fun EmptyStateScreen(
    onOpenCard: () -> Unit,
    onCreateCard: () -> Unit,
    updateStatus: UpdateStatus = UpdateStatus.Idle,
    onCheckUpdate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(36.dp))

        // Large Memory Card Graphic
        Image(
            painter = painterResource(id = R.drawable.icon_transparent),
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "PS2 Memory Card Editor",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Effortless PS2 save management, beautifully designed for Android.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Quick Action Cards
        ActionCard(
            title = "Open Memory Card",
            subtitle = "Load an existing .ps2, .mc2, .mcd, or .raw image file",
            icon = Icons.Default.FolderOpen,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            onClick = onOpenCard
        )

        Spacer(modifier = Modifier.height(16.dp))

        ActionCard(
            title = "Create New Card",
            subtitle = "Create a standard 8MB - 128MB .ps2 memory card with ECC",
            icon = Icons.Default.Add,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onCreateCard
        )

        Spacer(modifier = Modifier.height(16.dp))

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
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (updateStatus is UpdateStatus.UpdateAvailable) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (updateStatus is UpdateStatus.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
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
                        modifier = Modifier.size(32.dp),
                        tint = if (updateStatus is UpdateStatus.UpdateAvailable) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (updateStatus is UpdateStatus.UpdateAvailable) {
                            "update available ${updateStatus.tagName}"
                        } else {
                            "Check Update"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (updateStatus is UpdateStatus.UpdateAvailable) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    Spacer(modifier = Modifier.height(2.dp))

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
                    IconButton(onClick = onCheckUpdate) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Check Again",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = contentColor
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
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
