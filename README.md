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
app/src/main/java/com/example/checklist/
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

Another app (TalkToMe, `dev.fletcheese.talktome`) can add items to an existing list and read the list names, with no UI and the screen off. A list is never created implicitly. List names match case-insensitively, ignoring punctuation, a leading "the"/"my", a trailing "list" and a simple plural ("the grocery list" = "Groceries"). Item names match the way the app already de-duplicates items (letters and digits only, case-insensitive, trailing "s" ignored).

Result values: `added`, `already_there` (an unchecked item with that name exists, nothing changed), `unchecked_again` (it existed checked, now unchecked), `no_such_list` (with `lists`, the active list names), `bad_request`.

Every write goes through the repository, which saves and refreshes the home-screen widget; an open UI sees the change at once because it observes the same Compose state.

### 1. Synchronous call through the provider (recommended)

```kotlin
val uri = Uri.parse("content://com.fletcheese.checklist.provider")
val out: Bundle? = contentResolver.call(uri, "add_item", null, bundleOf("list" to "groceries", "item" to "milk"))
out?.getString("result")   // "added" | "already_there" | "unchecked_again" | "no_such_list" | "bad_request"
out?.getString("list")     // the list's real name when matched
out?.getString("item")     // the item's stored label when it already existed
out?.getStringArrayList("lists") // on no_such_list
```

List names and counts: query `content://com.fletcheese.checklist.provider/lists`, columns `_id` (list id), `name`, `unchecked`, `total`, `archived` (0/1).

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
```

## Status

Built for personal use and in daily use for grocery shopping for several months. No CI, tests, or contribution process — just a working personal tool.
