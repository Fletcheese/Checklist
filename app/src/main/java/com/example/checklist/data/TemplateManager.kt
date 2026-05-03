package com.example.checklist.data

import androidx.compose.runtime.mutableStateListOf

object TemplateManager {
    val templates = mutableStateListOf<ChecklistTemplate>()

    fun createTemplate(name: String): ChecklistTemplate {
        val newTemplate = ChecklistTemplate(name = name)
        templates.add(0, newTemplate)
        ChecklistRepository.save()
        return newTemplate
    }

    fun deleteTemplate(id: String) {
        templates.removeAll { it.id == id }
        ChecklistRepository.save()
    }

    fun updateTemplateName(id: String, newName: String) {
        val index = templates.indexOfFirst { it.id == id }
        if (index != -1) {
            if (newName.length >= templates[index].name.length) {
                templates[index] = templates[index].copy(name = newName)
                ChecklistRepository.save()
            }
        }
    }

    fun addItemToTemplate(templateId: String, label: String) {
        val def = ItemManager.getOrCreateDefinition(label)
        val index = templates.indexOfFirst { it.id == templateId }
        if (index != -1) {
            val template = templates[index]
            if (def.id !in template.itemIds) {
                templates[index] = template.copy(
                    itemIds = template.itemIds + def.id,
                    lastModified = System.currentTimeMillis()
                )
                ChecklistRepository.save()
            }
        }
    }

    fun removeItemFromTemplate(templateId: String, definitionId: String) {
        val index = templates.indexOfFirst { it.id == templateId }
        if (index != -1) {
            val template = templates[index]
            templates[index] = template.copy(
                itemIds = template.itemIds - definitionId,
                lastModified = System.currentTimeMillis()
            )
            ChecklistRepository.save()
        }
    }
}
