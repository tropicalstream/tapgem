# TapGem

> ## ⚠️ Experimental — personal sideloaded build
> Unofficial, not a RayNeo product. Talks straight to Google's Gemini API with
> **your own key** (your usage, your bill). Widgets can fail to refresh, the
> model can mis-hear or mis-place things, and generated apps run whatever code
> the model wrote. Nothing here is fit for anything consequential.

A **voice-designed desktop for the RayNeo X3 Pro**. One tiny app, one job: you
talk to **Gemini 3.8 Live** and it builds, arranges, styles and maintains a
heads-up display *or* a full-window desktop out of widgets — text, clocks,
auto-refreshing info cards, pictures, video, audio, PDFs, ebooks, web pages,
3D models, street maps, and mini apps the model vibe-codes on the spot. It can
tile and organize the windows, and it operates web pages for you — search,
click, scroll, type, play — with no keyboard ever appearing. Desktops are saved
as thumbnails in the top strip right next to the clock, date and battery.

- **Model:** `gemini-3.8-live` (native audio, bidi) for the conversation;
  `gemini-3.8-flash` (+ Google Search) for live cards, prompt text and app
  generation; `gemini-3.1-flash-image` to paint wallpapers from words.
- **Two modes.** `hud` — black background (transparent on the waveguide),
  small panels. `desktop` — wallpaper + windows with title bars.
- **Everything persists.** Autosaved, undoable, thumbnails regenerate.
- **~15 MB APK**, no companion app, no login. The key arrives over adb.
- Bundled: three.js r128 (MIT) for the 3D viewer.

---

## Install

```bash
/opt/homebrew/bin/adb -s <X3_SERIAL> install -r /Users/me/Projects/tapgem/TapGem.apk
```

(`TapGem.apk` at the repo root is the current debug build; or
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.)

### Permissions

Microphone is requested at first launch. To skip the on-glasses dialog and to
let the media finder see files outside Pictures/Movies/Music:

```bash
adb -s <X3_SERIAL> shell pm grant com.tapgem.app android.permission.RECORD_AUDIO
adb -s <X3_SERIAL> shell pm grant com.tapgem.app android.permission.READ_EXTERNAL_STORAGE
adb -s <X3_SERIAL> shell appops set com.tapgem.app MANAGE_EXTERNAL_STORAGE allow
```

## Set your Gemini API key (adb)

Either push a file (survives reinstalls, wins over the broadcast):

```bash
echo "AIza...your-key..." > gemini_api_key.txt
adb -s <X3_SERIAL> push gemini_api_key.txt /sdcard/Android/data/com.tapgem.app/files/gemini_api_key.txt
```

or broadcast it while the app is running:

```bash
adb -s <X3_SERIAL> shell am broadcast -a com.tapgem.app.SET_API_KEY --es key "AIza...your-key..."
```

The key is never stored in this repo (`gemini_api_key.txt` is git-ignored).

---

## Controls

| Input | Does |
|---|---|
| Right trackpad slide | Moves the cursor |
| **Single tap** | Click what's under the cursor. On a window's **title bar** → that window becomes **active** (brighter bar, accent border) and comes to the front. Inside a page or app → a real tap on that element (buttons, links, players). A desktop thumbnail → loads it. The camera icon → screenshot. Empty space while idle → starts the assistant |
| **Hold, then slide** (one finger, still for ~¼ s) | On a **title bar** (or a clock / image / audio panel, or the top band of any window) → **drags the window**; the cursor follows. On the bottom-right corner → **resizes**. On the **body** of a page, app, map, book, PDF or text panel → **drags the content**: scrolls the page, pans the map, moves sliders — the cursor stays put |
| **Two fingers slide** | **Scrolls** whatever is under the cursor, no hold needed (a full stroke ≈ 300 px; flick for momentum) |
| **Park the cursor near an edge** | The content **auto-scrolls** that way: rest it in the band along a window's bottom/top (or left/right on pages and maps) and after a fifth of a second it scrolls, faster the closer to the rim, with a thin accent line marking the edge. Move the cursor away — or reach the end — and it stops. Title bars and the resize corner never scroll |
| **Double tap** | Anywhere: **start** the assistant, or **exit** it if it's running |

Every window keeps its **title bar** (drag handle, ✕ to close) and its **corner
box** (hold and drag to resize) in both HUD and desktop mode — say "hide the
title bar" for a specific window if you want it bare.
| Left arm tap | Toggle HUD ⇄ desktop mode |

