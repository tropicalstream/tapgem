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

**Free-tier quota.** A key from [Google AI Studio](https://aistudio.google.com/apikey) needs no
credit card, but it carries real request-per-minute/day limits — and the **Live API (voice,
`gemini-3.8-live`) has its own, much stricter concurrent-session cap**, separate from the
`gemini-3.8-flash` text quota the tools use. Live is usually the first thing to 429. If TapGem
says a voice session hit its quota, that is Google's real `429 RESOURCE_EXHAUSTED` relayed as-is
(TapGem never invents that message) — check
[aistudio.google.com/rate-limit](https://aistudio.google.com/rate-limit) for the live numbers on
your project. Linking a billing account (Cloud Console → Billing, on the same Google Cloud
project as the key) lifts the caps a lot and Flash is cheap for casual single-user use, but it
also **removes the free tier for that project entirely** — every call becomes billable from the
first token once billing is linked, not just the overflow. Prefer to stay card-free? Just wait
for the daily reset (midnight Pacific), or use a second Google Cloud project/key to get a second
independent free allotment.

---

## Controls

| Input | Does |
|---|---|
| Right trackpad slide | Moves the cursor |
| **Single tap** | Click what's under the cursor. On a window's **title bar** → that window becomes **active** (brighter bar, accent border) and comes to the front. Inside a page or app → a real tap on that element (buttons, links, players). A desktop thumbnail → loads it. A strip icon → opens that drawer (tap outside to close). Empty space while idle → starts the assistant |
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

Left: three **drawers**, one icon each, one look — dark glass, titled sections
of tiles, a ✕, tap outside to close:

- **Apps & widgets** (tiles) — every app the glasses hold, each opening where you
  left it (saving an app window files it here), the built-in widget kinds (clock,
  note, live card, ticker, map, weather) and launchers for the sites TapGem
  drives well (YouTube, Radio Garden, Internet Archive, Wikipedia, Google Maps,
  SoundCloud, Bandcamp). Tap anything and it lands on the current desktop.
  *"Show my apps"*.
- **Bookmarks** (ribbon) — saved pages and windows that aren't apps: a video, a
  PDF at its page, a map, a note; see *Bookmarks* below. The three drawers never
  show the same thing twice.
- **Wallpapers & themes** (picture) — every wallpaper on the glasses, titled by
  the words it was painted from, the current one ringed, *kept* ones marked;
  **Keep this** saves the current backdrop, **None** clears it, ✕ removes a
  wallpaper no desktop uses; below, the eight **theme** presets as colour
  swatches — tap to restyle the desktop. *"Show wallpapers"*, *"show themes"*.

Right: the saved
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
start; pages may not autoplay media. Plug in
and everything returns to normal. Generated apps are asked to avoid
always-on animations, and inactive apps have their CSS animations frozen
while on battery.

(Pages are also drawn through a hardware layer: rendered straight into the
two-eye layout, Chromium saw the second draw as damage and re-rendered every
frame — a static page cost ~100 % of a core. At rest the app now renders
0 fps.)

The assistant's voice never ducks or pauses for a page's audio: a radio stream restarting, a game's sound
effect or a video taking audio focus used to mute the reply mid-sentence. TapGem keeps speaking at full
volume and takes focus back on the next chunk, which is what makes the page duck instead.

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
- "Add a note that says milk, eggs, coffee."
- "Show the Warriors score, refresh every five minutes." (live card)
- "Add a panel with today's top three AI headlines, update hourly." (prompt text)
- "Open the New York Times." · "Show me the Wikipedia page for Mars."
- "Make the clock bigger." · "Move the weather to the bottom left." · "Remove the news."
- "Make the note's text gold." · "Make that panel more transparent." · "Hide the title bars."
- "Rename that window to Groceries." · "Undo that."

**Clocks** (five faces, 12/24 h, seconds, date, world cities)
- "Put a clock in the top right." · "Make it analog." · "Switch to a thin digital face." · "LED clock with
  seconds." · "Modern analog." (faces: digital, thin, led, analog, modern)
- "Use 24-hour time." · "Show seconds." · "Hide the date."
- "Add a Tokyo clock." · "World clock with London, Tokyo and New York." (one window: city rows for digital
  faces, a dial per city for analog ones, each with its offset from local) · "Add Paris to the world clock."
- "Open the clock settings." — or tap the **⚙** in any window's title bar: a sheet of tappable chips (no
  typing): clocks get face, 12/24 h, seconds, date and a city picker; live cards and tickers their refresh
  rate; every window stay-on-top, opacity and text size. Voice changes and chip taps edit the same window,
  and the sheet follows.

**Windows**
- "Organize and tile my windows." · "Line them up side by side." · "Stack them." · "Cascade them."
  (tickers are strips: they stay along the bottom edge while the windows share the space above)
- "Make the video the main window." (it takes the left two thirds, the rest stack beside it)
- "Enlarge the YouTube window." · "Make this bigger." · "Shrink it." (resizes that one window only)
- "Keep the ticker on top." · "Pin this window." · "Toggle stay on top." · "Unpin it." (a lasting setting —
  a pinned window sits above every other, marked ⬆ in its title bar; "bring it to the front" only raises once)
- "Bring the clock to the front."

**Maps & navigation**
- "Show me a map of downtown Oakland." · "Coffee near Lake Merritt." · "Map of where I am." (Google Maps in a
  window, dark tiles; search and "ask Maps" questions go through the web tool)
- "Find good restaurants on the way to Montera Middle School." — the Maps window shows the pins and the
  assistant names a few well-rated options from its own Google Search (Maps' mobile list is ads-first).
  There is one Maps window per desktop: every new place query re-points it rather than stacking maps, and
  a window showing another site (Radio Garden, YouTube) is never hijacked for it.
- "Take me to Berkeley High School on foot." · "Drive me to SFO." → **TapGem's own turn-by-turn**: the route
  is drawn on the dark street map with the current step as a banner and the first step is read aloud.
  "Next step." · "Previous step." · "Repeat." · "Stop navigation." There is **one navigation at a time**: a new
  destination re-routes the same window; asking again for the same place just repeats the current step.
- "Take me to Glenview Taqueria on the way to Montera Middle School." — **stops**: the route goes through the
  stop first (numbered orange pin, "Arrive at Glenview Taqueria — then continue to Montera Middle School"),
  and a re-route keeps the stops you haven't reached yet. Business names OpenStreetMap doesn't know are
  looked up on Google Maps (key-free: an off-screen page load, only the result URL is read), biased to
  where you are — which also rescues misheard names ("Monterey Middle School" resolves to Montera, two
  miles away, instead of a school 1,100 miles east). A bare name that only exists hundreds of miles away
  is refused with the nearby alternative ("did you mean…?") rather than routed. The route line is
  simplified by bend, not by dropping every Nth point, so it hugs the streets at every zoom.
- "Zoom out." · "Zoom in a little." · "Recenter." — on the navigation map (one request = two zoom levels,
  and your zoom survives the next step) and on a Google Maps window ("zoom in on Google Maps" changes the
  map's zoom level directly — the mobile site has no zoom buttons on place pages).
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
- "Open Radio Garden and start playing some radio." · "Take a balloon ride." · "Next station." · "Play a station from Tokyo."
- "Scroll down." · "Click on the second result." · "Type Paris into the search box." · "Go back." · "What's on this page?"

A typical request is three tool calls — *search → click → play* — because every
web action reports back **where the page is now, what is on it** (numbered
items, results ranked before site chrome, things that just appeared flagged as
*New*), **whether a dialog is covering it** and **whether the glasses are
actually making sound** (measured natively, not trusted from the page). `search`
finds the site's own search box, opens it when it hides behind an icon or a tab,
never types into archive.org's Wayback URL box, and on Radio Garden — whose
compact layout has no search field — asks the site's own API and lists the
places and stations it returns. Dialogs that block a click (Spotify's "Get the
app", Radio Garden's "Press play to start", cookie sheets) are dismissed
automatically before the click is retried; a click that changes nothing says so
("Nothing on the page changed"), and the same call issued twice in a row is
called out, so the assistant stops second-guessing itself and either moves on or
tells you what is blocking.

**Navigation HUD — "walk to the taqueria with minimap".** Add *with minimap* to any
directions request and instead of the big map you get a small window (340×210 by default,
top right): a **3-D turn arrow** for the next manoeuvre (every OSRM turn, fork, ramp, merge,
roundabout with its exit number, U-turn and arrival side has its own shape), the distance to it
in large numerals that count down between GPS fixes, the instruction with the street in bold,
a *then …* hint for the manoeuvre after, and a circular **heading-up minimap**: the streets
around you from OpenStreetMap (Overpass), the route ahead bright and behind dim, the next
turn ringed, stops and the destination marked (as rim chevrons when off the disc), north on
the rim and a fixed pointer at your position. On foot the map turns with your head (the
glasses' compass); on a bike or in a car it follows your direction of travel and never spins
with head turns; with no usable heading it falls back to route-up, then north-up. The strip
along the bottom carries ETA, distance and time left, speed or pace, the travel-mode glyph and
GPS quality. Four themes besides the default — **fallout** (Pip-Boy phosphor, scanlines,
radar minimap, amber alert mode), **synthwave** (neon arrow over a horizon grid and striped
sun), **hiking** (trail-blaze arrow, topo contours, compass rose, a sunset chip when you'll
finish near dusk) and **running** (sports-watch numerals, pace panel, lap ring, split flashes)
— chosen by voice ("… in synthwave", "switch to fallout", "hiking theme") or in the ⚙ sheet
together with the view (minimap ↔ full map), orientation (heading / course / north), minimap
zoom, arrow size and units. The window resizes from an arrow-only glyph up to the full
display, and the last theme and view you chose become the defaults next time.
"Run to …" and "hike to …" pick the running / hiking themes on their own. The page is
`app/src/main/assets/navhud.html`; `?demo=glyphs` and `?demo=route` inside it replay every
manoeuvre and a whole walk for checking themes without leaving the desk. Minimap design after
[Everyday](https://github.com/TheophileGaudin/Everyday) (heading-up vector roads, rim
indicators), re-implemented.

**IRC — "connect to Libera / EFnet / OCF", "join #ocf", "tell them …".** A built-in retro-terminal
chat client (`app/src/main/assets/irc.html`, styled after cool-retro-term's profiles: amber, green,
scanlines, pixel, apple2, vintage, dos, ibm3278, futuristic — "IRC theme scanlines") with the
connection living in the app (`core/irc/IrcClient.kt`, TLS on 6697; a certificate Android cannot
verify, as on parts of EFnet, degrades to encrypted-unverified with a note in the server tab).
Default nick `gomie_`; networks Libera.Chat, EFnet and OCF Berkeley (`irc.ocf.berkeley.edu`). Say
"join #rebuild", "leave the channel", "change my nick to …", "switch to #ocf", "what did they say in
#ocf". Dictated messages are never sent blind: "tell the channel hello" stages the words, the
assistant reads them back and asks "send it?" — only your yes transmits (the window shows the same
staged line with send / cancel buttons, and the input box works too: `/join`, `/nick`, `/msg`, `/me`).

**Discord — "open Discord", "switch to #general on the Berkeley server", "tell them …".** The same
retro-terminal window for your own Discord account (`discord.html`, `core/irc/DiscordClient.kt`),
done the way third-party terminal clients like Discordo do it: the account's auth token, the
real-time gateway over a WebSocket, messages over the REST API. **Discord's terms forbid third-party
clients on user accounts and accounts have been suspended for it — the login screen says so; use
it knowing that.** Log in by pasting the account **token** in the window (a keyboard over scrcpy works; the screen
says where the web app keeps it). There is deliberately no password login: a password login
through Discord's API from an unknown client gets the new token revoked immediately
(`4003 Not authenticated` on the gateway) and the account pushed into a forced password reset —
that happened on the first try. The token
is stored app-private on the glasses and never logged; "log out" forgets it. Servers are tabs,
channels a second row; no join/part on Discord. Voice: switch channel, list servers/channels,
"what's new in #general", and dictated messages go through the same stage → read back → "send it?"
gate as IRC.

**Interpreter — "translate what they're saying", "I need Japanese", "both ways".** A live speech
interpreter (`interpreter.html`, `core/livex/`) on Google's dedicated translation model,
`gemini-3.5-live-translate-preview`: a continuous stream, not turns — it starts speaking the
translation while the sentence is still going (about 3 s behind) and shows both transcripts,
labelled with the language it detected. Three modes: **Listen** (the room → your language, in the
glasses' speaker; speech already in your language stays silent), **Speak** (you → their language,
out loud for them), **Both** (two directions at once off the one microphone — whoever talks gets
translated the other way, nobody presses anything). 70+ languages, chosen by voice or a tap; the
source language is always auto-detected, the target is yours to pick. The interpreter and the
tutor take the microphone from the assistant: asking for one ends the assistant's turn and starts
it; tapping the desktop to talk to the assistant stops it.

**Tutor — "teach me Spanish, beginner, ordering coffee".** A spoken language lesson
(`tutor.html`) on the turn-based agent model, built around what good tutors do: comprehensible
input just above your CEFR level, no interruptions, recasts inside the reply, explicit
correction cards *after* your turn (said → better → why), a vocabulary tray with spaced review of
the words you slipped on, scenario role-play, and an end-of-lesson summary. Level, language and
scenario are chips on the window or a sentence to the assistant; the tray and your level persist.

**YouTube Music and video in a window.** music.youtube.com's player page is laid out for a
phone held upright — it reserves 408 px under the media for the controls and the Up next / Lyrics
strip — so in a 640×418 window the music video (or the album art) came out 10 px tall. TapGem
reflows that page in short viewports: the video fills the window's width with a compact
title · seek bar · buttons block floating over its foot, the album art sits above the same
block in Song mode, and the Song / Video switch stays at the top; YouTube's player only
re-measures its `<video>` on a window resize, so one is dispatched whenever the box changes
size. The Up next / Lyrics sheet, which the site closes only with a finger dragged down its
header, also closes on a tap of its selected tab or of the media strip peeking above it. A
page's own **full screen** button (YouTube's ⛶, a video's control) now fills the *window*, not
the display — "go back" leaves it, and closing the window ends it. A site's "Leave this page?"
prompt is answered automatically; a HUD has nobody to ask.

**Spotify and DRM.** The X3 Pro firmware ships only the ClearKey DRM plugin — there is no
Widevine CDM on the device (`/vendor/lib/mediadrm/` holds `libdrmclearkeyplugin.so` alone, and
`navigator.requestMediaKeySystemAccess('com.widevine.alpha')` rejects with *NotSupportedError*).
Spotify's web player needs Widevine for every full track, so once you sign in it shows
*"Playback disabled"* and nothing else; signed out it plays 30-second previews. TapGem does its
part (it grants the WebView's protected-media permission, which is what most WebView apps miss), so
if a future RayNeo firmware adds Widevine, full playback will simply start working. Until then:
sign out for previews ("sign me out of Spotify"), or ask for the song on YouTube, SoundCloud,
Bandcamp or the Internet Archive — none of them use DRM.

**Opening things** — "open / show / bring up X" means something that exists: a window on this desktop,
a bookmark, an app still on some desktop, a media file, or a website. If nothing matches, the assistant
says so and **asks before building anything** ("There's no golf game yet — want me to make one?"); it
builds only after a yes, or when you said make/build/create yourself. The check is made against your
actual words (the session's speech transcript), not the model's reading of them — so a misheard request
can't quietly turn into a new app, and a different saved app is never substituted for the one you named.

**Bookmarks** (one window kept for later — on every desktop)
- "Bookmark this." · "Save the checkers game for later." · "Bookmark the groceries note as shopping list."
- "Open my checkers bookmark." · "Show my bookmarks." · "Forget the radio bookmark."
- "Keep this wallpaper." · "Use my coral reef wallpaper on this desktop." — kept wallpapers are bookmarks
  too; they show in the wallpapers drawer marked *kept*, and apply to whichever desktop you're on.
- The ribbon opens the bookmarks drawer: thumbnails of saved windows, a **+** tile that saves the active
  window, a ✕ on each to forget it. Tap a tile and a copy lands on the current desktop at its saved size,
  with its state — an app mid-game, a page, a PDF at its page. A bookmark is not a desktop: desktops are
  whole layouts (save/load from the strip), bookmarks are single windows you drop anywhere.
- **Apps freeze and thaw by themselves.** A vibe-coded app keeps its state in `let`/`const` variables the
  page can't reach from outside, so TapGem rewrites each app as it loads: the inline scripts are scanned
  (even inside an IIFE / `DOMContentLoaded` wrapper) and a registration is appended that exposes every
  variable and function. Every 5 s, and right before a bookmark, the variables are snapshotted as JSON into
  the widget; after any reload — bookmark opened, desktop switched, app restarted — they are put back and
  the zero-argument `render…`/`update…`/`sync…` functions are called. The checkers board comes back
  mid-move whether or not the author wrote a line of persistence. Snapshots are keyed to the app's code, so
  an updated app starts clean instead of thawing stale state.

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
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.TOOL --es name web --es args '{\"action\":\"search\",\"text\":\"So What Miles Davis\"}'"
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
# put the glasses somewhere else: a simulated position wins over every real source until cleared,
# so "walk to …" routes, the HUD and the minimap can be exercised from a desk (lat,lon[,accuracy m[,speed m/s[,bearing°]]])
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.LOCATION --es fix '37.8716,-122.2727,8,1.4,90'"
adb -s <X3_SERIAL> shell "am broadcast -a com.tapgem.app.LOCATION --es fix clear"
# keep a copy of what the assistant actually said: while files/voice_tee.on exists, every PCM chunk the
# speaker played lands in files/voice_tee/<epoch>.pcm (16-bit mono, rate in the .log) with a log of write
# times and barge-in cut points — screen recordings have no audio, this is how a demo gets its real voice back
adb -s <X3_SERIAL> shell "run-as com.tapgem.app touch files/voice_tee.on"
adb -s <X3_SERIAL> shell "run-as com.tapgem.app cat files/voice_tee/<epoch>.pcm" > reply.pcm
```

Results are logged under the `TapGemApp` tag and flashed as a notice. With a
macOS voice you can run the whole loop hands-free: start the session, then
`say -v Samantha "Organize and tile my windows"` next to the glasses.

Tools: `desktop` (describe/arrange/new/save/load/delete/list/rename/set_mode/undo/clear/apps/wallpapers),
`widget` (add/update/remove/move/resize/front/list/navigate/refresh),
`web` (search/inspect/read/click/type/press/scroll/play/pause/url/back/forward/reload),
`theme` (set/list), `wallpaper` (set/clear), `app_builder` (create/update), `irc` (connect/join/part/nick/say/confirm/read/status/theme), `discord` (open/servers/channels/switch/say/confirm/read/status/theme/logout), `interpreter` (start/stop/set/read/status/languages), `tutor` (start/stop/set/status),
`media` (find/open), `bookmark` (save/open/list/delete/show/hide).

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
