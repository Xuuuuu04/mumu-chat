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
    isSelected: Boolean,
    onSelected: () -> Unit,
    onRename: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(horizontal = 4.dp)) {
        Surface(
            onClick = onSelected,
            selected = isSelected,
            shape = RoundedCornerShape(10.dp),
            color = if (isSelected) BrandPrimary.copy(alpha = 0.06f) else Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Notes,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isSelected) BrandPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                
                Spacer(Modifier.width(12.dp))
                
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) BrandPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                if (isSelected) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // 自定义墨韵菜单
        if (showMenu) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier
                    .width(160.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                DropdownMenuItem(
                    text = { Text("题名", style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onRename()
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                
                DropdownMenuItem(
                    text = { Text("抹除", style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        viewModel.deleteSession(session.id)
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.05f)
                )

                // 归档操作区域
                Text(
                    "移至归档",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                DropdownMenuItem(
                    text = { Text("移出归档", style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        viewModel.moveSessionToFolder(session.id, null)
                        showMenu = false
                    }
                )

                viewModel.settings.folders.orEmpty().forEach { folder ->
                    DropdownMenuItem(
                        text = { Text(folder, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            viewModel.moveSessionToFolder(session.id, folder)
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}
