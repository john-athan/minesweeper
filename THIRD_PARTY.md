# Third-party material in Minesweeper

This app is GPL-3.0. Its own source was written for this project. This file
records the material that came from elsewhere and the obligations attached to
it.

## Dependencies shipped inside the APK

An Android APK bundles its libraries, so the published artifact contains this
code and carries its notices. All of it is Apache-2.0, which is compatible with
GPL-3.0 in this direction (Apache-2.0 code may be combined into a GPL-3.0 work,
not the reverse).

| Library | License | Copyright |
| --- | --- | --- |
| androidx.compose (ui, ui-graphics, foundation, animation) | Apache-2.0 | Copyright (c) The Android Open Source Project |
| androidx.compose.material3 | Apache-2.0 | Copyright (c) The Android Open Source Project |
| androidx.activity:activity-compose | Apache-2.0 | Copyright (c) The Android Open Source Project |
| androidx.core:core-ktx | Apache-2.0 | Copyright (c) The Android Open Source Project |
| Kotlin standard library | Apache-2.0 | Copyright (c) JetBrains s.r.o. |

Apache-2.0 requires the license text and any NOTICE content to travel with the
binary. The Android toolchain does not do this on its own. The licenses are
therefore reachable from inside the app, which is also what F-Droid expects.

## Design language

The app follows Material 3, Google's published design guidance, and uses the
Material 3 components from the library above. The guidance itself is
documentation, not code, and is not redistributed here. "Material Design" is a
Google trademark, used to name what the app follows and nothing more.

## Icons and screenshots

`app/src/main/res/` and `fastlane/metadata/` contain the launcher icon and store
screenshots produced for this project.

## The game

Minesweeper as a rule set is an idea, not an expressive work, and is not owned
by anyone. No code, artwork or text from Microsoft's implementation or from any
other implementation is used here.

## Reviewed and cleared

Nothing yet. Findings from `scripts/provenance-check.py` that turn out to be
convergent output rather than copying belong here, with the date and the
reasoning.
