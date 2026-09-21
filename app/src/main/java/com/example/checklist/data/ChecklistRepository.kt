package com.example.checklist.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.checklist.widget.ChecklistWidget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object ChecklistRepository {
    private const val FILE_NAME = "checklist_data.json"
    private lateinit var dataFile: File
    private var appContext: Context? = null
    private val jsonConfig = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
        coerceInputValues = true 
    }

    var syncUrl by mutableStateOf<String?>(null)
    
    var isInitialized by mutableStateOf(false)
        private set

    // Delegation to Managers for UI and internal access
    val instances get() = InstanceManager.instances
    val templates get() = TemplateManager.templates
    val sortSchemas get() = SchemaManager.sortSchemas
    val itemDefinitions get() = ItemManager.itemDefinitions

    fun initialize(context: Context) {
        if (isInitialized) return
        
        try {
            appContext = context.applicationContext
            dataFile = File(context.filesDir, FILE_NAME)
            if (dataFile.exists()) {
                val json = dataFile.readText()
                if (json.isNotBlank()) {
                    val state = jsonConfig.decodeFromString<RepositoryState>(json)
                    loadStateInternal(state)
                } else {
                    loadDefaultsInternal()
                }
            } else {
                loadDefaultsInternal()
            }
        } catch (e: Exception) {
            Log.e("ChecklistRepository", "Initialization failed", e)
            loadDefaultsInternal()
        } finally {
            isInitialized = true
        }
    }

    private fun loadDefaultsInternal() {
        SchemaManager.sortSchemas.clear()
        SchemaManager.sortSchemas.add(SortSchema(id = "default_schema", name = "Default"))
    }

    private fun loadStateInternal(state: RepositoryState) {
        SchemaManager.sortSchemas.clear()
        SchemaManager.sortSchemas.addAll(state.sortSchemas)
        ItemManager.itemDefinitions.clear()
        ItemManager.itemDefinitions.addAll(state.itemDefinitions)
        TemplateManager.templates.clear()
        TemplateManager.templates.addAll(state.templates)
        InstanceManager.instances.clear()
        InstanceManager.instances.addAll(state.instances)
        syncUrl = state.syncUrl ?: syncUrl
        
        if (SchemaManager.sortSchemas.isEmpty()) {
            SchemaManager.sortSchemas.add(SortSchema(id = "default_schema", name = "Default"))
        }
    }

    fun importState(state: RepositoryState) {
        loadStateInternal(state)
        save()
    }

    fun save() {
        try {
            if (!::dataFile.isInitialized) return
            val state = RepositoryState(
                sortSchemas = SchemaManager.sortSchemas.toList(),
                itemDefinitions = ItemManager.itemDefinitions.toList(),
                templates = TemplateManager.templates.toList(),
                instances = InstanceManager.instances.toList(),
                syncUrl = syncUrl
            )
            val json = jsonConfig.encodeToString(state)
            dataFile.writeText(json)
            
            // Refresh Widget
            appContext?.let { ChecklistWidget.updateAllWidgets(it) }
        } catch (e: Exception) {
            Log.e("ChecklistRepository", "Save failed", e)
        }
    }

    // List Management Delegations
    fun archiveInstance(id: String) = InstanceManager.archiveInstance(id)
    fun unarchiveInstance(id: String) = InstanceManager.unarchiveInstance(id)
    fun createInstanceFromTemplates(name: String, templateIds: List<String>, schemaId: String?) = 
        InstanceManager.createInstanceFromTemplates(name, templateIds, schemaId)
    fun toggleItem(instanceId: String, itemId: String) = InstanceManager.toggleItem(instanceId, itemId)
    fun unarchiveIfNeeded(instanceId: String) = InstanceManager.unarchiveIfNeeded(instanceId)
    fun completeAll(instanceId: String) = InstanceManager.completeAll(instanceId)
    fun removeCompleted(instanceId: String) = InstanceManager.removeCompleted(instanceId)
    fun addItemToInstance(instanceId: String, label: String) = InstanceManager.addItemToInstance(instanceId, label)
    fun removeItemFromInstance(instanceId: String, itemId: String) = InstanceManager.removeItemFromInstance(instanceId, itemId)
    fun addTemplateToInstance(instanceId: String, templateId: String) = InstanceManager.addTemplateToInstance(instanceId, templateId)
    fun updateItemLabelInInstance(instanceId: String, itemId: String, newLabel: String) = 
        InstanceManager.updateItemLabelInInstance(instanceId, itemId, newLabel)
    fun reorderItem(instanceId: String, fromIndex: Int, toIndex: Int) =
        InstanceManager.reorderItem(instanceId, fromIndex, toIndex)

    // Template Management Delegations
    fun createTemplate(name: String) = TemplateManager.createTemplate(name)
    fun deleteTemplate(id: String) = TemplateManager.deleteTemplate(id)
    fun updateTemplateName(id: String, newName: String) = TemplateManager.updateTemplateName(id, newName)
    fun addItemToTemplate(templateId: String, label: String) = TemplateManager.addItemToTemplate(templateId, label)
    fun removeItemFromTemplate(templateId: String, definitionId: String) = TemplateManager.removeItemFromTemplate(templateId, definitionId)

    // Schema Management Delegations
    fun addSchema(name: String) = SchemaManager.addSchema(name)
    fun renameSchema(id: String, newName: String) = SchemaManager.renameSchema(id, newName)

    // Item Management Delegations
    fun updateDefinitionLabel(id: String, newLabel: String) = ItemManager.updateDefinitionLabel(id, newLabel)
    fun updateSortValue(definitionId: String, schemaId: String, value: String) = ItemManager.updateSortValue(definitionId, schemaId, value)
    fun deleteDefinition(id: String) = ItemManager.deleteDefinition(id)
    fun reorderItemInSchema(schemaId: String, fromIndex: Int, toIndex: Int, sortedItems: List<ItemDefinition>) =
        ItemManager.reorderItemInSchema(schemaId, fromIndex, toIndex, sortedItems)

    fun mergeAndClean() {
        val idMap = mutableMapOf<String, String>()

        val groupedDefinitions = ItemManager.itemDefinitions.groupBy { ItemManager.normalizeLabel(it.label) }
        val newDefinitions = groupedDefinitions.map { (_, group) ->
            val keeper = group.maxByOrNull { it.label.length }!!
            group.forEach { d -> idMap[d.id] = keeper.id }
            
            val mergedSortValues = keeper.sortValues.toMutableMap()
            group.forEach { d ->
                d.sortValues.forEach { (sid, value) ->
                    if (mergedSortValues[sid].isNullOrBlank()) mergedSortValues[sid] = value
                }
            }
            keeper.copy(sortValues = mergedSortValues)
        }
        ItemManager.itemDefinitions.clear()
        ItemManager.itemDefinitions.addAll(newDefinitions)

        val groupedTemplates = TemplateManager.templates.groupBy { ItemManager.normalizeLabel(it.name) }
        val newTemplates = groupedTemplates.map { (_, group) ->
            val keeper = group.maxByOrNull { it.name.length }!!
            val mergedItemIds = group.flatMap { it.itemIds }.mapNotNull { idMap[it] }.distinct()
            val maxLastUsed = group.mapNotNull { it.lastUsed }.maxOrNull()
            keeper.copy(itemIds = mergedItemIds, lastUsed = maxLastUsed)
        }
        TemplateManager.templates.clear()
        TemplateManager.templates.addAll(newTemplates)

        InstanceManager.instances.forEachIndexed { idx, instance ->
            val newItems = instance.items.map { item ->
                item.copy(definitionId = item.definitionId?.let { idMap[it] ?: it })
            }
            InstanceManager.instances[idx] = instance.copy(items = newItems)
        }
        save()
    }

    fun exportToTsv(): String {
        val sb = StringBuilder()
        val schemas = SchemaManager.sortSchemas.toList()
        
        sb.append("Label\tTemplates")
        schemas.forEach { sb.append("\t${it.name}") }
        sb.append("\n")

        val exportItems = ItemManager.itemDefinitions.toList().sortedBy { ItemManager.getSortKey(it.label) }

        exportItems.forEach { def ->
            sb.append(def.label)
            sb.append("\t")
            val assignedTemplates = TemplateManager.templates.filter { it.itemIds.contains(def.id) }.joinToString(",") { it.name }
            sb.append(assignedTemplates)
            
            schemas.forEach { schema ->
                sb.append("\t${def.sortValues[schema.id] ?: ""}")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    fun importFromTsv(tsv: String) {
        val lines = tsv.trim().split("\n")
        if (lines.isEmpty()) return
        
        val headers = lines[0].split("\t").map { it.trim() }
        if (headers.isEmpty() || headers[0] != "Label") return
        
        val hasTemplatesColumn = headers.size > 1 && headers[1] == "Templates"
        val startSchemaIndex = if (hasTemplatesColumn) 2 else 1

        val schemaNamesInTsv = headers.drop(startSchemaIndex)
        schemaNamesInTsv.forEach { name ->
            if (SchemaManager.sortSchemas.none { it.name == name }) {
                SchemaManager.addSchema(name)
            }
        }

        val rows = lines.drop(1)
        rows.forEach { rowString ->
            val cells = rowString.split("\t")
            if (cells.isNotEmpty()) {
                val labelFromSheet = cells[0].trim()
                if (labelFromSheet.isEmpty()) return@forEach
                
                val def = ItemManager.getOrCreateDefinition(labelFromSheet)
                
                val dIdx = ItemManager.itemDefinitions.indexOfFirst { it.id == def.id }
                if (dIdx != -1) {
                    if (labelFromSheet.length >= ItemManager.itemDefinitions[dIdx].label.length) {
                        ItemManager.itemDefinitions[dIdx] = ItemManager.itemDefinitions[dIdx].copy(label = labelFromSheet)
                    }
                }
                
                if (hasTemplatesColumn && cells.size > 1) {
                    val targetTemplateNames = cells[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    
                    targetTemplateNames.forEach { tName ->
                        var template = TemplateManager.templates.find { ItemManager.normalizeLabel(it.name) == ItemManager.normalizeLabel(tName) }
                        if (template == null) {
                            template = TemplateManager.createTemplate(tName)
                        } else {
                            if (tName.length >= template.name.length) {
                                val tIdx = TemplateManager.templates.indexOfFirst { it.id == template.id }
                                TemplateManager.templates[tIdx] = TemplateManager.templates[tIdx].copy(name = tName)
                                template = TemplateManager.templates[tIdx]
                            }
                        }
                        
                        val tIdx = TemplateManager.templates.indexOfFirst { it.id == template.id }
                        if (!TemplateManager.templates[tIdx].itemIds.contains(def.id)) {
                            TemplateManager.templates[tIdx] = TemplateManager.templates[tIdx].copy(itemIds = TemplateManager.templates[tIdx].itemIds + def.id)
                        }
                    }
                }

                for (i in startSchemaIndex until cells.size) {
                    if (i < headers.size) {
                        val schemaName = headers[i]
                        val schema = SchemaManager.sortSchemas.find { it.name == schemaName }
                        if (schema != null) {
                            val sortValue = cells.getOrNull(i)?.trim() ?: ""
                            ItemManager.updateSortValue(def.id, schema.id, sortValue)
                        }
                    }
                }
            }
        }
        save()
    }
}
