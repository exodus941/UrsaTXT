# UrsaTXT

Android notebook: markdown notes in folders. Repo `exodus941/UrsaTXT`; a `vX.Y.Z` tag builds and publishes the release APK. It shares UrsaPDF's signing key, toolchain and emulator notes (memory `ursapdf.md`).

- **UrsaTXT takes its look, rules and motion from UrsaPDF 1:1.** Theme.kt, Motion.kt, Menu.kt, Modal.kt and Vignette.kt are UrsaPDF's; read `../Bear/CLAUDE.md` for its Releases, Icons, Testing, System bars and Motion rules, which apply here unchanged.
- **The editor is Yeetlist's** (`../YeeT/notes.js`, `notes-ui.js`), run in a WebView from `app/src/main/assets/editor`. `notes.js` is copied with `export` removed and the accent set to amber; port fixes from Yeetlist rather than rewriting it.
- Load `ui-layout` before any interface work.
