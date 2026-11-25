package com.fletcheese.checklist.external

import com.fletcheese.checklist.data.ChecklistInstance
import com.fletcheese.checklist.data.ChecklistItem
import com.fletcheese.checklist.data.ItemDefinition

/**
 * How a spoken "add X to Y" finds its list and its item. Pure Kotlin with no Android or Compose state, so it is unit
 * tested on the desktop (VoiceMatchTest).
 *
 * Item order: an item already on the target list, then a definition from the item library (every template's items are
 * library definitions, so templates are covered), else a new item.
 *
 * List order: no list named (or "my list"), the primary list; a named list, by exact name, then by likeness; a name
 * that matches nothing, or two lists equally well, falls back to the primary list.
 *
 * The primary list: the list pinned to a home-screen widget, if it is active (the most recently changed one, if several
 * widgets pin different lists); else the remembered primary, if it is still active; else the most recently changed
 * active list, which ExternalCommands then remembers. Remembering it matters: without it, a voice add to Costco would
 * make Costco the most recently changed list, and the next "add milk to my list" would go there too (seen on the
 * tablet, 2026-11-25).
 */
object VoiceMatch {
    /**
     * The one knob: how alike (0 to 1) a spoken name must be to a stored one to count as it. 0.85 lets a word of seven
     * or more letters differ by one letter ("yoghurt" for "Yogurt", "tomatoes" for "Tomato"), and makes anything
     * shorter match only exactly, after case, emoji, punctuation, spaces and a plural "s" are ignored.
     */
    const val MATCH_THRESHOLD = 0.85

    /** Spoken filler before an item: "add some eggs", "add a loaf of bread" keeps "loaf of bread". */
    private val LEADING_FILLER = setOf("a", "an", "some", "the", "more", "my")

    /** Spoken names for "no particular list". */
    private val GENERIC_LIST = setOf("", "list", "mylist", "thelist", "shopping", "shoppinglist")

    /** Lowercase letters and digits only (emoji, punctuation and spaces go), with a simple plural dropped. */
    fun key(text: String): String {
        val s = text.lowercase().filter { it.isLetterOrDigit() }
        return when {
            s.length > 4 && s.endsWith("ies") -> s.dropLast(3) + "y"
            s.length > 1 && s.endsWith("s") -> s.dropLast(1)
            else -> s
        }
    }

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

    /** The spoken item without leading filler words, as a key. */
    fun spokenKey(text: String): String {
        var words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        while (words.size > 1 && words.first().lowercase() in LEADING_FILLER) words = words.drop(1)
        return key(words.joinToString(" "))
    }

    private val BRACKETED = Regex("[(\\[{][^)\\]}]*[)\\]}]")

    /**
     * Every key a stored label answers to: the whole label, the label without bracketed parts ("Oatmilk (Oatly)" is
     * also "Oatmilk"), and each bracketed part on its own ("Oatly").
     */
    fun labelKeys(label: String): Set<String> {
        val out = linkedSetOf(key(label), key(label.replace(BRACKETED, " ")))
        BRACKETED.findAll(label).forEach { out += key(it.value) }
        return out.filter { it.isNotEmpty() }.toSet()
    }

    /** 1 minus the edit distance over the longer length; 0 when either is empty. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** How alike a spoken item is to a stored label: its best key. */
    fun itemScore(spoken: String, label: String): Double {
        val k = spokenKey(spoken)
        return labelKeys(label).maxOfOrNull { similarity(k, it) } ?: 0.0
    }

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    // ---- lists -------------------------------------------------------------------------------------------------------

    enum class ListHow(val code: String) {
        /** No list named, or "my list": the primary list. */
        PRIMARY("primary"),
        /** The name matched a list exactly (ignoring case, "the"/"my", "list" and a plural). */
        NAMED("named"),
        /** The name matched a list at [MATCH_THRESHOLD] or better. */
        LIKE("like"),
        /** A name was given but matched no list clearly, so the primary list was used. */
        FALLBACK("fallback"),
    }

    data class ListPick(val list: ChecklistInstance?, val how: ListHow)

    /** The list the home-screen widget shows; see the class comment. */
    fun primaryList(
        instances: List<ChecklistInstance>,
        widgetListIds: Collection<String>,
        rememberedId: String? = null,
    ): ChecklistInstance? {
        val active = instances.filter { !it.isArchived }
        return active.filter { it.id in widgetListIds }.maxByOrNull { it.lastModified }
            ?: active.firstOrNull { it.id == rememberedId }
            ?: active.maxByOrNull { it.lastModified }
    }

