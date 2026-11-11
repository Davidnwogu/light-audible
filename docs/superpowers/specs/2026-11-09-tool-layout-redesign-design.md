# Light Audible tool: layout redesign

**Date:** 2026-11-09
**Status:** Draft for review
**Builds on:** `docs/superpowers/specs/2026-11-04-light-audible-design.md` §2.5 (the tool). This spec replaces §2.5's screen list and its "Library" and "Player" behaviour. Everything else in §2.5 (storage, playback ownership, SDK version) still applies.

## 1. Purpose

Make the tool quick to use and consistent with LightOS's own tools. The first version works, but it is clunky on the device:

- Giant `Title`-size headings ("Library", the book name over two lines) take about a third of every screen.
- The Library has no way back to what's playing, so resuming means finding the book in the list again.
- The player's controls are lines of text mixed into the chapter list.
- The chapter list appears on both the Book and Player screens.
- "Refresh" is the first row of the book list and reads like a book.
- Skips are ±30 s, while the kit's skip icons and the LightOS lock screen use ±15 s.
- The scrolled Library list draws over the search field.

### Agreed understanding

**Stated by the owner**
- The owner opens the tool to resume a book and to pick a new one about equally often, so both must be one tap from where the tool opens.
- Skips are 15 seconds.
- Chapters are used occasionally: the player shows the current chapter, and the full list is behind a button.
- The player follows the LightOS podcast tool's layout: back at top-left, centred titles, playback controls under the progress bar, other controls along the bottom.
- No narrator on the player.
- Playback speed is wanted (it was out of scope in the first version).
- A Bluetooth menu is wanted if it's straightforward; otherwise it is on hold.

**Verified against SDK v0.2.3**
- `LightBarButton(enabled = false)` draws the button lightened, so controls can be dimmed.
- `LightAudioPlayer.speed` exists and works for detached playback.
- A tool cannot reach Bluetooth: no Bluetooth permission is on the allow-list, `LightServiceMethod` has no Bluetooth or settings call, and `startActivity` and `getSystemService` are banned. Bluetooth is therefore **on hold**.
- `LightBottomBar` allows at most five items, or at most three if any item is text.

