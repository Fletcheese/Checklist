package com.fletcheese.checklist.external

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.fletcheese.checklist.data.ChecklistInstance
import com.fletcheese.checklist.data.ChecklistRepository
import com.fletcheese.checklist.data.InstanceManager
import com.fletcheese.checklist.data.ItemManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Headless entry points used by other apps (TalkToMe voice commands), shared by
 * [AddItemReceiver] and [ChecklistProvider]. All repository access happens on the main
 * thread, because the repository's lists are Compose snapshot state that the open UI observes.
 *
 * Contract (see README "External API"):
 *  - result codes: added / already_there / unchecked_again / no_such_list / bad_request
 *  - a list is matched by name only, never created implicitly.
 */
object ExternalCommands {
    const val ACTION_ADD_ITEM = "com.fletcheese.checklist.action.ADD_ITEM"
    const val EXTRA_LIST = "list"
    const val EXTRA_ITEM = "item"
    const val EXTRA_REPLY = "reply"

    const val RESULT_ADDED = "added"
    const val RESULT_ALREADY_THERE = "already_there"
    const val RESULT_UNCHECKED_AGAIN = "unchecked_again"
    const val RESULT_NO_SUCH_LIST = "no_such_list"
    const val RESULT_BAD_REQUEST = "bad_request"

    /** Bundle keys of a result. */
    const val KEY_RESULT = "result"
    const val KEY_LIST = "list"
    const val KEY_ITEM = "item"
    const val KEY_LISTS = "lists"
    const val KEY_MESSAGE = "message"

    /** Normalises a spoken or stored list name: "the Grocery List" and "groceries" both give "grocery". */
    fun listKey(name: String): String {
        var words = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        while (words.size > 1 && words.first() in setOf("the", "my")) words = words.drop(1)
        if (words.size > 1 && words.last() == "list") words = words.dropLast(1)
        val joined = words.joinToString("")
        return when {
            joined.length > 3 && joined.endsWith("ies") -> joined.dropLast(3) + "y"
            joined.length > 1 && joined.endsWith("s") -> joined.dropLast(1)
            else -> joined
        }
    }

    /** Active lists first, then archived; the most recently modified wins a tie. */
    fun findList(name: String): ChecklistInstance? {
        val key = listKey(name)
        if (key.isEmpty()) return null
        return InstanceManager.instances
            .filter { listKey(it.name) == key }
            .sortedWith(compareBy<ChecklistInstance> { it.isArchived }.thenByDescending { it.lastModified })
            .firstOrNull()
    }

    fun activeListNames(): List<String> =
        InstanceManager.instances.filter { !it.isArchived }.map { it.name }

    /** Must run on the main thread. */
    fun addItem(context: Context, listName: String?, itemText: String?): Bundle {
        ChecklistRepository.initialize(context)
        val list = listName?.trim().orEmpty()
        val item = itemText?.trim().orEmpty()
        val out = Bundle().apply { putString(KEY_LIST, list); putString(KEY_ITEM, item) }
        if (list.isEmpty() || item.isEmpty() || ItemManager.normalizeLabel(item).isEmpty()) {
            out.putString(KEY_RESULT, RESULT_BAD_REQUEST)
            out.putString(KEY_MESSAGE, "Both extras '$EXTRA_LIST' and '$EXTRA_ITEM' are required")
            return out
        }
        val instance = findList(list)
        if (instance == null) {
            out.putString(KEY_RESULT, RESULT_NO_SUCH_LIST)
            out.putStringArrayList(KEY_LISTS, ArrayList(activeListNames()))
            return out
        }
        out.putString(KEY_LIST, instance.name)
        val wanted = ItemManager.normalizeLabel(item)
        val existing = instance.items.filter { ItemManager.normalizeLabel(it.label) == wanted }
        val unchecked = existing.firstOrNull { !it.isChecked }
        val checked = existing.firstOrNull { it.isChecked }
        when {
            unchecked != null -> {
                out.putString(KEY_RESULT, RESULT_ALREADY_THERE)
                out.putString(KEY_ITEM, unchecked.label)
            }
            checked != null -> {
                ChecklistRepository.toggleItem(instance.id, checked.id)
                ChecklistRepository.unarchiveIfNeeded(instance.id)
                out.putString(KEY_RESULT, RESULT_UNCHECKED_AGAIN)
                out.putString(KEY_ITEM, checked.label)
            }
            else -> {
                ChecklistRepository.addItemToInstance(instance.id, item)
                ChecklistRepository.unarchiveIfNeeded(instance.id)
                out.putString(KEY_RESULT, RESULT_ADDED)
            }
        }
        // save() inside the repository calls already refreshes the home-screen widget.
        return out
    }

    fun <T> onMain(timeoutMs: Long = 5000, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching(block))
            latch.countDown()
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "Main thread did not respond" }
        return result.get().getOrThrow()
    }
}
