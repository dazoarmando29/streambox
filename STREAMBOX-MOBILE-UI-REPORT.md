# StreamBox Mobile & Tablet UI/UX Redesign — Deliverables Report

**Scope:** mobile phone + tablet web layouts ONLY (portrait + landscape). Desktop, Android TV, and the Watch Party synchronization architecture are untouched (regression report in §7). Design language preserved: Obsidian Glow dark theme, teal accents, Space Grotesk display type.

---

## 1. Mobile UI Audit — confirmed design problems

Confirmed in source (index.html CSS/markup) and on rendered screenshots (`tools/mobile-shots.js`):

| # | Problem | Root cause | Component |
|---|---|---|---|
| A1 | **Icons not centered / misaligned buttons** | Navigation, header, player-strip and modal buttons used **emoji text glyphs** (🏠🎬📺✨🌸📌☰🔍🔒📡ℹ⬇🎯⚙🙈) rendered on the *text baseline*, each platform drawing a different glyph box; mixed with inline SVGs sized ad-hoc (16/18/20/24px) | Header actions, bottom nav, mirror strip, modal action buttons, season steppers, settings toggles |
| A2 | **Crowded, unbalanced phone header** | 5 action pills (Search/Lock/Party/About/Install) beside the logo — a desktop action cluster squeezed onto phones | Header |
| A3 | **7-tab bottom nav with no hierarchy** | All 7 destinations equal-sized with emoji; "compressed desktop" feel; cramped labels at 320-390px | Bottom nav |
| A4 | **Compressed-desktop dialogs** | Modals/settings/episodes/search were centered desktop cards (`items-center p-4`) that shrink awkwardly on phones; long content scrolls inside inconsistent containers | Detail modal, Settings, Episodes, Search, Party |
| A5 | **Hero ate the phone screen** | `h-[75vh]`→62vh but 3-line overview + large CTA row; little catalog visible below | Hero |
| A6 | **Redundant text & technical leakage** | "Press C on desktop" keyboard advice shown on phones; `+`/`✓`/`☰` text prefixes duplicating obvious buttons; 5 equal party action buttons always exposed; create form showed every field at once | Subtitle coach tip, list buttons, party lobby/room |
| A7 | **Inconsistent spacing/type** | No shared scale: 10/11/12/13/14/15px labels mixed; gaps of 2/3/5/6/8/12px ad-hoc | Global |
| A8 | **Tablets = stretched phones** | 641-1024px got only a 4-col grid rule; the phone bottom bar stretched edge-to-edge; phone sheets stretched full-width | Tablet layouts |
| A9 | **Keyboard overlap risk** | No `interactive-widget=resizes-content`; chat input could stay hidden under the Android keyboard | Party chat, search |
| A10 | **Episode steppers cramped** | `‹/›` baseline glyphs in 36px buttons; season picker shared rows with filters | Episodes drawer/watch sidebar |

## 2. Changed Files

| File | Why |
|---|---|
| `index.html` | (1) **Icon system**: 29-symbol 24-grid stroke SVG sprite + `.ico/.icbtn` centering utilities; emoji replaced across nav/header/player/mirror/modals/party/settings; JS text-setters (`setListBtn`, `paintAdultLock`, `paintSpoilerBtn`, `mirrorHomeBtn`, `ctlHome`) now render icon+label so state updates never wipe glyphs. (2) **Phone header**: Logo · Search · More (lock/party/about/install moved to the shared More sheet, `md+` keeps the full cluster). (3) **Bottom nav**: 5 SVG tabs + More (KDrama/Browse/tools in one shared sheet with focus restore + Esc/Back integration + scroll lock). (4) **Phones ≤599px**: every dialog is a bottom sheet (grabber, `92dvh` scroll, safe-area padding), 58vh hero, one type scale, 4px rhythm, 3-up episode grid. (5) **Tablets 600-1279px**: centered floating pill nav, centered 860px modal, 560px settings card, 4-up grids, wider hero column. (6) **Progressive disclosure**: party Room-options expander, secondary party actions behind More, per-device subtitle hint, spoilers pill de-cluttered. (7) **Keyboard/a11y**: `interactive-widget=resizes-content`, 16px iOS input rule retained, focus restore on sheet close. |
| `tools/mobile-shots.js` | **New** screenshot audit harness (6 viewports × 7 screens + landscape, waits-for-element interactions, `SB_ROOT`/`SB_VPS` for before/after comparisons). |
| `tools/qa.js` | One lock updated to the icon+label list-button implementation (behavior-identical). |

