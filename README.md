# Checklist

A native Android app for building and running grocery (or any recurring) checklists, built entirely in Android Studio with Kotlin and Jetpack Compose.

## What it does

- **Templates ("recipes")** — reusable lists of items you build once and reuse, e.g. "Weekly Groceries" or "BBQ Supplies".
- **Checklist instances** — a live, working copy of a list (or a combination of several templates) that you check items off as you shop. Instances can be archived when done and unarchived to reuse.
- **Item library & sort schemas** — a shared pool of item definitions, each with per-schema sort values, so a list can be automatically ordered to match, for example, the layout of a specific store's aisles.
- **Home screen widget** — check off items directly from a widget without opening the app.
- **Google Sheets sync** — push and pull app state (schemas, items, templates, instances) as JSON to a Google Apps Script endpoint, for backup or syncing across devices.
- **TSV import/export** — quick text-based import/export of list data.

## Tech stack

- Kotlin
- Jetpack Compose + Material 3 (adaptive navigation suite)
- kotlinx.serialization for JSON persistence
- OkHttp for the Google Sheets sync integration
- Android App Widgets

## Project structure

```
app/src/main/java/com/fletcheese/checklist/
├── MainActivity.kt          # App entry point
├── data/
│   ├── Models.kt             # Serializable data models (items, templates, instances, schemas)
│   ├── ChecklistRepository.kt# Facade over the managers below + persistence
│   ├── ItemManager.kt        # Item definitions & sort values
│   ├── TemplateManager.kt    # Templates ("recipes")
│   ├── InstanceManager.kt    # Active checklist instances
│   ├── SchemaManager.kt      # Sort schemas
│   └── GoogleSheetsSync.kt   # Push/pull sync via a Google Apps Script endpoint
├── external/
│   ├── ExternalCommands.kt   # Headless add-item logic shared by the two entry points below
│   ├── AddItemReceiver.kt    # Broadcast entry point (signature permission)
│   └── ChecklistProvider.kt  # Read-only lists provider + synchronous add_item call
├── ui/
│   ├── Screens.kt            # Compose screens
│   └── theme/                # Material theme
└── widget/
    └── ChecklistWidget.kt    # Home screen widget
```

## Building

Open the project in Android Studio (Giraffe or newer) and run it, or from the command line:

```
./gradlew assembleDebug
```

Minimum SDK 25, target/compile SDK 36.

The debug build is signed with the default `~/.android/debug.keystore`. To sign with a different key (for example to update an install made from another machine), add `debug.storeFile=C:/path/to/debug.keystore` (and optionally `debug.storePassword`, `debug.keyAlias`, `debug.keyPassword`) to the gitignored `local.properties`.

## External API (TalkToMe voice commands)

Another app (TalkToMe, `dev.fletcheese.talktome`) can add items to an existing, non-archived list and read the list names, with no UI and the screen off. A list is never created implicitly. All matching lives here, in `external/VoiceMatch.kt` (unit tested in `VoiceMatchTest`), so every caller gets the same behaviour.

**Which list.** A blank `list` (or "list", "my list", "shopping list") means the **primary list**. A named list matches case-insensitively, ignoring punctuation, a leading "the"/"my", a trailing "list" and a simple plural ("the grocery list" = "Groceries"), then by likeness ("grosery"). A name that matches no active list, or two lists equally well, falls back to the primary list, and the spoken reply says so ("No walmart list. Added ...").

**The primary list** is, in order: the list pinned to a home-screen widget (if active); else the remembered primary (if still active); else the most recently changed active list, which is then remembered (`shared_prefs/VoiceAddPrefs.xml`). It is remembered so that a voice add to another list doesn't move it. `/lists` reports it in the `primary` column. To change it, pin a widget to the list you want.