The pad is a touch surface first: the tap / double-tap keys the RayNeo
firmware injects afterwards (BUTTON_A, BACK) are treated as echoes and never
close the app.

**Typing from a computer or a paired keyboard** (scrcpy, Bluetooth): while a
page or app window is active, every key you type goes straight into its
focused field — letters, backspace, arrows, Enter — with no on-screen
keyboard ever appearing. Tap the field first (cursor tap or "click on the
search box"), then type.

### The strip

Left: **camera** — one tap saves a screenshot of what you see (both video and
web content) to the photo gallery under `Pictures/TapGem`. Right: the saved
desktops as **thumbnails** (newest first, the current one ringed) right next
to the **time · date · battery · network**, then the assistant's **wave**.

### Dark, always — and the battery

Unplugged, the X3 Pro powers itself off when the waveguide is lit hard: a
**full-screen white window is enough** (every white pixel is an LED at full
power). So nothing in TapGem is allowed to be light:

- **Web pages and apps are force-darkened** (Chromium's dark rendering; the
  app runs in night mode so sites with a native dark theme use it).
- **PDF pages render inverted** — black paper, light ink.
- No theme has a light panel (**paper** is warm sepia on dark), maps are
  always the dark style, the 3D viewer is black.
- Third-party **ad and tracker requests are blocked** in web windows (fewer
  banners, less to fetch and render).

On top of that, whenever no cable is attached an **eco policy** runs: the
window is dimmed to 60 % of the system brightness (40 % under 20 % charge)
and a **luminance guard** samples the display every 4 s and dims further the
brighter it is on average (down to ~27 % on a mostly-white screen); photos,
video and painted wallpapers are dimmed a third; the wave stops animating
when idle (the app renders at 0 fps at rest); windows fully hidden behind
others have their web content paused; heavy windows come up one at a time on
start; pages may not autoplay media; the screenshot flash is skipped. Plug in
and everything returns to normal. Generated apps are asked to avoid
always-on animations, and inactive apps have their CSS animations frozen
while on battery.

(Pages are also drawn through a hardware layer: rendered straight into the
two-eye layout, Chromium saw the second draw as damage and re-rendered every
frame — a static page cost ~100 % of a core. At rest the app now renders
0 fps.)

### The wave

The tiny bar wave at the top-right is the assistant. Its colours follow the
new-Siri palette and tell you its state:

| Wave | State |
|---|---|
| dim grey dots | idle — tap to talk |
| amber pulse | connecting |
| full rainbow, bars ride your voice | **listening** |
| slow rainbow sweep | thinking / running a tool |
| blue-shifted rainbow, bars ride its voice | speaking |
| red | error (no key, no network) — see the notice under the strip |

Barge-in is on: talk over it and it stops. It ends itself after 20 s of mutual
silence, or the moment you double-tap.

**It can see the screen.** While a session is running, a small screenshot of
the display goes to the model every few seconds, and a fresh one is sent
right before every tool result that changed something — so when it says "the
video is playing", it looked. Ask it "what do you see?" or "is the radio
actually playing?" and it answers from the frame, and it will notice an
intro overlay, a login wall or an unresponsive station and press on.

---

## Talking to it

Say what you want; it calls tools and confirms in one sentence. Spatial words
work: `top left / top right / center / bottom …`, `small / medium / large /
full / half left / half right / wide / tall`. Sizes and spots are defaults you
can then adjust ("a bit bigger", "move it down").

**Widgets**
- "Put a clock in the top right." · "Add a note that says milk, eggs, coffee."
- "Show the Warriors score, refresh every five minutes." (live card)
- "Add a panel with today's top three AI headlines, update hourly." (prompt text)
- "Open the New York Times." · "Show me the Wikipedia page for Mars."
- "Make the clock bigger." · "Move the weather to the bottom left." · "Remove the news."
- "Make the note's text gold." · "Make that panel more transparent." · "Hide the title bars."
- "Rename that window to Groceries." · "Undo that."

**Windows**
- "Organize and tile my windows." · "Line them up side by side." · "Stack them." · "Cascade them."
  (tickers are strips: they stay along the bottom edge while the windows share the space above)
- "Make the video the main window." (it takes the left two thirds, the rest stack beside it)
- "Enlarge the YouTube window." · "Make this bigger." · "Shrink it." (resizes that one window only)
- "Keep the ticker on top." · "Pin this window." · "Toggle stay on top." · "Unpin it." (a lasting setting —
  a pinned window sits above every other, marked ⬆ in its title bar; "bring it to the front" only raises once)
- "Bring the clock to the front." · "Take a screenshot."

**Maps & navigation**
- "Show me a map of downtown Oakland." · "Coffee near Lake Merritt." · "Map of where I am." (Google Maps in a
  window, dark tiles; search and "ask Maps" questions go through the web tool)
- "Take me to Berkeley High School on foot." · "Drive me to SFO." → **TapGem's own turn-by-turn**: the route
  is drawn on the dark street map with the current step as a banner and the first step is read aloud.
  "Next step." · "Previous step." · "Repeat." · "Stop navigation." There is **one navigation at a time**: a new
  destination re-routes the same window; asking again for the same place just repeats the current step.
  With phone GPS the step follows where you are on the route, and **two fixes off the line re-route
  automatically** ("Off route — recalculating…", then the new first step).
- "Where am I?" — the glasses have no GPS chip, but **your phone's GPS is relayed to them**: pair the
  glasses in the RayNeo app (Bluetooth on, RayNeo app location permission set to *Always / Precise*) and
  TapGem receives the phone's real fix through RayNeo's own IPC — the same channel the built-in navigation
  uses. Without the phone, position falls back to nearby Wi-Fi (BeaconDB) or your internet connection, and
  TapGem says how rough that is. "Check phone GPS" reports what the launcher is sending. A real fix moves
  the dot and advances steps by itself (every 8 s while navigating).
- "Show me a simple map of Paris." (the clean tile map) · "Zoom in." · "Pan north." · "Recenter."

**Tickers**
- "Put a stock ticker along the bottom with the S&P, Nasdaq, Apple and Nvidia." · "A news crawl, refresh every ten minutes."

**Web pages** (search, click, scroll, type, play — verified with the Internet
Archive, YouTube, Spotify and Radio Garden; no keyboard ever pops up)
- "Search the Internet Archive for the Grateful Dead Cornell 77 show and play it."
- "Open YouTube and play lo-fi hip hop radio." · "Skip the ad." · "Pause it."
- "Open Spotify and play So What by Miles Davis." (30-second previews unless you're signed in)
- "Open Radio Garden and start playing some radio." · "Take a balloon ride." · "Next station."
- "Scroll down." · "Click on the second result." · "Type Paris into the search box." · "Go back." · "What's on this page?"

**Media** (files on the glasses, found by name)
- "Open my vacation video, bottom right." · "Play the fanfare mp3." · "Pause it." · "Mute it."
- "Open the Alice ebook." · "Next chapter." · "Go to chapter 3."
- "Show the report PDF." · "Next page."
- "Show the duck 3D model, large."
- "Open my notes text file."

Drop files in with adb — this folder is searched first:

```bash
adb -s <X3_SERIAL> push Alice.epub /sdcard/Android/data/com.tapgem.app/files/media/
```

(Pictures/Movies/Music/Downloads/Documents are searched too once All-files
access is granted, see Permissions.)

**Look**
- "Give it a neon theme." (presets: midnight, neon, paper, forest, sunset, mono, ocean, **wood** — dark walnut
  grain drawn procedurally, brass accents)
- "Make the accent orange and the panels darker." · "Bigger text everywhere."
- "Paint a wallpaper of a calm night sky over a misty pine forest." (switches to desktop mode)
- "Set the background to a purple-to-black gradient." · "Clear the wallpaper."

**Apps** (vibe-coded on the spot, run in a sandboxed WebView)
- "Build me a pomodoro timer with start, pause and reset." · "Make a dice roller." · "A unit converter for cooking."
- "Change the pomodoro to 50 minutes." (updates the existing app)

**About the session**
- "Which model are you?" · "How many tokens have we used?" — reads the server's own token accounting for the
  session (context in use of the 128k window, turns, tool calls, screen frames) and names the models. Remaining
  API *quota* is not exposed by the Gemini API; see Google AI Studio's usage page for that.

**Desktops**
- "Save this desktop as Work." · "New desktop called Reading." · "Load Work." · "Delete Reading."
- "Switch to HUD mode." · "Desktop mode." · "What's on screen?"

Saving under a name only overwrites an **exact** name match; loading/deleting
by a partial name works when it's unambiguous, otherwise it asks which one.

One request opens one window: asking again for a page, card or ticker that is
already open brings the existing one forward instead of opening a copy
("open another one" gets you a second window).

Saved desktops appear as thumbnails in the strip (newest first, current one
ringed); a single tap on a thumbnail loads it.

---

## Bench testing (no voice)

Every assistant tool can be driven over adb while a **debug** build runs (the
hooks are compiled out of release builds and guarded by `android.permission.DUMP`,
which only the adb shell holds):

```bash
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.TOOL --es name widget --es args '{\"action\":\"add\",\"type\":\"clock\",\"anchor\":\"top_right\"}'"
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.TOOL --es name desktop --es args '{\"action\":\"arrange\",\"layout\":\"grid\"}'"
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.TOOL --es name web --es args '{\"action\":\"inspect\"}'"
# start / stop the Live voice session without tapping
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.VOICE --es cmd start"
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.VOICE --es cmd stop"
# emulate the right trackpad through the real input path (x 0-638, y 0-196)
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.TRACKPAD --es cmd 'swipe 300 190 300 20'"
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.TRACKPAD --es cmd 'holddrag 300 100 400 150'"
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.TRACKPAD --es cmd 'twofinger 300 150 300 60'"
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.TRACKPAD --es cmd 'cursor 170 335'"   # place the cursor (e.g. in an edge band)
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.TRACKPAD --es cmd 'doubletap 300 100'"
# read a value out of the active page (debug builds only)
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.TOOL --es name web --es args '{\"action\":\"eval\",\"js\":\"window.scrollY\"}'"
# simulate battery power to see the eco policy kick in (and undo it)
adb -s <X3_SERIAL> shell dumpsys battery unplug
adb -s <X3_SERIAL> shell dumpsys battery reset
```

Results are logged under the `TapGemApp` tag and flashed as a notice. With a
macOS voice you can run the whole loop hands-free: start the session, then
`say -v Samantha "Organize and tile my windows"` next to the glasses.

Tools: `desktop` (describe/arrange/new/save/load/delete/list/rename/set_mode/undo/clear/screenshot),
`widget` (add/update/remove/move/resize/front/list/navigate/refresh),
`web` (inspect/read/click/type/press/scroll/play/pause/url/back/forward/reload),
`theme` (set/list), `wallpaper` (set/clear), `app_builder` (create/update),
`media` (find/open).

---

## Architecture (single module, `com.tapgem.app`)

```
MainActivity            640×480 logical viewport drawn twice (BinocularSbsLayout);
                        cursor, taps, strip (clock/battery/net/thumbnails/wave)
ui/DesktopHostView      renders the current Desktop: wallpaper + WidgetViews, move/resize
ui/WidgetView           one window: text · clock · live · ticker · image · video · audio · pdf · epub · web · app · model3d · map
ui/TickerView           the scrolling crawl (30 fps, 20 on battery)
                        (WebViews never take input focus → no keyboard; per-type sandbox; web-tool JS helper)
ui/SyntheticInput       cursor taps / tool key presses delivered as real-looking input
ui/EdgeScroller         park-the-cursor-near-an-edge auto-scroll (dwell, eased speed, stops at the end)
ui/SiriWaveView         the assistant avatar
core/model              Desktop / Widget / Theme / Wallpaper (JSON)
core/bridge/DesktopBridge   single source of truth: mutate → autosave → thumbnail; undo stack
core/store/DesktopStore     files/desktops/<id>.json + .png (atomic writes)
core/session            TapGemForegroundService + GeminiVoicePipeline (mic → Live → speaker, barge-in)
core/network            GeminiLiveClient (WebSocket + tool declarations), GeminiRest (flash / image), Geocoder (Nominatim / IP),
                        Router (OSRM turn-by-turn), WebAdBlocker
core/location           LocationSource: phone GPS (RayNeo IPC) → platform fix → last known → Wi-Fi (BeaconDB) → IP, cached and labelled
                        PhoneGps: RayNeo IPC SDK stream (GPSIPCHelper) + launcher one-shot, auto-released when idle
core/tools              desktop · widget · web · theme · wallpaper · app_builder · media (+ Layout.arrange tiling)
core/bridge/WebCommandBus   tool → live WebView commands, display capture
core/live/WidgetRefreshEngine   "how often it updates"
core/media              MediaScanner (find by name), EpubUnpacker (spine → chapters)
assets/viewer3d.html    three.js GLB/glTF/OBJ viewer
assets/map.html         OpenStreetMap tile map (dark-filtered), pin + label, pan/zoom, route line + step banner + position
```

Desktops live in `files/desktops/`, painted wallpapers in `files/wallpapers/`,
generated apps in `files/apps/` (versioned on every update so undo works),
downloads in `files/downloads/` — all app-private, all on-device; files no
saved desktop references any more are garbage-collected. Map tiles come from
OpenStreetMap (© OpenStreetMap contributors); place lookup uses Nominatim.
