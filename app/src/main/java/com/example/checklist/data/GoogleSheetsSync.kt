package com.example.checklist.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object GoogleSheetsSync {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonConfig = Json { ignoreUnknownKeys = true }

    suspend fun pushToSheet(scriptUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val state = RepositoryState(
                sortSchemas = SchemaManager.sortSchemas.toList(),
                itemDefinitions = ItemManager.itemDefinitions.toList(),
                templates = TemplateManager.templates.toList(),
                instances = InstanceManager.instances.toList(),
                syncUrl = ChecklistRepository.syncUrl
            )
            val json = Json.encodeToString(state)
            val body = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(scriptUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("GoogleSheetsSync", "Push failed", e)
            false
        }
    }

    suspend fun pullFromSheet(scriptUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(scriptUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return@withContext false
                    val newState = jsonConfig.decodeFromString<RepositoryState>(json)
                    
                    withContext(Dispatchers.Main) {
                        ChecklistRepository.importState(newState)
                    }
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            Log.e("GoogleSheetsSync", "Pull failed", e)
            false
        }
    }
}
