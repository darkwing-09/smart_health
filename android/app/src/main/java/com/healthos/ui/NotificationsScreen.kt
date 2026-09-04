package com.healthos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class NotificationItemUi(
    val id: String,
    val title: String,
    val body: String,
    val severity: String,
    val alertTier: Int,
    val state: String,
    val isAcknowledged: Boolean,
    val createdAt: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    notifications: List<NotificationItemUi>,
    onAcknowledge: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health Notifications & Alerts") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No active notifications. All vitals nominal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(notifications, key = { it.id }) { item ->
                    NotificationCard(
                        item = item,
                        onAcknowledge = { onAcknowledge(item.id) },
                        onDismiss = { onDismiss(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationCard(
    item: NotificationItemUi,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit
) {
    val tierColor = when (item.alertTier) {
        4 -> Color(0xFFD32F2F) // Level 4 Urgent (Red)
        3 -> Color(0xFFF57C00) // Level 3 Important (Orange)
        2 -> Color(0xFF1976D2) // Level 2 Attention (Blue)
        else -> Color(0xFF757575)
    }

    val tierLabel = when (item.alertTier) {
        4 -> "URGENT (ACTION RECOMMENDED)"
        3 -> "IMPORTANT VARIATION"
        2 -> "ATTENTION / INSIGHT"
        else -> "INFO"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tierLabel,
                    color = tierColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (item.isAcknowledged) "Acknowledged" else "New",
                    color = if (item.isAcknowledged) Color(0xFF388E3C) else Color.Gray,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = item.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = Color.Gray)
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (!item.isAcknowledged) {
                    Button(
                        onClick = onAcknowledge,
                        colors = ButtonDefaults.buttonColors(containerColor = tierColor)
                    ) {
                        Text("Acknowledge", color = Color.White)
                    }
                }
            }
        }
    }
}