### Success criteria
From launch, resuming takes one tap (play on the Library's bottom bar) and starting a different book takes two (the book, then ▶). Every screen uses the kit's small title bar or the podcast-style player header, with no giant headings. The player reads like the LightOS podcast tool.

### Out of scope
Bluetooth (on hold until the SDK allows it), sleep timer, cover art, server changes.

## 2. Screens

Four screens: Connect, Library, Player, Chapters. The Book screen is removed.

### 2.1 Connect
Same fields and button as today: server address, 6-digit code, Connect. The giant "Connect" heading becomes a `LightTopBar` with centre text "Audiobooks".

### 2.2 Library (where the tool opens once paired)

- **Top bar** (`LightTopBar`): search icon on the left, centre text "Audiobooks", refresh icon on the right.
- **Message line**, shown only when needed, as one small line under the top bar: "Connecting…", "Can't reach the server." or "Sign in again on your setup page". The cached list stays usable.
- **List** (left-aligned), newest purchase first, as the server sends it.
  - Each row has the title, then the authors.
  - Authors drop credit suffixes (any entry containing " - ", such as "Philip Freeman - Introduction"), and show at most two names, then "+N".
  - Unplayable books are drawn lighter and can't be tapped.
  - ♪ marks the book loaded in the player. ⤓ marks a downloaded book.
  - Tapping a book opens the Player for it.
  - The list is clipped below the top bar and never draws over it.
- **Searching.** The search icon opens Light's text editor. While a search is active:
  - the top bar's centre shows the query in quotes
  - the left button becomes ✕ (clear)
  - the right button becomes the search icon (edit the query)
  - matching is case-insensitive on title or author, as today.
- **Now-playing bar** (a custom bar matching `LightBottomBar`'s height, padding and touch targets, because the kit's text item is too large to show the title and chapter; text on up to two lines, plus one icon), shown only while a book is loaded in the playback session:
  - left: text "‹title› · ‹current chapter›" (or "‹title› · Preparing…"); tapping it opens the Player
  - right: play or pause icon, which controls playback without leaving the Library; hidden while preparing.
  - With nothing loaded there is no bottom bar.

### 2.3 Player

Layout, top to bottom, all centred except the back button:

1. ‹ back, top-left.
2. Book title, bold.
3. Chapter line: the current chapter, capped at two lines with "…". While something is in progress or has failed, this line shows the status instead (§2.3.1).
4. "‹authors› · ‹length›", for example "Jordan B. Peterson · 13 h 11 min". No narrator.
5. `LightTouchableProgressBar` across the book. Dragging it seeks.
6. Controls row: skip back 15 s, play/pause, skip forward 15 s (`SKIP_BACKWARD_FIFTEEN`, `PLAY`/`PAUSE`, `SKIP_FORWARD_FIFTEEN`).
7. Current position, for example "8:10:02".
8. Bottom bar (`LightBottomBar`, three items): chapters (`LIST`), download, speed (text).

#### 2.3.1 States

| State | Chapter line | Controls row | Notes |
|---|---|---|---|
| Not playing yet (opened from the Library) | First chapter, or the chapter at the resume point | Enabled; ▶ | Position shows where ▶ will resume. Nothing loads until ▶. |
| Playing / paused | Current chapter | Enabled | |
| Preparing / Connecting / Reconnecting | "Preparing…", "Connecting…" or "Reconnecting…" | Disabled (drawn lighter) | |
| Failed | "Couldn't prepare this book" or "Couldn't play this book", with "Try again" under it | Disabled | |
| Unplayable | "This book can't be played" | Disabled | No Try again. |
| Needs sign-in | "Sign in again on your setup page" | Disabled | No Try again. |

#### 2.3.2 Bottom bar

- **Chapters** opens the Chapters screen.
- **Download**
  - not downloaded: `DOWNLOAD_ARROW`; tap to start
  - downloading: text "34%"; tap to cancel
  - downloaded: `DOWNLOADED_ARROW`; tap to show a "Remove download?" confirmation (`LightFullscreenModal`)
  - a failed download shows its message on the chapter line until the next tap.
- **Speed** is text showing the current rate. Each tap moves to the next rate: 1x → 1.25x → 1.5x → 2x → 1x. The rate applies immediately and is saved in DataStore. It carries over between books and restarts.

If Bluetooth becomes possible in a later SDK, speed must become an icon to stay within the bottom bar's limits.

### 2.4 Chapters
- `LightTopBar`: ‹ back, centre text "Chapters".
- The list of chapters, each with title and length, one line each, truncated with "…".
- It opens scrolled to the current chapter, which is marked ▸ and bold.
- Tapping a chapter seeks there and returns to the Player.

## 3. Behaviour changes

1. **Opening a book doesn't load it.** Today, Play on the Book screen calls `PlaybackSession.open(book, factory)`, which prepares and plays. Now:
   - tapping a book opens its Player in the "not playing yet" state, with no session call
   - ▶ on that screen calls `open(book, factory)`
   - whatever was playing keeps playing until then
   - the resume position shown before ▶ comes from `PositionSync.resumePoint` (best-effort; 0:00 if unavailable).
2. **The Player for the loaded book reflects the session.** Opening the Player for the book the session holds, from the Library row or the now-playing bar, attaches to the session as today. It shows live state and never reloads.
3. **Library play/pause** calls the session's toggle directly. If the session released its handle while paused, the session reopens it, which needs a `PlaybackFactory` the Library screen supplies.
4. **Skips** become ±15 s (`skip(±15_000)`).
5. **Speed** is added to `Playback` and `PlaybackControls` and applied by `LightPlayback` through `LightAudioPlayer.speed`. The session reapplies the saved rate whenever it opens a handle.
6. **The Book screen is removed** (`BookScreen`, `BookViewModel`):
   - its details move to the Player's "not playing yet" state
   - its chapter list moves to Chapters
   - Download and "Try again" move to the Player.
7. **Navigation.**
   - Connect → Library → Player → Chapters; ‹ goes back one screen.
   - Back from the Library exits the tool, as today.
   - Sign-out unwinds to Connect, as today.

## 4. Testing

- **View-model tests:**
  - Library: the states in §2.2 (normal, searching, message, nothing loaded) and the now-playing bar's text and visibility.
  - Player: every row of §2.3.1, the speed cycle and that it's saved, and the download states.
  - Chapters: the current chapter is marked.
- **Author formatting tests:** credit suffixes are dropped and lists are capped at two names plus "+N".
- **Playback session tests:**
  - opening the Player for a different book makes no session call until ▶
  - speed is applied on handle open and on change
  - Library play/pause reopens a released handle.
- **Emulator check:** fold the screens into the remaining Task 10 checklist.
