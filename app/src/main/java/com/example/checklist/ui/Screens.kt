package com.example.checklist.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.checklist.data.ChecklistInstance
import com.example.checklist.data.ChecklistItem
import com.example.checklist.data.ChecklistRepository
import com.example.checklist.data.ChecklistTemplate
import com.example.checklist.data.ItemDefinition
import com.example.checklist.data.ItemManager
import com.example.checklist.data.SortSchema
import com.example.checklist.widget.ChecklistWidget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Utility to skip leading emojis/spaces for sorting
private fun getTemplateSortKey(name: String): String {
    return name.dropWhile { !it.isLetterOrDigit() }.lowercase()
}

private fun isAutocompleteMatch(label: String, query: String): Boolean {
    if (query.length < 2) return false
    // Replace non-alphanumeric non-space chars with spaces to ignore emojis and treat them as word boundaries
    val clean = label.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }.joinToString("")
    return clean.split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .any { it.startsWith(query, ignoreCase = true) }
}

/**
 * Drives a "drag to reorder" gesture for a [SnapshotStateList].
 *
 * The dragged item is moved within [items] live so surrounding rows can animate out of the
 * way, but callers only find out the final (from, to) indices when the gesture ends - nothing
 * is persisted to a repository until the finger lifts.
 */
private class DragReorderState<T : Any>(
    private val items: SnapshotStateList<T>,
    private val keyOf: (T) -> Any
) {
    var draggedKey by mutableStateOf<Any?>(null)
        private set
    private var draggedFromIndex = -1
    var dragOffset by mutableFloatStateOf(0f)
        private set
    private var rowHeightPx = 1f

    val isDragging: Boolean get() = draggedKey != null

    fun start(item: T, index: Int, measuredRowHeightPx: Float) {
        draggedKey = keyOf(item)
        draggedFromIndex = index
        dragOffset = 0f
        rowHeightPx = measuredRowHeightPx.coerceAtLeast(1f)
    }

    fun offsetPxFor(key: Any): Float = if (key == draggedKey) dragOffset else 0f

    fun onDrag(deltaY: Float) {
        val key = draggedKey ?: return
        dragOffset += deltaY
        val currentIndex = items.indexOfFirst { keyOf(it) == key }
        if (currentIndex == -1) return
        val slots = Math.round(dragOffset / rowHeightPx)
        if (slots == 0) return
        val target = (currentIndex + slots).coerceIn(0, items.size - 1)
        if (target != currentIndex) {
            val moving = items.removeAt(currentIndex)
            items.add(target, moving)
            dragOffset -= (target - currentIndex) * rowHeightPx
        }
    }

    /** Returns the (fromIndex, toIndex) the drag settled on, or null if it never moved. */
    fun finish(): Pair<Int, Int>? {
        val key = draggedKey ?: return null
        val toIndex = items.indexOfFirst { keyOf(it) == key }
        draggedKey = null
        dragOffset = 0f
        val fromIndex = draggedFromIndex
        draggedFromIndex = -1
        return if (toIndex == -1 || fromIndex == -1 || fromIndex == toIndex) null else fromIndex to toIndex
    }

    fun cancel() {
        val key = draggedKey ?: return
        val currentIndex = items.indexOfFirst { keyOf(it) == key }
        if (currentIndex != -1 && currentIndex != draggedFromIndex && draggedFromIndex in items.indices) {
            items.add(draggedFromIndex, items.removeAt(currentIndex))
        }
        draggedKey = null
        dragOffset = 0f
        draggedFromIndex = -1
    }
}

/**
 * Handle-only drag gesture: waits for a long press before starting the drag (so a quick tap or
 * scroll passes through untouched), and consumes the initial press immediately so a long press
 * that starts on the handle can never also trigger an ancestor's click/long-click handler.
 */
private fun Modifier.dragReorderHandle(
    key: Any,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
): Modifier = this.pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val liftedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation()
        }
        if (liftedEarly == null) {
            onDragStart()
            val completed = try {
                drag(down.id) { change ->
                    change.consume()
                    onDragDelta(change.positionChange().y)
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                false
            }
            if (completed) onDragEnd() else onDragCancel()
        }
    }
}