**Which item.** Names are compared ignoring case, emoji, punctuation, spaces, a plural "s", leading filler ("some", "a") and bracketed parts ("Oatmilk (Oatly)" also answers to "oatmilk" and "oatly"), with a similarity of 1 minus the edit distance over the longer length. `VoiceMatch.MATCH_THRESHOLD` (0.85) is the one knob: a word of seven letters or more may be one letter out ("yoghurt" = "Yogurt"); shorter words must match exactly ("rice" is not "Ice"). The order is: an item already on the list (unchecked first), then the item library (which holds every template's items), else a new item.

Result values: `added_new` (a new item), `added_from_library` (an existing definition, so its store order carries over), `already_there` (an unchecked item matched, nothing changed), `unchecked_again` (it matched a checked item, now unchecked), `no_such_list` (no active lists at all, with `lists`), `bad_request`. Every result has `speak`, a sentence to say back as it is ("Oatmilk Oatly is already on Groceries."), plus `list_how` (`primary`, `named`, `like`, `fallback`), `score`, `heard_list` and `heard_item`.

Every write goes through the repository, which saves and refreshes the home-screen widget; an open UI sees the change at once because it observes the same Compose state.

### 1. Synchronous call through the provider (recommended)

```kotlin
val uri = Uri.parse("content://com.fletcheese.checklist.provider")
val out: Bundle? = contentResolver.call(uri, "add_item", null, bundleOf("list" to "groceries", "item" to "milk"))
out?.getString("result")   // "added_new" | "added_from_library" | "already_there" | "unchecked_again" | "no_such_list" | "bad_request"
out?.getString("list")     // the list's real name
out?.getString("item")     // the item's label as stored
out?.getString("speak")    // the sentence to say back
out?.getStringArrayList("lists") // on no_such_list
```

List names and counts: query `content://com.fletcheese.checklist.provider/lists`, columns `_id` (list id), `name`, `unchecked`, `total`, `archived` (0/1), `primary` (0/1).

Guard: the provider checks `Binder.getCallingUid()` on every call and allows only this app, the package `dev.fletcheese.talktome`, adb shell (uid 2000) and root; anyone else gets a `SecurityException`. Weakness: it trusts the package name, so any app installed under that name (only possible while the real TalkToMe is not installed) would be let in.

### 2. Ordered broadcast

```kotlin
val intent = Intent("com.fletcheese.checklist.action.ADD_ITEM")
    .setComponent(ComponentName("com.fletcheese.checklist", "com.fletcheese.checklist.external.AddItemReceiver"))
    .putExtra("list", "groceries").putExtra("item", "milk")
sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val result = resultData                  // the result string
        val extras = getResultExtras(true)       // same Bundle as the provider returns
    }
}, null, Activity.RESULT_CANCELED, null, null)
```

Alternatively put a `PendingIntent` in the extra `reply`; it is sent with the result Bundle as its extras.

Guard: the receiver requires `com.fletcheese.checklist.permission.ADD_ITEM`, `protectionLevel="signature"`, so TalkToMe must declare `<uses-permission android:name="com.fletcheese.checklist.permission.ADD_ITEM"/>` and be signed with the same key as Checklist++. adb shell cannot hold it, so test from adb with the provider (`adb shell content call ...`). If the two apps are ever signed with different keys, the broadcast stops being delivered and the provider still works.

### Caller requirements

- Android 11+ package visibility: TalkToMe needs `<queries><package android:name="com.fletcheese.checklist"/></queries>` in its manifest, or the provider and receiver are invisible to it.
- From adb:

```
adb shell content query --uri content://com.fletcheese.checklist.provider/lists
adb shell content call --uri content://com.fletcheese.checklist.provider --method add_item --extra list:s:groceries --extra item:s:milk
adb shell content call --uri content://com.fletcheese.checklist.provider --method add_item --extra list:s: --extra "item:s:oat\ milk"
```

## Status

Built for personal use and in daily use for grocery shopping for several months. No CI, tests, or contribution process — just a working personal tool.