## 3. Before/After Screenshots

Captured with `node tools/mobile-shots.js <dir>` at 320/390/430/768/1024-land × {home, details, search, player, settings, party, my-list} + phone landscape; "before" served byte-exact from the pre-redesign commit, "after" from the redesigned tree. Stored in `mobile-ui-shots/before/` and `mobile-ui-shots/after/`.

**Visual comparison notes (screens inspected side-by-side):**

| Screen | Before | After |
|---|---|---|
| Phone 390 home | 4 misaligned emoji header pills (🔍🔒📡ℹ — the party glyph visibly tilted, lock sitting high); 7 crowded emoji tabs (Browse squeezed to a hamburger glyph); footer text colliding behind the translucent bar | Header is Logo · Search · More(⋯) — balanced; bottom bar is 5 centered SVG tiles + More with one baseline per label, active tab teal + glow; content now clears the bar |
| Phone 390 details | centered floating desktop card with a large **dead artwork area** (image box empty), content crammed below, awkward margins | full-width bottom sheet: full-bleed artwork → title → metadata/genres → synopsis → **icon+label actions** (Watch Now / + My List / Watch Party) — the brief's exact order, grabber + rounded top |
| Phone 390 player | slim bar existed but `☰`/mixed glyphs baseline-shifted inside buttons | slim bar with centered SVG glyphs (play/−10/+30/Episodes/CC/tap-through + settings/fullscreen wrap row), episode button icon+label centered |
| Phone 390 settings | desktop-shaped card floating over the page | proper bottom sheet: grabber handle, rounded top, 82dvh scrollable body, gear + title + close row, tab pills |
| Phone 390 party | centered card over the page | bottom sheet with grabber; name + "Join with a code" card (big letter-spaced input); geometry measured: bottom-anchored at viewport edge, zero overflow (scrollH==clientH) |
| Tablet 768 home | phone bar stretched edge-to-edge | full labeled header cluster (Search field + Lock/Party/About/Install pills) + **centered floating pill nav** (560px, rounded, shadow) — intentional tablet layout |
| Engine/settings rows | colored emoji glyphs (🛡📺▶⏭⚡⇄🎬) mixed with SVGs | consistent stroke icons throughout (Ad Shield, TV-Safe, Autoplay, Auto-Skip, Judder, Source Mirror, Wrong Version) |
| Catalog-dependent screens (hero/rows/search) | hero 62vh, 3-line overview, full-width CTA row, unstyled title rows | hero 58vh with badge/title/meta/2-line overview + Watch·Details·List trio (catalog peeks below); Continue Watching card (thumb + title + episode + progress + secondary remove); rows with teal accent + centered SVG chevrons; search sheet — all verified in the final captured sets |

Note: during one capture window the TMDB catalog failed to load in the headless profile (transient — the Worker verified healthy immediately after); those runs show the (itself well-styled) error state and were kept in the set for honesty.

## 4. Removed Redundancies

- **Header actions on phones:** 5 pills → 2 (Search + More); Lock/Party/About/Install live in the one shared More sheet (no duplicate nav systems — header overflow and bottom-bar More open the *same* sheet).
- **Bottom nav:** 7 tabs → 5 + More; KDrama/Browse one tap away in the sheet (active state still lights More).
- **Party room screen:** 5 always-visible action buttons → 3 primary (Invite/Resync/Leave) + More (Copy Code/End); create form fields collapse behind "Room options" (visible, progressive).
- **Text prefixes removed where the icon carries meaning:** `+ My List`/`✓ In My List` → icon + "My List"/"In My List"; `☰ Episodes` → icon + "Episodes"; `▶ Watch Now` → icon + "Watch Now"; `🎯/⇄/⚙/🖱/🙈/🛡` glyph prefixes → icons or plain words.
- **Desktop-only advice hidden on phones:** "Press C on desktop…" (phones get "Open Subs & Sync to calibrate").
- **Nothing removed functionally:** every control, setting, and feature remains reachable (QA gate + click tests assert the doors).

