<img src="docs/icon.svg" width="128" height="128" alt="UrsaTXT icon: a black bear head over a box reading TXT, on amber">

# UrsaTXT

A small, 'bear minimum' notebook for Android 8+. Every note is a markdown file in `Documents/UrsaTXT`, in folders you make. No libraries beyond the Kotlin standard library in the app itself; it is UrsaPDF's sibling and looks and moves the same.

- Home: notes and folders as cards, each with a colour strip on its edge. A note shows its first two lines and when it was last opened; a folder shows how many notes and folders it holds. A name and text search (filters as you type, through the folders below too) stays pinned at the top; a soft shadow shows where the list runs under it or off the screen. The app's version sits beside its name.
- Each card's ⋮ menu pins it (a pinned note stays at the top), renames it, moves or copies it, or deletes it. **Move/Copy** shows the folder tree from Root; pick a folder and **Create New Folder Here** opens under it, to name a new folder in place (Enter keeps it, Back drops it).
- The sort icon picks Name, Date Accessed, Date Added or Size, ascending or descending; Date Accessed, newest first, is the default. The ⋮ menu holds **Dark Mode**, **Folders First** (on by default), **Export Notes**, **Import Notes** and **Check for Updates** (updates also install themselves from the latest release here).
- The + button, with a slow amber pulse, opens **New Note** and **New Folder**. Inside a folder, a button above it goes up a folder, Back does the same, and a path strip under the title bar jumps to any folder above.
- Editor: Yeetlist's. Body text, six heading levels and code blocks from a dropdown; bold, italic and lists on the bar; strikethrough, code and links under the dots button. "- ", "1. " and "[ ] " start lists and task lists. A note with no name takes its first line when closed. The colour square picks one of sixteen strip colours, the text size runs from 50% to 200%, and search marks every match in amber.
- Share a note as MD, HTML or TXT (saved to Downloads too), copy it to the clipboard, or send it to another app.
- Backup: **Export Notes** packs every note and folder into one zip in Downloads and opens the share sheet; **Import Notes** reads one back, and a note whose name is taken arrives beside it as "Name (2)".
- Storage: Android 11 and newer ask for All files access to keep notes in `Documents/UrsaTXT`. Until it is allowed, notes stay in the app's own folder and move over the first time it is. Colours, pins and dates sit in `.ursatxt.json` there.

## Build

```
./gradlew assembleRelease
```

Release signing reads `URSA_KS_PATH` and `URSA_KS_PASS` from the environment or from `keystore.properties` (not in git).

## Release

Push a tag `vX.Y.Z`. The workflow builds a signed APK, attaches it to a GitHub release and deletes every older release, so only the newest exists.

## Credits

- **[Tabler Icons](https://tabler.io/icons)** by Paweł Kuna, MIT licence: every icon in the app. Converted to Android vectors with `tools/svg2vd.py`.
- **[Five by Five](https://fontenddev.com/fonts/five-by-five/)** by jeti, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/): the "TXT" lettering in the app icon.

Full licence texts are in `THIRD_PARTY_NOTICES.md`.
