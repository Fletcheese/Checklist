package com.fletcheese.checklist.external

import com.fletcheese.checklist.data.ChecklistInstance
import com.fletcheese.checklist.data.ChecklistItem
import com.fletcheese.checklist.data.ItemDefinition
import com.fletcheese.checklist.external.VoiceMatch.ItemPick
import com.fletcheese.checklist.external.VoiceMatch.ListHow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMatchTest {
    private val eggs = ItemDefinition(id = "def-eggs", label = "🥚 Eggs")
    private val oatmilk = ItemDefinition(id = "def-oatmilk", label = "Oatmilk (Oatly)")
    private val bread = ItemDefinition(id = "def-bread", label = "Bread")
    private val pb = ItemDefinition(id = "def-pb", label = "Peanut Butter 🥜")
    private val yogurt = ItemDefinition(id = "def-yogurt", label = "Greek Yogurt")
    private val tomato = ItemDefinition(id = "def-tomato", label = "Tomato")
    private val rice = ItemDefinition(id = "def-rice", label = "Rice")
    private val beef = ItemDefinition(id = "def-beef", label = "Beef mince")
    private val library = listOf(eggs, oatmilk, bread, pb, yogurt, tomato, rice, beef)

    private fun item(d: ItemDefinition, checked: Boolean = false) =
        ChecklistItem(id = "it-${d.id}", label = d.label, isChecked = checked, definitionId = d.id)

    private val groceries = ChecklistInstance(
        id = "groceries", name = "Groceries", lastModified = 300,
        items = listOf(item(eggs), item(oatmilk), item(bread, checked = true)),
    )
    private val costco = ChecklistInstance(id = "costco", name = "Costco", lastModified = 200)
    private val hardware = ChecklistInstance(id = "hardware", name = "Hardware", lastModified = 400, isArchived = true)
    private val lists = listOf(groceries, costco, hardware)

    // ---- the matcher's scores, the cases that set the threshold ------------------------------------------------------

    /** Each row: spoken, stored label, whether it should count as the same item at MATCH_THRESHOLD. */
    private val cases = listOf(
        Triple("oat milk", "Oatmilk (Oatly)", true),       // spaces and the bracketed brand ignored
        Triple("oatly", "Oatmilk (Oatly)", true),          // the brand alone
        Triple("eggs", "🥚 Eggs", true),                    // emoji and case
        Triple("egg", "🥚 Eggs", true),                     // plural
        Triple("peanut butter", "Peanut Butter 🥜", true),
        Triple("bananas", "Banana", true),
        Triple("tomatoes", "Tomato", true),                 // "tomatoe" vs "tomato", 0.857
        Triple("yoghurt", "Yogurt", true),                  // one letter in seven, 0.857
        Triple("some eggs", "🥚 Eggs", true),               // leading filler
        Triple("paper towel", "Paper towels", true),
        Triple("blueberries", "Blueberry", true),
        Triple("milk", "Oatmilk (Oatly)", false),           // a shorter word inside a longer one is not it
        Triple("yogurt", "Greek Yogurt", false),            // 6 of 11 letters
        Triple("rice", "Ice", false),                       // short words must match exactly: 0.75
        Triple("beer", "Beef", false),
        Triple("pears", "Peas", false),
        Triple("apple", "Apple juice", false),
        Triple("cheddar cheese", "Cheddar", false),
        Triple("bread", "Breadcrumbs", false),
    )

    @Test
    fun thresholdCases() {
        val report = cases.joinToString("\n") { (s, l, want) ->
            "%-16s %-18s %.3f %s".format(s, l, VoiceMatch.itemScore(s, l), if (want) "match" else "no")
        }
        println("MATCH_THRESHOLD=${VoiceMatch.MATCH_THRESHOLD}\n$report")
        for ((spoken, label, want) in cases) {
            val score = VoiceMatch.itemScore(spoken, label)
            assertEquals("'$spoken' vs '$label' scored $score", want, score >= VoiceMatch.MATCH_THRESHOLD)
        }
    }

    @Test
    fun thresholdIsInTheAgreedRange() {
        assertTrue(VoiceMatch.MATCH_THRESHOLD in 0.80..0.90)
    }

    // ---- items: list first, then the library, then new --------------------------------------------------------------

    @Test
    fun oatMilkFindsTheItemAlreadyOnTheList() {
        val pick = VoiceMatch.resolveItem("oat milk", groceries, library)
        assertTrue(pick is ItemPick.OnList)
        assertEquals("Oatmilk (Oatly)", (pick as ItemPick.OnList).item.label)
    }

    @Test
    fun aCheckedItemOnTheListIsFound() {
        val pick = VoiceMatch.resolveItem("bread", groceries, library) as ItemPick.OnList
        assertTrue(pick.item.isChecked)
    }

    @Test
    fun notOnTheListComesFromTheLibrary() {
        val pick = VoiceMatch.resolveItem("peanut butter", groceries, library)
        assertEquals(pb, (pick as ItemPick.FromLibrary).definition)
    }

    @Test
    fun oatMilkOnAnotherListComesFromTheLibrary() {
        val pick = VoiceMatch.resolveItem("oat milk", costco, library)
        assertEquals(oatmilk, (pick as ItemPick.FromLibrary).definition)
    }

    @Test
    fun anUnknownWordIsANewItem() {
        val pick = VoiceMatch.resolveItem("dragon fruit", groceries, library)
        assertEquals("Dragon fruit", (pick as ItemPick.New).label)
    }

    @Test
    fun milkIsNotOatmilk() {
        val pick = VoiceMatch.resolveItem("milk", groceries, library)
        assertEquals("Milk", (pick as ItemPick.New).label)
    }

    @Test
    fun anUncheckedDuplicateBeatsACheckedOne() {
        val list = groceries.copy(items = listOf(item(eggs, checked = true).copy(id = "a"), item(eggs).copy(id = "b")))
        val pick = VoiceMatch.resolveItem("eggs", list, library) as ItemPick.OnList
        assertEquals("b", pick.item.id)
    }

    // ---- lists: named, like, primary, fallback ----------------------------------------------------------------------

    @Test
    fun noListNamedIsThePrimary() {
        for (spoken in listOf(null, "", "my list", "list", "the list", "my shopping list")) {
            val pick = VoiceMatch.resolveList(spoken, lists, emptyList())
            assertEquals("'$spoken'", groceries, pick.list)
            assertEquals(ListHow.PRIMARY, pick.how)
        }
    }

    @Test
    fun theWidgetsListIsThePrimary() {
        assertEquals(costco, VoiceMatch.primaryList(lists, listOf("costco")))
        assertEquals(costco, VoiceMatch.resolveList("my list", lists, listOf("costco")).list)
    }

    @Test
    fun anArchivedWidgetListIsNotThePrimary() {
        assertEquals(groceries, VoiceMatch.primaryList(lists, listOf("hardware")))
    }

    @Test
    fun withoutAWidgetTheMostRecentlyChangedActiveListIsThePrimary() {
        assertEquals(groceries, VoiceMatch.primaryList(lists, emptyList()))
        assertNull(VoiceMatch.primaryList(listOf(hardware), emptyList()))
    }

    @Test
    fun theRememberedPrimaryStaysPutWhenAnotherListChanges() {
        // Costco was just changed by a named voice add, so it is now the most recently changed list.
        val after = listOf(groceries, costco.copy(lastModified = 999), hardware)
        assertEquals(costco.id, VoiceMatch.primaryList(after, emptyList())?.id)
        assertEquals(groceries, VoiceMatch.primaryList(after, emptyList(), rememberedId = "groceries"))
        assertEquals(groceries, VoiceMatch.resolveList("my list", after, emptyList(), "groceries").list)
    }

    @Test
    fun aWidgetBeatsTheRememberedPrimary() {
        assertEquals(costco, VoiceMatch.primaryList(lists, listOf("costco"), rememberedId = "groceries"))
    }

    @Test
    fun anArchivedRememberedPrimaryIsReplaced() {
        assertEquals(groceries, VoiceMatch.primaryList(lists, emptyList(), rememberedId = "hardware"))
    }

    @Test
    fun aNamedListMatches() {
        assertEquals(ListPick(costco, ListHow.NAMED), VoiceMatch.resolveList("costco", lists, emptyList()))
        assertEquals(ListPick(costco, ListHow.NAMED), VoiceMatch.resolveList("the Costco list", lists, emptyList()))
        assertEquals(ListPick(groceries, ListHow.NAMED), VoiceMatch.resolveList("grocery", lists, emptyList()))
    }

    @Test
    fun aMisheardListNameMatchesByLikeness() {
        assertEquals(ListPick(groceries, ListHow.LIKE), VoiceMatch.resolveList("grosery", lists, emptyList()))
        assertEquals(costco, VoiceMatch.resolveList("cost co list", lists, emptyList()).list)
        assertEquals(ListPick(costco, ListHow.LIKE), VoiceMatch.resolveList("costcoe", lists, emptyList()))
    }

    @Test
    fun anUnknownListFallsBackToThePrimary() {
        assertEquals(ListPick(groceries, ListHow.FALLBACK), VoiceMatch.resolveList("walmart", lists, emptyList()))
    }

    @Test
    fun anArchivedListIsNotMatchedByName() {
        assertEquals(ListPick(groceries, ListHow.FALLBACK), VoiceMatch.resolveList("hardware", lists, emptyList()))
    }

    @Test
    fun aListReallyNamedListWinsMyList() {
        val named = ChecklistInstance(id = "l", name = "list", lastModified = 1)
        assertEquals(ListPick(named, ListHow.NAMED), VoiceMatch.resolveList("my list", lists + named, emptyList()))
    }

    // ---- spoken forms -----------------------------------------------------------------------------------------------

    @Test
    fun labelsAreSpokenWithoutEmojiOrBrackets() {
        assertEquals("Oatmilk Oatly", VoiceMatch.spoken("Oatmilk (Oatly)"))
        assertEquals("Eggs", VoiceMatch.spoken("🥚 Eggs"))
        assertEquals("Peanut Butter", VoiceMatch.spoken("Peanut Butter 🥜"))
    }

    @Test
    fun whatIsSaidBack() {
        assertEquals("Oatmilk Oatly is already on Groceries.",
            VoiceMatch.speak(ExternalCommands.RESULT_ALREADY_THERE, "Oatmilk (Oatly)", "Groceries", ListHow.PRIMARY, ""))
        assertEquals("Bread was ticked off. It's back on Groceries.",
            VoiceMatch.speak(ExternalCommands.RESULT_UNCHECKED_AGAIN, "Bread", "Groceries", ListHow.PRIMARY, ""))
        assertEquals("Added Peanut Butter to Costco.",
            VoiceMatch.speak(ExternalCommands.RESULT_ADDED_FROM_LIBRARY, "Peanut Butter 🥜", "Costco", ListHow.NAMED, "costco"))
        assertEquals("Added Dragon fruit to Groceries, as a new item.",
            VoiceMatch.speak(ExternalCommands.RESULT_ADDED_NEW, "Dragon fruit", "Groceries", ListHow.PRIMARY, ""))
        assertEquals("No walmart list. Added Eggs to Groceries.",
            VoiceMatch.speak(ExternalCommands.RESULT_ADDED_FROM_LIBRARY, "🥚 Eggs", "Groceries", ListHow.FALLBACK, "walmart"))
    }

    private fun ListPick(list: ChecklistInstance?, how: ListHow) = VoiceMatch.ListPick(list, how)
}