    fun resolveList(
        spoken: String?,
        instances: List<ChecklistInstance>,
        widgetListIds: Collection<String>,
        rememberedId: String? = null,
    ): ListPick {
        val active = instances.filter { !it.isArchived }
        val primary = primaryList(instances, widgetListIds, rememberedId)
        val key = listKey(spoken.orEmpty())
        // A list really named "list" still wins "my list".
        active.filter { listKey(it.name) == key && key.isNotEmpty() }.maxByOrNull { it.lastModified }
            ?.let { return ListPick(it, ListHow.NAMED) }
        if (key in GENERIC_LIST) return ListPick(primary, ListHow.PRIMARY)
        val scored = active.map { it to similarity(key, listKey(it.name)) }
        val best = scored.maxOfOrNull { it.second } ?: 0.0
        if (best >= MATCH_THRESHOLD) {
            val top = scored.filter { it.second == best }.map { it.first }
            if (top.size == 1) return ListPick(top[0], ListHow.LIKE)
            if (primary != null && primary in top) return ListPick(primary, ListHow.LIKE)
        }
        return ListPick(primary, ListHow.FALLBACK)
    }

    // ---- items -------------------------------------------------------------------------------------------------------

    sealed class ItemPick {
        abstract val score: Double

        /** Already on the list (unchecked preferred over checked). */
        data class OnList(val item: ChecklistItem, override val score: Double) : ItemPick()

        /** Not on the list, but in the item library: add that definition's label. */
        data class FromLibrary(val definition: ItemDefinition, override val score: Double) : ItemPick()

        /** Nothing alike: add the words as a new item. */
        data class New(val label: String, override val score: Double) : ItemPick()
    }

    fun resolveItem(spoken: String, list: ChecklistInstance, library: List<ItemDefinition>): ItemPick {
        val onList = list.items
            .map { it to itemScore(spoken, it.label) }
            .filter { it.second >= MATCH_THRESHOLD }
            .maxWithOrNull(compareBy<Pair<ChecklistItem, Double>> { it.second }.thenBy { if (it.first.isChecked) 0 else 1 })
        if (onList != null) return ItemPick.OnList(onList.first, onList.second)
        val fromLibrary = library
            .map { it to itemScore(spoken, it.label) }
            .filter { it.second >= MATCH_THRESHOLD }
            .maxByOrNull { it.second }
        if (fromLibrary != null) return ItemPick.FromLibrary(fromLibrary.first, fromLibrary.second)
        val best = (list.items.map { itemScore(spoken, it.label) } + library.map { itemScore(spoken, it.label) }).maxOrNull() ?: 0.0
        return ItemPick.New(newLabel(spoken), best)
    }

    /** A new item as heard, filler dropped, first letter capitalised: "some oat milk" is "Oat milk". */
    fun newLabel(spoken: String): String {
        var words = spoken.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        while (words.size > 1 && words.first().lowercase() in LEADING_FILLER) words = words.drop(1)
        return words.joinToString(" ").replaceFirstChar { it.titlecase() }
    }

    // ---- what is said back -------------------------------------------------------------------------------------------

    /** A label as it should be spoken: emoji and brackets go, "Oatmilk (Oatly)" is "Oatmilk Oatly". */
    fun spoken(label: String): String =
        label.map { if (it.isLetterOrDigit() || it == '\'' || it == '&') it else ' ' }.joinToString("")
            .trim().replace(Regex("\\s+"), " ")

    /** The sentence said back for an add_item result: what was added, matched or created, and where. */
    fun speak(result: String, label: String, listName: String, how: ListHow, heardList: String): String {
        val item = spoken(label)
        val name = spoken(listName)
        val sentence = when (result) {
            ExternalCommands.RESULT_ALREADY_THERE -> "$item is already on $name."
            ExternalCommands.RESULT_UNCHECKED_AGAIN -> "$item was ticked off. It's back on $name."
            ExternalCommands.RESULT_ADDED_FROM_LIBRARY -> "Added $item to $name."
            else -> "Added $item to $name, as a new item."
        }
        return if (how == ListHow.FALLBACK && heardList.isNotBlank()) "No ${spoken(heardList)} list. $sentence" else sentence
    }
}
