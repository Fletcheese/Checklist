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

## Status

Built for personal use and in daily use for grocery shopping for several months. No CI, tests, or contribution process — just a working personal tool.
