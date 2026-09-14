package com.tianxian.fruit.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tianxian.fruit.sync.*

private data class PermissionItem(
    val key: String,
    val label: String,
    val group: String
)

private val permissionItems =
    listOf(
        PermissionItem(
            BookPermissions.HOME_BUSINESS_VIEW,
            "首页经营金额",
            "查看"
        ),
        PermissionItem(
            BookPermissions.PURCHASE_VIEW,
            "查看进货",
            "采购"
        ),
        PermissionItem(
            BookPermissions.PURCHASE_CREATE,
            "新增进货",
            "采购"
        ),
        PermissionItem(
            BookPermissions.PURCHASE_EDIT,
            "修改进货",
            "采购"
        ),
        PermissionItem(
            BookPermissions.PURCHASE_DELETE,
            "删除进货",
            "采购"
        ),
        PermissionItem(
            BookPermissions.PURCHASE_ACTIVITY_VIEW,
            "查看协作采购",
            "采购"
        ),
        PermissionItem(
            BookPermissions.PURCHASE_PLAN_EDIT,
            "编辑采购计划",
            "采购"
        ),
        PermissionItem(
            BookPermissions.BUSINESS_VIEW,
            "查看营业数据",
            "营业"
        ),
        PermissionItem(
            BookPermissions.BUSINESS_EDIT,
            "新增/修改营业",
            "营业"
        ),
        PermissionItem(
            BookPermissions.HISTORY_VIEW,
            "历史记录",
            "经营"
        ),
        PermissionItem(
            BookPermissions.STATS_VIEW,
            "经营统计",
            "经营"
        ),
        PermissionItem(
            BookPermissions.PROFIT_VIEW,
            "查看利润分配",
            "利润与结算"
        ),
        PermissionItem(
            BookPermissions.PROFIT_EDIT,
            "修改利润分配",
            "利润与结算"
        ),
        PermissionItem(
            BookPermissions.SETTLEMENT_VIEW,
            "查看结算",
            "利润与结算"
        ),
        PermissionItem(
            BookPermissions.SETTLEMENT_EDIT,
            "执行/修改结算",
            "利润与结算"
        ),
        PermissionItem(
            BookPermissions.REPORT_VIEW,
            "查看/生成报表",
            "报表"
        ),
        PermissionItem(
            BookPermissions.BASIC_EDIT,
            "商品/摊位/合伙人设置",
            "基础设置"
        )
    )

private val permissionTemplates =
    listOf(
        "FULL",
        "PURCHASER",
        "OPERATOR",
        "FINANCE",
        "READONLY",
        "CUSTOM"
    )

