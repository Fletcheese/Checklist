package com.example.checklist.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SortSchema(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Schema"
)

@Serializable
data class ItemDefinition(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val sortValues: Map<String, String> = emptyMap()
)

@Serializable
data class ChecklistItem(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val isChecked: Boolean = false,
    val sortString: String = "",
    val definitionId: String? = null
)

@Serializable
data class ChecklistTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Recipe",
    val itemIds: List<String> = emptyList(),
    val lastModified: Long = System.currentTimeMillis(),
    val lastUsed: Long? = null
)

@Serializable
data class ChecklistInstance(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New List",
    val items: List<ChecklistItem> = emptyList(),
    val isArchived: Boolean = false,
    val lastModified: Long = System.currentTimeMillis(),
    val appliedSortSchemaId: String? = null
)

@Serializable
data class RepositoryState(
    val sortSchemas: List<SortSchema> = emptyList(),
    val itemDefinitions: List<ItemDefinition> = emptyList(),
    val templates: List<ChecklistTemplate> = emptyList(),
    val instances: List<ChecklistInstance> = emptyList(),
    val syncUrl: String? = null
)
