package mumu.xsy.mumuchat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mumu.xsy.mumuchat.ChatSession
import mumu.xsy.mumuchat.ChatViewModel
import mumu.xsy.mumuchat.ui.theme.BrandPrimary

/**
 * 会话项组件
 * 显示单个会话信息，提供重命名、删除等操作
 */
@Composable
fun SessionItem(
    session: ChatSession,
    viewModel: ChatViewModel,
    onRename: () -> Unit
) {
    val isSelected = session.id == viewModel.currentSessionId
    var showMenu by remember { mutableStateOf(false) }

    Box {
        NavigationDrawerItem(
            label = { 
                Text(
                    session.title, 
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                ) 
            },
            selected = isSelected,
            onClick = { viewModel.selectSession(session.id) },
            icon = {
                Icon(
                    Icons.Default.Notes,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            badge = {
                if (isSelected) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            shape = RoundedCornerShape(8.dp),
            colors = NavigationDrawerItemDefaults.colors(
                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                unselectedContainerColor = Color.Transparent,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(vertical = 2.dp, horizontal = 0.dp)
        )

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("重命名") },
                onClick = {
                    onRename()
                    showMenu = false
                },
                leadingIcon = {
                    Icon(Icons.Default.Edit, null)
                }
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    viewModel.deleteSession(session.id)
                    showMenu = false
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        null,
                        tint = Color.Red
                    )
                }
            )

            HorizontalDivider()

            DropdownMenuItem(
                text = { Text("移出文件夹") },
                onClick = {
                    viewModel.moveSessionToFolder(session.id, null)
                    showMenu = false
                }
            )

            viewModel.settings.folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text("移至: $folder") },
                    onClick = {
                        viewModel.moveSessionToFolder(session.id, folder)
                        showMenu = false
                    }
                )
            }
        }
    }
}
