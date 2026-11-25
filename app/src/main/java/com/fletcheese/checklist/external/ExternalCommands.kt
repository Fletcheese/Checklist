package com.fletcheese.checklist.external

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.fletcheese.checklist.data.ChecklistRepository
import com.fletcheese.checklist.data.InstanceManager
import com.fletcheese.checklist.data.ItemManager
import com.fletcheese.checklist.external.VoiceMatch.ItemPick
import com.fletcheese.checklist.widget.ChecklistWidget
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Headless entry points used by other apps (TalkToMe voice commands), shared by
 * [AddItemReceiver] and [ChecklistProvider]. All repository access happens on the main
 * thread, because the repository's lists are Compose snapshot state that the open UI observes.
 *
 * Contract (see README "External API"):
 *  - result codes: added_new / added_from_library / already_there / unchecked_again / no_such_list / bad_request
 *  - a list is never created implicitly; a blank or unmatched list name means the primary list ([VoiceMatch]).
 *  - `speak` is a sentence the caller can say back as it is.
 */
object ExternalCommands {
    const val ACTION_ADD_ITEM = "com.fletcheese.checklist.action.ADD_ITEM"
    const val EXTRA_LIST = "list"
    const val EXTRA_ITEM = "item"
    const val EXTRA_REPLY = "reply"

    const val RESULT_ADDED_NEW = "added_new"
    const val RESULT_ADDED_FROM_LIBRARY = "added_from_library"
    const val RESULT_ALREADY_THERE = "already_there"
    const val RESULT_UNCHECKED_AGAIN = "unchecked_again"
    const val RESULT_NO_SUCH_LIST = "no_such_list"
    const val RESULT_BAD_REQUEST = "bad_request"

    /** Bundle keys of a result. */
    const val KEY_RESULT = "result"
    const val KEY_LIST = "list"
    const val KEY_ITEM = "item"
    const val KEY_HEARD_LIST = "heard_list"
    const val KEY_HEARD_ITEM = "heard_item"
    const val KEY_LIST_HOW = "list_how"
    const val KEY_SCORE = "score"
    const val KEY_SPEAK = "speak"
    const val KEY_LISTS = "lists"
    const val KEY_MESSAGE = "message"

    /** Normalises a spoken or stored list name: "the Grocery List" and "groceries" both give "grocery". */
    fun listKey(name: String): String = VoiceMatch.listKey(name)

    fun activeListNames(): List<String> =
        InstanceManager.instances.filter { !it.isArchived }.map { it.name }

    private const val PREFS = "VoiceAddPrefs"
    private const val PREF_PRIMARY = "primary_list_id"

    /**
     * The primary list (see [VoiceMatch.primaryList]), remembering it when it came from neither a widget nor an earlier
     * choice, so it stays put when other lists change. Must run on the main thread.
     */
    fun primaryListId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remembered = prefs.getString(PREF_PRIMARY, null)
        val primary = VoiceMatch.primaryList(InstanceManager.instances.toList(), ChecklistWidget.widgetListIds(context), remembered)
        if (primary != null && primary.id != remembered) prefs.edit().putString(PREF_PRIMARY, primary.id).apply()
        return primary?.id
    }

    /** Must run on the main thread. */
    fun addItem(context: Context, listName: String?, itemText: String?): Bundle {
        ChecklistRepository.initialize(context)
        val heardList = listName?.trim().orEmpty()
        val heardItem = itemText?.trim().orEmpty()
        val out = Bundle().apply {
            putString(KEY_LIST, heardList); putString(KEY_ITEM, heardItem)
            putString(KEY_HEARD_LIST, heardList); putString(KEY_HEARD_ITEM, heardItem)
        }
        if (heardItem.isEmpty() || ItemManager.normalizeLabel(heardItem).isEmpty()) {
            out.putString(KEY_RESULT, RESULT_BAD_REQUEST)
            out.putString(KEY_MESSAGE, "The extra '$EXTRA_ITEM' is required")
            out.putString(KEY_SPEAK, "I didn't catch the item.")
            return out
        }
        // primaryListId first: it settles and remembers the primary before this add changes any list's lastModified.
        val primaryId = primaryListId(context)
        val pick = VoiceMatch.resolveList(heardList, InstanceManager.instances.toList(), emptyList(), primaryId)
        val instance = pick.list
        if (instance == null) {
            out.putString(KEY_RESULT, RESULT_NO_SUCH_LIST)
            out.putStringArrayList(KEY_LISTS, ArrayList(activeListNames()))
            out.putString(KEY_SPEAK, "You have no active lists in Checklist++.")
            return out
        }
        out.putString(KEY_LIST, instance.name)
        out.putString(KEY_LIST_HOW, pick.how.code)
        val item = VoiceMatch.resolveItem(heardItem, instance, ItemManager.itemDefinitions.toList())
        out.putString(KEY_SCORE, String.format(Locale.US, "%.3f", item.score))
        val result: String
        val label: String
        when (item) {
            is ItemPick.OnList -> {
                label = item.item.label
                if (item.item.isChecked) {
                    ChecklistRepository.toggleItem(instance.id, item.item.id)
                    result = RESULT_UNCHECKED_AGAIN
                } else {
                    result = RESULT_ALREADY_THERE
                }
            }
            is ItemPick.FromLibrary -> {
                label = item.definition.label
                // addItemToInstance finds this definition again by its label, so its store order carries over.
                ChecklistRepository.addItemToInstance(instance.id, label)
                result = RESULT_ADDED_FROM_LIBRARY
            }
            is ItemPick.New -> {
                label = item.label
                ChecklistRepository.addItemToInstance(instance.id, label)
                result = RESULT_ADDED_NEW
            }
        }
        out.putString(KEY_RESULT, result)
        out.putString(KEY_ITEM, label)
        out.putString(KEY_SPEAK, VoiceMatch.speak(result, label, instance.name, pick.how, heardList))
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
