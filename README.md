# UrsaTXT

A small Android notebook. Each note is a markdown file in `Documents/UrsaTXT`, in folders you make. It is UrsaPDF's sibling and looks and moves the same.

## What it does

- **Notes and folders as cards.** A folder shows how many notes it holds. A note shows its first two lines and when you last opened it. Each card carries a colour strip on its edge.
- **Pin, Rename, Move/Copy, Delete** from each card's ⋮ menu. A pinned note stays at the top. Move/Copy opens the folder tree, and you can make a new folder in it as you go.
- **Sort** by Name, Date Accessed, Date Added or Size, ascending or descending. The default is Date Accessed, newest first.
- **Search** note names and text in the current folder and the folders under it.
- **The + button** opens New Note and New Folder. Inside a folder, a button above it goes up a folder, and a path strip under the title bar takes you to any folder above.
- **The editor** is Yeetlist's: headings, bold, italic, strikethrough, code, links, lists and task lists, written to the file as markdown. It also has a colour picker, text size from 50% to 200%, and a search that marks matches in amber.
- **Share a note** as MD, HTML or TXT, copy it to the clipboard, or send it to another app.
- **Export Notes** packs every note and folder into a zip in Downloads and opens the share sheet. **Import Notes** reads one back. A note whose name is taken arrives beside it as "Name (2)".
- **Dark Mode** and **Folders First** (on by default) in the ⋮ menu, and **Check for Updates** from GitHub releases.

## Storage

The notes live in `Documents/UrsaTXT` once you allow file access. Android 11 and newer ask for All files access for that. Until you allow it, notes stay in the app's own folder, and they move to `Documents/UrsaTXT` the first time access is on. Colours, pins and dates sit in `.ursatxt.json` in that folder.

## Credits

- **[Tabler Icons](https://tabler.io/icons)** by Paweł Kuna, MIT licence: every icon in the app. Converted to Android vectors with `tools/svg2vd.py`.
- **[Five by Five](https://fontenddev.com/fonts/five-by-five/)** by jeti, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/): the "TXT" lettering in the app icon.

Full licence texts are in `THIRD_PARTY_NOTICES.md`.
