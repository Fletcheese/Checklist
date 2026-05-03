package com.example.checklist.data

import androidx.compose.runtime.mutableStateListOf

object SchemaManager {
    val sortSchemas = mutableStateListOf<SortSchema>()

    fun renameSchema(id: String, newName: String) {
        val index = sortSchemas.indexOfFirst { it.id == id }
        if (index != -1) {
            sortSchemas[index] = sortSchemas[index].copy(name = newName)
            ChecklistRepository.save()
        }
    }

    fun addSchema(name: String): SortSchema {
        val newSchema = SortSchema(name = name)
        sortSchemas.add(newSchema)
        ChecklistRepository.save()
        return newSchema
    }
}