private fun pinListWidget(context: Context, instanceId: String) {
    val appWidgetManager = AppWidgetManager.getInstance(context)
    val myProvider = ComponentName(context, ChecklistWidget::class.java)

    if (appWidgetManager.isRequestPinAppWidgetSupported) {
        val successCallback = Intent(context, ChecklistWidget::class.java).apply {
            action = ChecklistWidget.ACTION_PIN_CONFIRM
            putExtra(ChecklistWidget.EXTRA_INSTANCE_ID, instanceId)
        }
        
        val successPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            successCallback,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        appWidgetManager.requestPinAppWidget(myProvider, null, successPendingIntent)
    } else {
        Toast.makeText(context, "Pinned widgets not supported on this launcher", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigateToEditList: (String) -> Unit
) {
    val instances = ChecklistRepository.instances.filter { !it.isArchived }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                FloatingActionButton(
                    onClick = { showHelpDialog = true },
                    modifier = Modifier.align(Alignment.BottomStart)
                ) {
                    Icon(Icons.Default.Help, contentDescription = "Help")
                }
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New List")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { padding ->
        if (instances.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No active lists. Create one!", fontSize = 16.sp)
            }
        }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(instances, key = { it.id }) { instance ->
                ListCard(
                    instance = instance, 
                    onClick = { onNavigateToEditList(instance.id) },
                    onArchive = { ChecklistRepository.archiveInstance(instance.id) },
                    onLongClick = { 
                        pinListWidget(context, instance.id)
                        Toast.makeText(context, "Adding shortcut...", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        if (showCreateDialog) {
            CreateListDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name, templateIds, schemaId ->
                    val newInstance = ChecklistRepository.createInstanceFromTemplates(name, templateIds, schemaId)
                    onNavigateToEditList(newInstance.id)
                    showCreateDialog = false
                }
            )
        }

        if (showHelpDialog) {
            HelpDialog(onDismiss = { showHelpDialog = false })
        }
    }
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Help & Links") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { uriHandler.openUri("https://docs.google.com/spreadsheets/d/1Q5mv3uMpP4YOp-9wBdeuwPG7gz5eJo4uvaOGeSMgiCE/edit?usp=sharing") },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Getting started & recipes",
                            fontSize = 16.sp,
                            textDecoration = TextDecoration.Underline
                        )
                    }
                }
                
                TextButton(
                    onClick = { uriHandler.openUri("https://github.com/fletcheese") },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Fletcheese on Github",
                            fontSize = 16.sp,
                            textDecoration = TextDecoration.Underline
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun TemplatesScreen(
    modifier: Modifier = Modifier,
    onNavigateToEditTemplate: (String) -> Unit
) {
    val templates = ChecklistRepository.templates.sortedBy { getTemplateSortKey(it.name) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var templateToDelete by remember { mutableStateOf<ChecklistTemplate?>(null) }
    var templateToRename by remember { mutableStateOf<ChecklistTemplate?>(null) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New Template")
            }
        }
    ) { padding ->
        if (templates.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No templates. Create one!", fontSize = 16.sp)
            }
        }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(templates, key = { it.id }) { template ->
                TemplateCard(
                    template = template, 
                    onClick = { onNavigateToEditTemplate(template.id) },
                    onDelete = { templateToDelete = template },
                    onLongClick = { templateToRename = template }
                )
            }
        }

        if (showCreateDialog) {
            var name by remember { mutableStateOf("") }
            val focusRequester = remember { FocusRequester() }
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("New Template") },
                text = {
                    TextField(
                        value = name, 
                        onValueChange = { name = it }, 
                        label = { Text("Template Name") },
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                },
                confirmButton = {
                    Button(onClick = {
                        if (name.isNotBlank()) {
                            val newTemplate = ChecklistRepository.createTemplate(name)
                            onNavigateToEditTemplate(newTemplate.id)
                            showCreateDialog = false
                        }
                    }) { Text("Create") }
                }
            )
        }

        templateToDelete?.let { template ->
            AlertDialog(
                onDismissRequest = { templateToDelete = null },
                title = { Text("Delete Template") },
                text = { Text("Are you sure you want to delete '${template.name}'?", fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            ChecklistRepository.deleteTemplate(template.id)
                            templateToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { templateToDelete = null }) { Text("Cancel") }
                }
            )
        }

        templateToRename?.let { template ->
            var newName by remember { mutableStateOf(template.name) }
            val focusRequester = remember { FocusRequester() }
            AlertDialog(
                onDismissRequest = { templateToRename = null },
                title = { Text("Rename Template") },
                text = {
                    TextField(
                        value = newName, 
                        onValueChange = { newName = it }, 
                        label = { Text("Template Name") },
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                },
                confirmButton = {
                    Button(onClick = {
                        if (newName.isNotBlank()) {
                            ChecklistRepository.updateTemplateName(template.id, newName)
                            templateToRename = null
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { templateToRename = null }) { Text("Cancel") }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemasScreen(modifier: Modifier = Modifier) {
    val schemas = ChecklistRepository.sortSchemas
    var selectedSchemaId by remember { mutableStateOf(schemas.firstOrNull()?.id) }
    var schemaToRename by remember { mutableStateOf<SortSchema?>(null) }
    var showAddSchemaDialog by remember { mutableStateOf(false) }
    var itemToEditDef by remember { mutableStateOf<ItemDefinition?>(null) }
    var showSyncDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(schemas.size) {
        if (selectedSchemaId == null || schemas.none { it.id == selectedSchemaId }) {
            selectedSchemaId = schemas.firstOrNull()?.id
        }
    }

    val selectedSchema = schemas.find { it.id == selectedSchemaId }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Schemas & Sync") },
                actions = {
                    IconButton(onClick = { showSyncDialog = true }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Import/Export")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Current:", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                selectedSchema?.let { schema ->
                    TextButton(onClick = { schemaToRename = schema }) {
                        Text(schema.name, fontSize = 14.sp)
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showAddSchemaDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Schema")
                }
            }
            
            HorizontalDivider()

            val currentSchemaId = selectedSchemaId
            val sortedItems by remember(currentSchemaId, ChecklistRepository.itemDefinitions.size) {
                derivedStateOf {
                    val allItems = ChecklistRepository.itemDefinitions
                    if (currentSchemaId != null) {
                        allItems.sortedWith(
                            compareBy<ItemDefinition> { it.sortValues[currentSchemaId].isNullOrBlank() }.reversed()
                            .thenBy(Comparator { a, b -> ItemManager.compareSortStrings(a, b) }) { it.sortValues[currentSchemaId] ?: "" }
                            .thenBy { it.label }
                        )
                    } else allItems.toList()
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                items(sortedItems, key = { it.id }) { def ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .clickable { itemToEditDef = def },
                        border = CardDefaults.outlinedCardBorder(),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(def.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                            Text(def.sortValues[currentSchemaId] ?: "-", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            
            if (schemas.isNotEmpty()) {
                val selectedIndex = schemas.indexOfFirst { it.id == selectedSchemaId }.coerceAtLeast(0)
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    edgePadding = 0.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                ) {
                    schemas.forEach { schema ->
                        Tab(
                            selected = selectedSchemaId == schema.id,
                            onClick = { selectedSchemaId = schema.id },
                            text = { Text(schema.name, fontSize = 14.sp) }
                        )
                    }
                }
            }
        }

        if (showSyncDialog) {
            ImportExportDialog(
                onDismiss = { showSyncDialog = false },
                onExport = {
                    val tsv = ChecklistRepository.exportToTsv()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Checklist Export", tsv))
                    scope.launch { snackbarHostState.showSnackbar("Exported to clipboard") }
                },
                onImport = { data ->
                    ChecklistRepository.importFromTsv(data)
                    scope.launch { snackbarHostState.showSnackbar("Imported successfully") }
                    showSyncDialog = false
                }
            )
        }

        if (showAddSchemaDialog) {
            var name by remember { mutableStateOf("") }
            val focusRequester = remember { FocusRequester() }
            AlertDialog(
                onDismissRequest = { showAddSchemaDialog = false },
                title = { Text("Add New Schema") },
                text = {
                    TextField(
                        value = name, 
                        onValueChange = { name = it }, 
                        label = { Text("Schema Name") }, 
                        singleLine = true,
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                },
                confirmButton = {
                    Button(onClick = {
                        if (name.isNotBlank()) {
                            val newSchema = ChecklistRepository.addSchema(name)
                            selectedSchemaId = newSchema.id
                        }
                        showAddSchemaDialog = false
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { showAddSchemaDialog = false }) { Text("Cancel") } }
            )
        }

        schemaToRename?.let { schema ->
            var name by remember { mutableStateOf(schema.name) }
            val focusRequester = remember { FocusRequester() }
            AlertDialog(
                onDismissRequest = { schemaToRename = null },
                title = { Text("Rename Schema") },
                text = {
                    TextField(
                        value = name, 
                        onValueChange = { name = it }, 
                        label = { Text("Schema Name") }, 
                        singleLine = true,
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                },
                confirmButton = {
                    Button(onClick = {
                        if (name.isNotBlank()) {
                            ChecklistRepository.renameSchema(schema.id, name)
                        }
                        schemaToRename = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { schemaToRename = null }) { Text("Cancel") } }
            )
        }

        itemToEditDef?.let { def ->
            EditItemDefinitionDialog(
                def = def,
                onDismiss = { itemToEditDef = null }
            )
        }
    }
}

@Composable
fun ImportExportDialog(
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImport: (String) -> Unit
) {
    var importText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import / Export Data") },
        text = {
            Column {
                Text("Copy current state for editing, or paste TSV to sync.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Export")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { 
                        ChecklistRepository.mergeAndClean()
                        Toast.makeText(context, "Database Merged & Cleaned", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Merge & Clean Database")
                }
                Spacer(Modifier.height(16.dp))
                TextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("Paste TSV here to Import") },
                    modifier = Modifier.fillMaxWidth().height(150.dp).focusRequester(focusRequester)
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            }
        },
        confirmButton = {
            Button(onClick = { onImport(importText) }, enabled = importText.isNotBlank()) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ArchiveScreen(
    modifier: Modifier = Modifier,
    onNavigateToEditList: (String) -> Unit
) {
    val instances = ChecklistRepository.instances.filter { it.isArchived }
    val context = LocalContext.current
    
    Scaffold(modifier = modifier) { padding ->
        if (instances.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Archive is empty.", fontSize = 18.sp)
            }
        }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(instances, key = { it.id }) { instance ->
                ListCard(
                    instance = instance, 
                    onClick = { 
                        ChecklistRepository.unarchiveInstance(instance.id)
                        Toast.makeText(context, "Unarchived", Toast.LENGTH_SHORT).show()
                        onNavigateToEditList(instance.id)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditListScreen(
    instanceId: String,
    onBack: () -> Unit
) {
    val instance = ChecklistRepository.instances.find { it.id == instanceId } ?: return
    var isEditMode by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showAddItemDialog by remember { mutableStateOf(false) }
    var showAddTemplateDialog by remember { mutableStateOf(false) }
    var itemToEdit by remember { mutableStateOf<ChecklistItem?>(null) }
    var showCompleteConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    // Feedback: Back swipe gesture exits edit mode
    BackHandler(enabled = isEditMode) {
        isEditMode = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(instance.name) },
                navigationIcon = {
                    IconButton(onClick = { if (isEditMode) isEditMode = false else onBack() }) {
                        Icon(if (isEditMode) Icons.Default.Close else Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Feedback: Pencil icon to trigger edit mode
                    IconButton(onClick = { isEditMode = !isEditMode }) {
                        Icon(if (isEditMode) Icons.Default.Save else Icons.Default.Edit, contentDescription = "Edit Mode")
                    }
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add Item") },
                            onClick = { showMenu = false; showAddItemDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Add Template") },
                            onClick = { showMenu = false; showAddTemplateDialog = true }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Complete All") },
                            onClick = { 
                                showMenu = false
                                showCompleteConfirm = true 
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove Completed") },
                            onClick = { 
                                showMenu = false
                                showRemoveConfirm = true 
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Feedback: Button at top of list in edit mode to add new item
            if (isEditMode) {
                OutlinedButton(
                    onClick = { showAddItemDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add New Item", fontSize = 16.sp)
                }
            }

            val localItems = remember(instance.items) { instance.items.toMutableStateList() }
            val dragState = remember(localItems) { DragReorderState(localItems) { it.id } }
            val rowHeightPx = remember { mutableMapOf<String, Float>() }
            val haptic = LocalHapticFeedback.current
            val context = LocalContext.current

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(localItems, key = { it.id }) { item ->
                    val beingDragged = dragState.draggedKey == item.id
                    val targetOffset = if (beingDragged) dragState.dragOffset else 0f
                    val animatedOffset by animateFloatAsState(
                        targetValue = targetOffset,
                        animationSpec = tween(durationMillis = if (beingDragged) 0 else 150),
                        label = "dragOffset"
                    )
                    ChecklistItemRow(
                        item = item,
                        onToggle = {
                            if (isEditMode) {
                                itemToEdit = item
                            } else {
                                ChecklistRepository.toggleItem(instanceId, item.id)
                                ChecklistRepository.unarchiveIfNeeded(instanceId)
                            }
                        },
                        onLongClick = { itemToEdit = item },
                        onDelete = if (isEditMode) {
                            { ChecklistRepository.removeItemFromInstance(instanceId, item.id) }
                        } else null,
                        isEditMode = isEditMode,
                        rowModifier = Modifier
                            .then(if (beingDragged) Modifier else Modifier.animateItem())
                            .onSizeChanged { rowHeightPx[item.id] = it.height.toFloat() }
                            .graphicsLayer {
                                translationY = animatedOffset
                                scaleX = if (beingDragged) 1.03f else 1f
                                scaleY = if (beingDragged) 1.03f else 1f
                                shadowElevation = if (beingDragged) 12f else 0f
                            }
                            .zIndex(if (beingDragged) 1f else 0f),
                        dragHandleModifier = if (isEditMode) {
                            Modifier.dragReorderHandle(
                                key = item.id,
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragState.start(item, localItems.indexOf(item), rowHeightPx[item.id] ?: 0f)
                                },
                                onDragDelta = { dragState.onDrag(it) },
                                onDragEnd = {
                                    val move = dragState.finish()
                                    if (move != null) {
                                        val (fromIndex, toIndex) = move
                                        ChecklistRepository.reorderItem(instanceId, fromIndex, toIndex)
                                        val movedItem = localItems.getOrNull(toIndex)
                                        val schemaName = instance.appliedSortSchemaId?.let { id ->
                                            ChecklistRepository.sortSchemas.find { it.id == id }?.name
                                        }
                                        val positionText = "position ${toIndex + 1} of ${localItems.size}"
                                        val message = if (schemaName != null) {
                                            "Moved \"${movedItem?.label}\" to $positionText (sorted by $schemaName)"
                                        } else {
                                            "Moved \"${movedItem?.label}\" to $positionText"
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDragCancel = { dragState.cancel() }
                            )
                        } else Modifier
                    )
                }
            }
        }

        if (showAddItemDialog) {
            var label by remember { mutableStateOf("") }
            val focusRequester = remember { FocusRequester() }
            val suggestions by remember(label) {
                derivedStateOf {
                    if (label.length < 2) emptyList<ItemDefinition>()
                    else {
                        val existingNormalized = instance.items.map { ItemManager.normalizeLabel(it.label) }.toSet()
                        ChecklistRepository.itemDefinitions
                            .filter { def ->
                                val normalizedDef = ItemManager.normalizeLabel(def.label)
                                !existingNormalized.contains(normalizedDef) && isAutocompleteMatch(def.label, label)
                            }
                            .take(5)
                    }
                }
            }

            AlertDialog(
                onDismissRequest = { showAddItemDialog = false },
                title = { Text("Add Item") },
                text = {
                    Column {
                        TextField(
                            value = label, 
                            onValueChange = { label = it }, 
                            label = { Text("Item Name") },
                            modifier = Modifier.focusRequester(focusRequester).fillMaxWidth(),
                            singleLine = true
                        )
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        
                        if (suggestions.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column {
                                    suggestions.forEach { suggestion ->
                                        Text(
                                            text = suggestion.label,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    ChecklistRepository.addItemToInstance(instanceId, suggestion.label)
                                                    showAddItemDialog = false
                                                }
                                                .padding(12.dp),
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        ChecklistRepository.addItemToInstance(instanceId, label)
                        showAddItemDialog = false
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { showAddItemDialog = false }) { Text("Cancel") } }
            )
        }

        if (showAddTemplateDialog) {
            MultiTemplateSelectDialog(
                onDismiss = { showAddTemplateDialog = false },
                onAdd = { templateIds ->
                    templateIds.forEach { ChecklistRepository.addTemplateToInstance(instanceId, it) }
                    showAddTemplateDialog = false
                }
            )
        }

        if (showCompleteConfirm) {
            AlertDialog(
                onDismissRequest = { showCompleteConfirm = false },
                title = { Text("Complete All?") },
                text = { Text("This will mark all items as checked and archive the list.", fontSize = 14.sp) },
                confirmButton = {
                    Button(onClick = {
                        ChecklistRepository.completeAll(instanceId)
                        showCompleteConfirm = false
                        onBack()
                    }) { Text("Complete") }
                },
                dismissButton = { TextButton(onClick = { showCompleteConfirm = false }) { Text("Cancel") } }
            )
        }

        if (showRemoveConfirm) {
            AlertDialog(
                onDismissRequest = { showRemoveConfirm = false },
                title = { Text("Remove Completed?") },
                text = { Text("This will permanently remove all checked items from this list.", fontSize = 14.sp) },
                confirmButton = {
                    Button(onClick = {
                        ChecklistRepository.removeCompleted(instanceId)
                        showRemoveConfirm = false
                    }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") } }
            )
        }

        itemToEdit?.let { item ->
            var label by remember { mutableStateOf(item.label) }
            val focusRequester = remember { FocusRequester() }
            AlertDialog(
                onDismissRequest = { itemToEdit = null },
                title = { Text("Edit Item") },
                text = {
                    TextField(
                        value = label, 
                        onValueChange = { label = it }, 
                        label = { Text("Label") },
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                },
                confirmButton = {
                    Button(onClick = {
                        ChecklistRepository.updateItemLabelInInstance(instanceId, item.id, label)
                        itemToEdit = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { itemToEdit = null }) { Text("Cancel") } }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTemplateScreen(
    templateId: String,
    onBack: () -> Unit
) {
    val template = ChecklistRepository.templates.find { it.id == templateId } ?: return
    var showAddItemDialog by remember { mutableStateOf(false) }
    var itemToEditDef by remember { mutableStateOf<ItemDefinition?>(null) }
    var selectedSchemaId by remember { mutableStateOf(ChecklistRepository.sortSchemas.firstOrNull()?.id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(template.name)
                        selectedSchemaId?.let { id ->
                            val schemaName = ChecklistRepository.sortSchemas.find { it.id == id }?.name
                            Text("Sorting by: $schemaName", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    var showSchemaSelect by remember { mutableStateOf(false) }
                    IconButton(onClick = { showSchemaSelect = true }) {
                        Icon(Icons.Default.Sort, contentDescription = "Select Schema")
                    }
                    DropdownMenu(expanded = showSchemaSelect, onDismissRequest = { showSchemaSelect = false }) {
                        ChecklistRepository.sortSchemas.forEach { schema ->
                            DropdownMenuItem(
                                text = { Text(schema.name) },
                                onClick = { selectedSchemaId = schema.id; showSchemaSelect = false }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddItemDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Item")
            }
        }
    ) { padding ->
        val items = template.itemIds.mapNotNull { id -> ChecklistRepository.itemDefinitions.find { it.id == id } }
        val sortedItems = remember(items, selectedSchemaId) { 
            items.sortedWith(
                compareBy(Comparator { a, b -> ItemManager.compareSortStrings(a, b) }) { it.sortValues[selectedSchemaId] ?: "" }
            )
        }
        val localItems = remember(sortedItems) { sortedItems.toMutableStateList() }
        val dragState = remember(localItems) { DragReorderState(localItems) { it.id } }
        val rowHeightPx = remember { mutableMapOf<String, Float>() }
        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current

        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(localItems, key = { it.id }) { def ->
                val beingDragged = dragState.draggedKey == def.id
                val targetOffset = if (beingDragged) dragState.dragOffset else 0f
                val animatedOffset by animateFloatAsState(
                    targetValue = targetOffset,
                    animationSpec = tween(durationMillis = if (beingDragged) 0 else 150),
                    label = "dragOffset"
                )
                TemplateItemRow(
                    def = def,
                    schemaId = selectedSchemaId,
                    onDelete = { ChecklistRepository.removeItemFromTemplate(templateId, def.id) },
                    onEdit = { itemToEditDef = def },
                    rowModifier = Modifier
                        .then(if (beingDragged) Modifier else Modifier.animateItem())
                        .onSizeChanged { rowHeightPx[def.id] = it.height.toFloat() }
                        .graphicsLayer {
                            translationY = animatedOffset
                            scaleX = if (beingDragged) 1.03f else 1f
                            scaleY = if (beingDragged) 1.03f else 1f
                            shadowElevation = if (beingDragged) 12f else 0f
                        }
                        .zIndex(if (beingDragged) 1f else 0f),
                    dragHandleModifier = Modifier.dragReorderHandle(
                        key = def.id,
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragState.start(def, localItems.indexOf(def), rowHeightPx[def.id] ?: 0f)
                        },
                        onDragDelta = { dragState.onDrag(it) },
                        onDragEnd = {
                            val move = dragState.finish()
                            val schemaId = selectedSchemaId
                            if (move != null && schemaId != null) {
                                val (fromIndex, toIndex) = move
                                ChecklistRepository.reorderItemInSchema(schemaId, fromIndex, toIndex, sortedItems)
                                val movedDef = localItems.getOrNull(toIndex)
                                val schemaName = ChecklistRepository.sortSchemas.find { it.id == schemaId }?.name
                                Toast.makeText(
                                    context,
                                    "Moved \"${movedDef?.label}\" to position ${toIndex + 1} of ${localItems.size} in $schemaName",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onDragCancel = { dragState.cancel() }
                    )
                )
            }
        }

        if (showAddItemDialog) {
            var label by remember { mutableStateOf("") }
            val focusRequester = remember { FocusRequester() }
            val suggestions by remember(label) {
                derivedStateOf {
                    if (label.length < 2) emptyList<ItemDefinition>()
                    else {
                        val existingNormalized = items.map { ItemManager.normalizeLabel(it.label) }.toSet()
                        ChecklistRepository.itemDefinitions
                            .filter { def ->
                                val normalizedDef = ItemManager.normalizeLabel(def.label)
                                !existingNormalized.contains(normalizedDef) && isAutocompleteMatch(def.label, label)
                            }
                            .take(5)
                    }
                }
            }

            AlertDialog(
                onDismissRequest = { showAddItemDialog = false },
                title = { Text("Add Item to Template") },
                text = {
                    Column {
                        TextField(
                            value = label, 
                            onValueChange = { label = it }, 
                            label = { Text("Item Name") },
                            modifier = Modifier.focusRequester(focusRequester).fillMaxWidth(),
                            singleLine = true
                        )
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        
                        if (suggestions.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column {
                                    suggestions.forEach { suggestion ->
                                        Text(
                                            text = suggestion.label,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    ChecklistRepository.addItemToTemplate(templateId, suggestion.label)
                                                    showAddItemDialog = false
                                                }
                                                .padding(12.dp),
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        ChecklistRepository.addItemToTemplate(templateId, label)
                        showAddItemDialog = false
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { showAddItemDialog = false }) { Text("Cancel") } }
            )
        }

        itemToEditDef?.let { def ->
            EditItemDefinitionDialog(
                def = def,
                onDismiss = { itemToEditDef = null }
            )
        }
    }
}

@Composable
fun TemplateItemRow(
    def: ItemDefinition,
    schemaId: String?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    rowModifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier
) {
    Surface(
        modifier = rowModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 8.dp)
            .clickable { onEdit() },
        border = CardDefaults.outlinedCardBorder(),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Reorder",
                modifier = dragHandleModifier.padding(end = 8.dp).size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f)) {
                Text(text = def.label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                schemaId?.let { id ->
                    val sortValue = def.sortValues[id]
                    if (!sortValue.isNullOrBlank()) {
                        Text(text = "Sort: $sortValue", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun EditItemDefinitionDialog(def: ItemDefinition, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(def.label) }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Item") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextField(
                    value = label, 
                    onValueChange = { label = it; ChecklistRepository.updateDefinitionLabel(def.id, it) }, 
                    label = { Text("Label") },
                    modifier = Modifier.focusRequester(focusRequester)
                    )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                Spacer(Modifier.height(16.dp))
                Text("Sort Values", style = MaterialTheme.typography.labelLarge)
                ChecklistRepository.sortSchemas.forEach { schema ->
                    var value by remember { mutableStateOf(def.sortValues[schema.id] ?: "") }
                    TextField(
                        value = value,
                        onValueChange = { 
                            value = it
                            ChecklistRepository.updateSortValue(def.id, schema.id, it)
                        },
                        label = { Text("Schema: ${schema.name}") },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            Box(Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { 
                        ChecklistRepository.deleteDefinition(def.id)
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Item", tint = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun CreateListDialog(
    onDismiss: () -> Unit,
    onCreate: (String, List<String>, String?) -> Unit
) {
    var name by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) }
    val selectedTemplates = remember { mutableStateListOf<String>() }
    var selectedSchemaId by remember { mutableStateOf(ChecklistRepository.sortSchemas.firstOrNull()?.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New List") },
        text = {
            Column {
                TextField(value = name, onValueChange = { name = it }, label = { Text("List Name") })
                Spacer(Modifier.height(8.dp))
                Text("Pick Templates:", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    val sortedTemplates = ChecklistRepository.templates.sortedBy { getTemplateSortKey(it.name) }
                    items(sortedTemplates) { template ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (template.id in selectedTemplates) selectedTemplates.remove(template.id)
                                else selectedTemplates.add(template.id)
                            }.padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = template.id in selectedTemplates,
                                onCheckedChange = null
                            )
                            Column {
                                Text(template.name, fontSize = 14.sp)
                                val lastUsedText = template.lastUsed?.let { 
                                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
                                } ?: "Never"
                                Text(
                                    text = "${template.itemIds.size} items • Used: $lastUsedText",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Sort Schema:", style = MaterialTheme.typography.labelLarge)
                ChecklistRepository.sortSchemas.forEach { schema ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selectedSchemaId = schema.id }
                    ) {
                        RadioButton(selected = selectedSchemaId == schema.id, onClick = null)
                        Text(schema.name, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, selectedTemplates.toList(), selectedSchemaId) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun MultiTemplateSelectDialog(
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit
) {
    val selectedTemplates = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Templates") },
        text = {
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                val sortedTemplates = ChecklistRepository.templates.sortedBy { getTemplateSortKey(it.name) }
                items(sortedTemplates) { template ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (template.id in selectedTemplates) selectedTemplates.remove(template.id)
                            else selectedTemplates.add(template.id)
                        }.padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = template.id in selectedTemplates,
                            onCheckedChange = null
                        )
                        Column {
                            Text(template.name, fontSize = 14.sp)
                            val lastUsedText = template.lastUsed?.let { 
                                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
                            } ?: "Never"
                            Text(
                                text = "${template.itemIds.size} items • Used: $lastUsedText",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(selectedTemplates.toList()) }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListCard(
    instance: ChecklistInstance, 
    onClick: () -> Unit,
    onArchive: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = instance.name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp))
                Text(text = "${instance.items.count { it.isChecked }}/${instance.items.size} items", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
            }
            if (onArchive != null) {
                IconButton(onClick = onArchive, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Archive, contentDescription = "Archive", modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TemplateCard(
    template: ChecklistTemplate, 
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = template.name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp))
                val lastUsedText = template.lastUsed?.let { 
                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
                } ?: "Never"
                Text(
                    text = "${template.itemIds.size} items • Used: $lastUsedText",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChecklistItemRow(
    item: ChecklistItem,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    isEditMode: Boolean = false,
    rowModifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier
) {
    Surface(
        modifier = rowModifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onToggle,
                onLongClick = onLongClick
            )
            .padding(vertical = 4.dp, horizontal = 8.dp),
        border = CardDefaults.outlinedCardBorder(),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isEditMode) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Reorder",
                    modifier = dragHandleModifier.padding(end = 8.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Checkbox(
                checked = item.isChecked, 
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = item.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                textDecoration = if (item.isChecked) TextDecoration.LineThrough else null
            )
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
