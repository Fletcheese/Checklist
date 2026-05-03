package com.example.checklist.data

import androidx.compose.runtime.mutableStateListOf

object ItemManager {
    val itemDefinitions = mutableStateListOf<ItemDefinition>()

    fun normalizeLabel(label: String): String {
        val skeleton = label.filter { it.isLetterOrDigit() }.lowercase()
        return if (skeleton.endsWith("s") && skeleton.length > 1) {
            skeleton.dropLast(1)
        } else {
            skeleton
        }
    }

    fun getSortKey(label: String): String {
        return label.dropWhile { !it.isLetterOrDigit() }.lowercase()
    }

    fun getOrCreateDefinition(label: String): ItemDefinition {
        val normalized = normalizeLabel(label)
        val existing = itemDefinitions.find { normalizeLabel(it.label) == normalized }
        if (existing != null) return existing
        
        val newDef = ItemDefinition(label = label.trim())
        itemDefinitions.add(newDef)
        ChecklistRepository.save()
        return newDef
    }

    fun updateDefinitionLabel(id: String, newLabel: String) {
        val index = itemDefinitions.indexOfFirst { it.id == id }
        if (index != -1) {
            itemDefinitions[index] = itemDefinitions[index].copy(label = newLabel.trim())
            
            // Sync this label update to all active checklist instances using this definition
            InstanceManager.instances.forEachIndexed { instIdx, instance ->
                val newItems = instance.items.map { item ->
                    if (item.definitionId == id) item.copy(label = newLabel.trim()) else item
                }
                InstanceManager.instances[instIdx] = instance.copy(items = newItems)
            }
            ChecklistRepository.save()
        }
    }

    fun updateSortValue(definitionId: String, schemaId: String, value: String) {
        val index = itemDefinitions.indexOfFirst { it.id == definitionId }
        if (index != -1) {
            val def = itemDefinitions[index]
            val newSortValues = def.sortValues.toMutableMap()
            newSortValues[schemaId] = value.trim()
            itemDefinitions[index] = def.copy(sortValues = newSortValues)
            
            // Sync this sort update to all active checklists using this definition and schema
            InstanceManager.instances.forEachIndexed { instIdx, instance ->
                if (instance.appliedSortSchemaId == schemaId) {
                    val newItems = instance.items.map { item ->
                        if (item.definitionId == definitionId) item.copy(sortString = value.trim()) else item
                    }.sortedWith(compareBy({ it.isChecked }, { it.sortString }, { getSortKey(it.label) }))
                    InstanceManager.instances[instIdx] = instance.copy(items = newItems)
                }
            }
            ChecklistRepository.save()
        }
    }

    fun deleteDefinition(id: String) {
        itemDefinitions.removeAll { it.id == id }
        // Also remove from all templates
        TemplateManager.templates.forEachIndexed { index, template ->
            if (id in template.itemIds) {
                TemplateManager.templates[index] = template.copy(itemIds = template.itemIds - id)
            }
        }
        ChecklistRepository.save()
    }
}
