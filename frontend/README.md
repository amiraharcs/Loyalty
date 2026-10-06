# Loyalty frontend

Minimal React + TypeScript frontend built with Vite, Tailwind CSS, React Router,
and ESLint. The temporary Home page is available at `/home`; `/` redirects there.

Requires Node.js 22.12+ and npm.

```sh
npm ci
npm run dev
```

Run these commands from `frontend/`:

```sh
npm run build
npm run lint
npm run preview
```

Source folders:

- `assets/`: static assets
- `components/`: shared components
- `pages/`: page components
- `routes/`: route definitions
- `styles/`: global styles and Tailwind import
- `types/`: shared TypeScript types
- `utils/`: shared utilities

Production hosting must serve `index.html` for application routes such as `/home`.