## 5. Icon Alignment Report

- **Root cause:** emoji/text glyphs sit on the font baseline (per-platform glyph boxes, line-height coupling); inline SVGs had four different sizes and mixed centering (`grid place-items-center` vs bare inline).
- **Fix:** one sprite (`<symbol>` 34-icon 24-grid set, stroke-width 2, round caps) + `.ico{display:block;20px}` `.ico-sm/.ico-lg` + `.icbtn{inline-flex;center;gap}`; 41 use sites across markup + JS templates; buttons holding icons center via flex + `margin-inline:auto` fallback; nav tiles fixed `line-height:1`, `gap:3px`, icons 21px, labels 10px on one baseline each.
- **Verified:** season steppers, header pills, bottom-nav tiles, More-sheet tiles (identical 76px tiles), player-bar SVG buttons (16px on phones / 20px desktop), list/state buttons keep icon+label on state change, mirror-strip buttons, settings lock, party actions — all centered in the captured screenshots at every tested width.

## 6. Responsive Test Report

- **Viewports (automated):** 320/360/375(virtual)/390/414/430/600/768/800/820/834/1024/1280/1366/1440/1920/2560/3840 — `responsive-test.js`: **0 failing screens, 0 compact-target warnings** (no horizontal overflow, no clipping, overlays fit, touch targets ≥40px).
- **Screenshots (automated, inspected):** home / details / search / player / settings / party / my-list × 320, 390, 430, 768, 1024-landscape + phone-landscape player/settings.
- **Interaction regression:** `click-test.js` **15/15**; site QA gate **0 failures**.
- **Browsers:** headless Edge (Chromium) only — **not** claimed as iPhone Safari / Samsung Internet verification (see §8).
- **Harness note (honest):** two capture-harness defects were found and fixed during verification, and their early outputs discarded: (1) `tailwind.css` served as `application/octet-stream` — Chrome refuses stylesheets with wrong MIME, so those renders were unstyled; (2) the server bound a random port while the API Worker's CORS allowlist accepts `127.0.0.1:8788` only (as the house tools document), so the catalog never loaded in those runs. Both fixed (proper MIME map; port 8788 like `click-test`/`responsive-test`/`phone-shots`); before/after sets were fully re-captured styled and with the live catalog. The "before" source is byte-exact from the pre-redesign commit (an initial copy had PowerShell encoding corruption — also discarded).

## 7. Regression Report — desktop & Android TV

- Desktop nav (7 text tabs), cards, modals, player bar layout: structurally unchanged (shared glyph buttons now use the SVG set at the same sizes — no layout change).
- All new UI is scoped: phone rules live in `@media (max-width:599px)`, tablet rules in `@media (600–1279px)`; `#moreSheet` and the sheet-only markup are unreachable at `md+`/`xl` (header keeps its cluster; `mobileNav` is `xl:hidden`, so 720p/1080p TVs never see it).
- `.tv-mode`/`.tv-app` overrides untouched; TV D-pad chain (Esc/Back mapping updated additively for the new sheet), focus rings, 44→56px targets intact.
- Watch Party engine untouched (same protocol/sync code); `party-e2e.js` 27/27 remains the guard (run at release).
- **Verified:** responsive-test (includes 3840×2160 TV screens) + click-test + QA gate all green after the redesign.

## 8. Known Limitations (physical-device verification required)

1. **iPhone Safari / iPadOS:** bottom-sheet `92dvh`, keyboard pan behavior, Dynamic Island safe areas, and address-bar height changes were designed for (dvh units, `viewport-fit=cover`, safe-area padding) but verified **only in Chromium emulation** — needs a real iPhone pass.
2. **Android Chrome/WebView + Samsung Internet:** `interactive-widget=resizes-content` keyboard behavior (Android 11+ Chrome/WebView) needs on-device confirmation; older WebViews fall back to pan.
3. **Firefox Android:** fine-pointer/coarse-pointer heuristics (tap-through button) untested there.
4. **Split-screen / iPad Stage Manager** widths (320-500px tall) are covered by the landscape harness but not by real window managers.
5. Real third-party embed players inside the frame are outside our layout control (unchanged).
