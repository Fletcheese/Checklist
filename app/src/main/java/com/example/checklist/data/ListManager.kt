package com.example.checklist.data

import androidx.compose.runtime.mutableStateListOf

object ListManager {
    val instances = mutableStateListOf<ChecklistInstance>()

    fun addItemToInstance(instanceId: String, label: String) {
        val index = instances.indexOfFirst { it.id == instanceId }
        if (index != -1) {
            val instance = instances[index]
            val existingDef = ItemManager.getOrCreateDefinition(label)
            val schemaId = instance.appliedSortSchemaId
            val sortString = if (schemaId != null) existingDef.sortValues[schemaId] ?: "" else ""
            
            val newItem = ChecklistItem(
                label = label.trim(),
                sortString = sortString,
                definitionId = existingDef.id
            )
            
            val newItems = (instance.items + newItem).sortedWith(
                compareBy<ChecklistItem> { it.isChecked }
                .thenBy { it.sortString }
                .thenBy { ItemManager.getSortKey(it.label) }
            )
            
            instances[index] = instance.copy(items = newItems, lastModified = System.currentTimeMillis())
            ChecklistRepository.save()
        }
    }

    fun addTemplateToInstance(instanceId: String, templateId: String) {
        val index = instances.indexOfFirst { it.id == instanceId }
        val template = TemplateManager.templates.find { it.id == templateId }
        if (index != -1 && template != null) {
            val instance = instances[index]
            val schemaId = instance.appliedSortSchemaId
            
            val newItemsFromTemplate = template.itemIds.map { itemId ->
                val def = ItemManager.itemDefinitions.find { it.id == itemId }
                ChecklistItem(
                    label = def?.label ?: "Unknown",
                    sortString = if (schemaId != null) def?.sortValues?.get(schemaId) ?: "" else "",
                    definitionId = itemId
                )
            }
            
            val combinedItems = (instance.items + newItemsFromTemplate).distinctBy { ItemManager.normalizeLabel(it.label) }
            val sortedItems = combinedItems.sortedWith(
                compareBy<ChecklistItem> { it.isChecked }
                .thenBy { it.sortString }
                .thenBy { ItemManager.getSortKey(it.label) }
            )
            
            instances[index] = instance.copy(items = sortedItems, lastModified = System.currentTimeMillis())
            
            val tIdx = TemplateManager.templates.indexOfFirst { it.id == templateId }
            TemplateManager.templates[tIdx] = TemplateManager.templates[tIdx].copy(lastUsed = System.currentTimeMillis())
            
            ChecklistRepository.save()
        }
    }

    fun updateItemLabelInInstance(instanceId: String, itemId: String, newLabel: String) {
        val index = instances.indexOfFirst { it.id == instanceId }
        if (index != -1) {
            val instance = instances[index]
            val itemIdx = instance.items.indexOfFirst { it.id == itemId }
            if (itemIdx != -1) {
                val item = instance.items[itemIdx]
                val newItems = instance.items.toMutableList()
                newItems[itemIdx] = item.copy(label = newLabel.trim())
                
                // If linked to definition, update the global definition too
                item.definitionId?.let { defId ->
                    ItemManager.updateDefinitionLabel(defId, newLabel)
                }
                
                instances[index] = instance.copy(items = newItems, lastModified = System.currentTimeMillis())
                ChecklistRepository.save()
            }
        }
    }

    fun createInstanceFromTemplates(name: String, templateIds: List<String>, schemaId: String?): ChecklistInstance {
        val selectedTemplates = TemplateManager.templates.filter { it.id in templateIds }
        val allItemIds = selectedTemplates.flatMap { it.itemIds }.distinct()
        
        val items = allItemIds.map { itemId ->
            val def = ItemManager.itemDefinitions.find { it.id == itemId }
            val label = def?.label ?: "Unknown"
            val sortString = if (schemaId != null) def?.sortValues?.get(schemaId) ?: "" else ""
            ChecklistItem(
                label = label,
                sortString = sortString,
                definitionId = itemId
            )
        }.sortedWith(compareBy({ it.isChecked }, { it.sortString }, { ItemManager.getSortKey(it.label) }))

        val newInstance = ChecklistInstance(
            name = name,
            items = items,
            appliedSortSchemaId = schemaId
        )
        instances.add(0, newInstance)
        
        templateIds.forEach { tid ->
            val idx = TemplateManager.templates.indexOfFirst { it.id == tid }
            if (idx != -1) {
                TemplateManager.templates[idx] = TemplateManager.templates[idx].copy(lastUsed = System.currentTimeMillis())
            }
        }
        
        ChecklistRepository.save()
        return newInstance
    }

    fun toggleItem(instanceId: String, itemId: String) {
        val index = instances.indexOfFirst { it.id == instanceId }
        if (index != -1) {
            val instance = instances[index]
            val newItems = instance.items.map {
                if (it.id == itemId) it.copy(isChecked = !it.isChecked) else it
            }
            
            val sortedItems = newItems.sortedWith(
                compareBy<ChecklistItem> { it.isChecked }
                .thenBy { it.sortString }
                .thenBy { ItemManager.getSortKey(it.label) }
            )

            instances[index] = instance.copy(
                items = sortedItems,
                lastModified = System.currentTimeMillis()
            )
            ChecklistRepository.save()
        }
    }
    
    fun completeAll(instanceId: String) {
        val index = instances.indexOfFirst { it.id == instanceId }
        if (index != -1) {
            val instance = instances[index]
            val newItems = instance.items.map { it.copy(isChecked = true) }
            instances[index] = instance.copy(items = newItems, isArchived = true, lastModified = System.currentTimeMillis())
            ChecklistRepository.save()
        }
    }

    fun removeCompleted(instanceId: String) {
        val index = instances.indexOfFirst { it.id == instanceId }
        if (index != -1) {
            val instance = instances[index]
            val newItems = instance.items.filter { !it.isChecked }
            instances[index] = instance.copy(items = newItems, lastModified = System.currentTimeMillis())
            ChecklistRepository.save()
        }
    }

    fun unarchiveIfNeeded(instanceId: String) {
        val index = instances.indexOfFirst { it.id == instanceId }
        if (index != -1) {
            val instance = instances[index]
            val anyUnchecked = instance.items.any { !it.isChecked }
            if (anyUnchecked && instance.isArchived) {
                instances[index] = instance.copy(isArchived = false)
                ChecklistRepository.save()
            }
        }
    }

    fun archiveInstance(id: String) {
        val index = instances.indexOfFirst { it.id == id }
        if (index != -1) {
            instances[index] = instances[index].copy(isArchived = true, lastModified = System.currentTimeMillis())
            ChecklistRepository.save()
        }
    }

    fun unarchiveInstance(id: String) {
        val index = instances.indexOfFirst { it.id == id }
        if (index != -1) {
            instances[index] = instances[index].copy(isArchived = false, lastModified = System.currentTimeMillis())
            ChecklistRepository.save()
        }
    }
}
