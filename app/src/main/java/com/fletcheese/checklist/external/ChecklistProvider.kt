package com.fletcheese.checklist.external

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.fletcheese.checklist.data.ChecklistRepository
import com.fletcheese.checklist.data.InstanceManager

/**
 * Read-only view of the lists, plus a synchronous add-item call, for TalkToMe.
 *
 *   content://com.fletcheese.checklist.provider/lists
 *     columns: _id (list UUID), name, unchecked, total, archived (0/1)
 *   ContentResolver.call(uri, "add_item", null, Bundle{list, item}) -> Bundle{result, list, item, lists?}
 *
 * Guard: the caller's UID is checked on every call. Allowed: this app, TalkToMe
 * (dev.fletcheese.talktome), adb shell (uid 2000) and root. Everyone else gets SecurityException.
 */
class ChecklistProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == SHELL_UID || uid == ROOT_UID) return
        val packages = context?.packageManager?.getPackagesForUid(uid).orEmpty()
        if (ALLOWED_PACKAGES.any { it in packages }) return
        throw SecurityException("Caller uid $uid (${packages.joinToString()}) may not use the Checklist++ provider")
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        enforceCaller()
        if (uri.pathSegments.firstOrNull() != PATH_LISTS) return null
        val ctx = context ?: return null
        val rows = ExternalCommands.onMain {
            ChecklistRepository.initialize(ctx)
            InstanceManager.instances.map { inst ->
                arrayOf<Any>(
                    inst.id, inst.name,
                    inst.items.count { !it.isChecked }, inst.items.size,
                    if (inst.isArchived) 1 else 0
                )
            }
        }
        return MatrixCursor(COLUMNS).apply { rows.forEach { addRow(it) } }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        enforceCaller()
        val ctx = context ?: return null
        return when (method) {
            METHOD_ADD_ITEM -> ExternalCommands.onMain {
                ExternalCommands.addItem(
                    ctx,
                    extras?.getString(ExternalCommands.EXTRA_LIST),
                    extras?.getString(ExternalCommands.EXTRA_ITEM)
                )
            }
            else -> Bundle().apply {
                putString(ExternalCommands.KEY_RESULT, ExternalCommands.RESULT_BAD_REQUEST)
                putString(ExternalCommands.KEY_MESSAGE, "Unknown method '$method'")
            }
        }
    }

    override fun getType(uri: Uri): String? =
        if (uri.pathSegments.firstOrNull() == PATH_LISTS) "vnd.android.cursor.dir/vnd.fletcheese.checklist.list" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    companion object {
        const val AUTHORITY = "com.fletcheese.checklist.provider"
        const val PATH_LISTS = "lists"
        const val METHOD_ADD_ITEM = "add_item"
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
        private val ALLOWED_PACKAGES = setOf("dev.fletcheese.talktome")
        private val COLUMNS = arrayOf("_id", "name", "unchecked", "total", "archived")
    }
}
