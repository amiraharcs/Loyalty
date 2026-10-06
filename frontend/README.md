# GRND Loyalty frontend

A React + TypeScript recreation of the user-provided coffee-shop prototype,
using Vite, Tailwind CSS, React Router and Lucide icons. Application code is
written from scratch; original public artwork and the font are retained to
match the reference. See `src/assets/README.md` for asset provenance.

## Run locally

Requires Node.js 22.12+ and npm. From `frontend/`:

```sh
npm ci
npm run dev
npm run build
npm run lint
```

Production output is written to the repository's `dist/` folder for Sites
hosting. Hosting must fall back to `index.html` for React Router paths.

## Pages

| Path | Purpose |
| --- | --- |
| `/home` | Greeting, points, streak, mascot, and Profile sheet |
| `/card` | Flippable loyalty card, demo QR, and Wallet sheet |
| `/game` | GRND Jump preview, playable game, and results |
| `/leaderboard` | Weekly and Friends demo rankings |
| `/login` | Demo email/password form |

`/` redirects to `/home`. A first-time visitor opens the demo as Haroon.
Signing out clears the demo session and routes to `/login`. A valid email
and any nonempty password start a new demo session; passwords are never saved.

## How the code fits together

- `routes/`: route definitions, session gating, and the shared app layout.
- `components/ui/`: buttons, animated numbers, and an accessible bottom sheet.
- `components/layout/`: GRND branding and the bottom navigation.
- `components/loyalty/`: shared balance/streak displays and the Profile sheet.
- `pages/`: page-specific components and composition.
- `features/session/`: a small React context for the local demo member.
- `features/leaderboard/`: opponent fixtures and score sorting.
- `features/game/`: the canvas engine, physics, rendering, inputs and lives.
- `styles/`: global layout, design tokens, fonts and small animations.
- `utils/storage.ts`: optional browser storage with graceful fallback.

Follow `App.tsx` → `AppRoutes.tsx` → `Home.tsx` to understand the main page.
Home and Profile use the same balance and streak components. Profile's
simulated scan changes the context, which updates all balance displays.

The game uses a fixed 60 Hz simulation. React renders its preview, HUD and
results; ordinary TypeScript updates the canvas player and platforms. Keeping
physics outside React avoids component updates for every moving object.

## Game controls and state

- Arrow keys or A/D steer horizontally.
- Touch position steers on a phone; supported devices also offer tilt.
- The bean bounces automatically and wraps across horizontal edges.
- White platforms are normal, orange platforms crumble, and green platforms move.
- A sugar cube activates Sugar Rush for 360 simulation steps.
- Three lives are available; each Start Game uses one. Each used life returns
  after two hours, even after refreshing the page.
- Personal best, demo weekly score, member points and lives are device-local.

## Prototype boundaries

This remains a frontend demo. The QR is decorative; contactless scanning and
Wallet are visual demos. Opponents are fixtures, the streak does not track real
visits, and there is no backend, payment integration or automatic weekly reset.
Game scores are separate from loyalty points. The prototype's prize wording
is preserved, including its different game and leaderboard reward descriptions.

The main screens retain the prototype's centred 430px layout; active gameplay
and the game-over overlay fill the viewport. Bottom sheets sit above navigation
so their controls remain usable. Reduced-motion preferences, keyboard focus,
Escape dismissal, and safe-area padding are supported.