@Composable
internal fun CloudSharedBooksContent(
    cloudSyncManager: CloudSyncManager,
    ledgerManager: LedgerManager,
    currentBook: LedgerBook,
    onSwitchBook: (String) -> Unit,
    onChanged: () -> Unit
) {
    var books by remember {
        mutableStateOf<List<CloudBookInfo>>(
            emptyList()
        )
    }
    var loading by remember {
        mutableStateOf(false)
    }
    var message by remember {
        mutableStateOf("")
    }

    fun reload() {
        if (loading) return
        loading = true

        Thread {
            val result =
                runCatching {
                    cloudSyncManager
                        .listCloudBooks()
                }

            Handler(
                Looper.getMainLooper()
            ).post {
                loading = false
                result
                    .onSuccess {
                        books = it
                        message =
                            "云端账本 ${it.size} 个"
                        onChanged()
                    }
                    .onFailure {
                        message =
                            it.message
                                ?: "读取失败"
                    }
            }
        }.start()
    }

    LaunchedEffect(Unit) {
        reload()
    }

    Column(
        Modifier.fillMaxSize()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 12.dp,
                    vertical = 6.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    reload()
                },
                enabled = !loading
            ) {
                Text(
                    if (loading) {
                        "刷新中"
                    } else {
                        "刷新"
                    }
                )
            }

            Spacer(
                Modifier.width(10.dp)
            )

            Text(
                message,
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color = Color.Gray
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    horizontal = 12.dp,
                    vertical = 4.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(7.dp)
        ) {
            if (
                books.isEmpty() &&
                !loading
            ) {
                item {
                    Text(
                        "当前账号没有可访问的云端账本。",
                        modifier =
                            Modifier.padding(10.dp),
                        color = Color.Gray
                    )
                }
            }

            items(
                books,
                key = {
                    it.id
                }
            ) {
                info ->
                val local =
                    ledgerManager
                        .findBookByCloudId(
                            info.id
                        )

                Card(
                    Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(11.dp),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f)
                        ) {
                            Text(
                                if (
                                    info.ownerUsername
                                        .isBlank()
                                ) {
                                    info.name
                                } else {
                                    "${info.name}（${info.ownerUsername}）"
                                },
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(
                                (
                                    if (
                                        info.role ==
                                        "OWNER"
                                    ) {
                                        "所有者"
                                    } else {
                                        "成员访问"
                                    }
                                ) +
                                    " · 云端 · ${info.memberCount}人",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    if (
                                        currentBook.id ==
                                        info.id
                                    ) {
                                        Color(0xFF13A868)
                                    } else {
                                        Color.Gray
                                    }
                            )
                        }

                        if (local != null) {
                            TextButton(
                                onClick = {
                                    onSwitchBook(
                                        local.id
                                    )
                                }
                            ) {
                                Text(
                                    if (
                                        currentBook.id ==
                                        local.id
                                    ) {
                                        "当前"
                                    } else {
                                        "切换"
                                    }
                                )
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    if (loading) {
                                        return@TextButton
                                    }

                                    loading = true
                                    message =
                                        "正在下载 ${info.name}…"

                                    Thread {
                                        val result =
                                            runCatching {
                                                cloudSyncManager
                                                    .downloadCloudBook(
                                                        info
                                                    )
                                            }

                                        Handler(
                                            Looper.getMainLooper()
                                        ).post {
                                            loading = false
                                            result
                                                .onSuccess {
                                                    message =
                                                        it.message
                                                    onChanged()
                                                    reload()
                                                }
                                                .onFailure {
                                                    message =
                                                        it.message
                                                            ?: "下载失败"
                                                }
                                        }
                                    }.start()
                                },
                                enabled = !loading
                            ) {
                                Text("下载")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MemberPermissionContent(
    cloudSyncManager: CloudSyncManager,
    ledgerManager: LedgerManager,
    currentBook: LedgerBook,
    onChanged: () -> Unit
) {
    val session =
        cloudSyncManager.session()

    var members by remember {
        mutableStateOf<List<CloudMemberInfo>>(
            emptyList()
        )
    }
    var loading by remember {
        mutableStateOf(false)
    }
    var message by remember {
        mutableStateOf("")
    }
    var showAdd by remember {
        mutableStateOf(false)
    }
    var editing by remember {
        mutableStateOf<CloudMemberInfo?>(
            null
        )
    }

    val canManage =
        session?.systemRole ==
            "SUPERADMIN" ||
            currentBook.permission ==
            "OWNER"

    fun reload() {
        if (
            loading ||
            !currentBook.cloudEnabled
        ) {
            return
        }

        loading = true

        Thread {
            val result =
                runCatching {
                    cloudSyncManager
                        .listMembers(
                            currentBook.id
                        )
                }

            Handler(
                Looper.getMainLooper()
            ).post {
                loading = false

                result
                    .onSuccess {
                        members = it
                        message =
                            "成员 ${it.size} 人"
                    }
                    .onFailure {
                        message =
                            it.message
                                ?: "读取失败"
                    }
            }
        }.start()
    }

    LaunchedEffect(
        currentBook.id
    ) {
        reload()
    }

    if (!currentBook.cloudEnabled) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment =
                Alignment.Center
        ) {
            Text(
                "当前是本机账本，请先建立或下载云端账本。",
                color = Color.Gray
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 12.dp,
                    vertical = 6.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Text(
                ledgerManager
                    .displayName(
                        currentBook
                    ),
                fontWeight =
                    FontWeight.Bold,
                modifier =
                    Modifier.weight(1f)
            )

            if (canManage) {
                Button(
                    onClick = {
                        showAdd = true
                    },
                    enabled = !loading
                ) {
                    Text("＋ 成员")
                }
            }
        }

        if (message.isNotBlank()) {
            Text(
                message,
                modifier =
                    Modifier.padding(
                        horizontal = 12.dp
                    ),
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color = Color.Gray
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(12.dp),
            verticalArrangement =
                Arrangement.spacedBy(7.dp)
        ) {
            items(
                members,
                key = {
                    it.userId
                }
            ) {
                member ->
                Card(
                    Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(11.dp),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f)
                        ) {
                            Text(
                                "${member.displayName}（${member.username}）",
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(
                                if (
                                    member.role ==
                                    "OWNER"
                                ) {
                                    "所有者 · 全部权限"
                                } else {
                                    BookPermissions
                                        .templateLabel(
                                            member.permissionTemplate
                                        ) +
                                        " · ${member.permissions.size}项"
                                },
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color = Color.Gray
                            )
                        }

                        if (
                            canManage &&
                            member.role !=
                            "OWNER"
                        ) {
                            TextButton(
                                onClick = {
                                    editing =
                                        member
                                }
                            ) {
                                Text("权限")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddMemberDialog(
            busy = loading,
            onDismiss = {
                showAdd = false
            },
            onSave = {
                username,
                template,
                permissions ->
                showAdd = false
                loading = true

                Thread {
                    val result =
                        runCatching {
                            cloudSyncManager
                                .addOrUpdateMember(
                                    bookId =
                                        currentBook.id,
                                    username =
                                        username,
                                    permissionTemplate =
                                        template,
                                    permissions =
                                        permissions
                                )
                            cloudSyncManager
                                .listMembers(
                                    currentBook.id
                                )
                        }

                    Handler(
                        Looper.getMainLooper()
                    ).post {
                        loading = false
                        result
                            .onSuccess {
                                members = it
                                message =
                                    "成员已加入"
                                onChanged()
                            }
                            .onFailure {
                                message =
                                    it.message
                                        ?: "保存失败"
                            }
                    }
                }.start()
            }
        )
    }

    editing?.let {
        member ->
        PermissionEditorDialog(
            member = member,
            busy = loading,
            onDismiss = {
                editing = null
            },
            onSave = {
                template,
                permissions ->
                editing = null
                loading = true

                Thread {
                    val result =
                        runCatching {
                            cloudSyncManager
                                .updateMemberPermissions(
                                    bookId =
                                        currentBook.id,
                                    userId =
                                        member.userId,
                                    permissionTemplate =
                                        template,
                                    permissions =
                                        permissions
                                )
                            cloudSyncManager
                                .listMembers(
                                    currentBook.id
                                )
                        }

                    Handler(
                        Looper.getMainLooper()
                    ).post {
                        loading = false
                        result
                            .onSuccess {
                                members = it
                                message =
                                    "权限已保存"
                                onChanged()
                            }
                            .onFailure {
                                message =
                                    it.message
                                        ?: "保存失败"
                            }
                    }
                }.start()
            },
            onRemove = {
                editing = null
                loading = true

                Thread {
                    val result =
                        runCatching {
                            cloudSyncManager
                                .removeMember(
                                    bookId =
                                        currentBook.id,
                                    userId =
                                        member.userId
                                )
                            cloudSyncManager
                                .listMembers(
                                    currentBook.id
                                )
                        }

                    Handler(
                        Looper.getMainLooper()
                    ).post {
                        loading = false
                        result
                            .onSuccess {
                                members = it
                                message =
                                    "成员已移除"
                                onChanged()
                            }
                            .onFailure {
                                message =
                                    it.message
                                        ?: "移除失败"
                            }
                    }
                }.start()
            }
        )
    }
}

@Composable
private fun AddMemberDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        String,
        String,
        Set<String>
    ) -> Unit
) {
    var username by remember {
        mutableStateOf("")
    }
    var template by remember {
        mutableStateOf("PURCHASER")
    }
    var expanded by remember {
        mutableStateOf(false)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("添加账本成员")
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                    },
                    label = {
                        Text("用户名")
                    },
                    singleLine = true
                )

                Box {
                    OutlinedButton(
                        onClick = {
                            expanded = true
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "权限：" +
                                BookPermissions
                                    .templateLabel(
                                        template
                                    )
                        )
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = {
                            expanded = false
                        }
                    ) {
                        permissionTemplates
                            .filter {
                                it != "CUSTOM"
                            }
                            .forEach {
                                value ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            BookPermissions
                                                .templateLabel(
                                                    value
                                                )
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        template = value
                                    }
                                )
                            }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (
                        username.trim()
                            .isNotBlank()
                    ) {
                        onSave(
                            username.trim(),
                            template,
                            BookPermissions
                                .templatePermissions(
                                    template
                                )
                        )
                    }
                },
                enabled = !busy
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PermissionEditorDialog(
    member: CloudMemberInfo,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        String,
        Set<String>
    ) -> Unit,
    onRemove: () -> Unit
) {
    var template by remember(
        member.userId
    ) {
        mutableStateOf(
            member.permissionTemplate
                .takeIf {
                    it in permissionTemplates
                }
                ?: "CUSTOM"
        )
    }
    var expanded by remember {
        mutableStateOf(false)
    }

    val selected =
        remember(
            member.userId
        ) {
            mutableStateMapOf<String, Boolean>()
                .apply {
                    permissionItems
                        .forEach {
                            item ->
                            this[item.key] =
                                item.key in
                                    member.permissions
                        }
                }
        }

    fun applyTemplate(
        value: String
    ) {
        template = value
        val values =
            BookPermissions
                .templatePermissions(
                    value
                )

        permissionItems
            .forEach {
                selected[it.key] =
                    it.key in values
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "${member.displayName} 权限"
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(
                        max = 590.dp
                    )
                    .verticalScroll(
                        rememberScrollState()
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(5.dp)
            ) {
                Box {
                    OutlinedButton(
                        onClick = {
                            expanded = true
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "模板：" +
                                BookPermissions
                                    .templateLabel(
                                        template
                                    )
                        )
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = {
                            expanded = false
                        }
                    ) {
                        permissionTemplates
                            .forEach {
                                value ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            BookPermissions
                                                .templateLabel(
                                                    value
                                                )
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        if (
                                            value ==
                                            "CUSTOM"
                                        ) {
                                            template =
                                                "CUSTOM"
                                        } else {
                                            applyTemplate(
                                                value
                                            )
                                        }
                                    }
                                )
                            }
                    }
                }

                permissionItems
                    .groupBy {
                        it.group
                    }
                    .forEach {
                        group ->
                        Text(
                            group.key,
                            fontWeight =
                                FontWeight.Bold,
                            modifier =
                                Modifier.padding(
                                    top = 5.dp
                                )
                        )

                        group.value
                            .forEach {
                                item ->
                                Row(
                                    Modifier
                                        .fillMaxWidth(),
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {
                                    Text(
                                        item.label,
                                        modifier =
                                            Modifier.weight(1f)
                                    )

                                    Switch(
                                        checked =
                                            selected[item.key] ==
                                            true,
                                        onCheckedChange = {
                                            selected[item.key] =
                                                it
                                            template =
                                                "CUSTOM"
                                        }
                                    )
                                }
                            }
                    }

                TextButton(
                    onClick = onRemove,
                    enabled = !busy,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        "移除成员",
                        color =
                            MaterialTheme
                                .colorScheme
                                .error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        template,
                        selected
                            .filterValues {
                                it
                            }
                            .keys
                            .toSet()
                    )
                },
                enabled = !busy
            ) {
                Text("保存权限")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("取消")
            }
        }
    )
}
