import { n, e, g } from '../dsl.mjs';

const loc = (id, y, title, line) => n(id, 'part', 760, y, 180, 64, title, line ? [line] : []);

export default {
  slug: 'ui',
  title: 'spire-ui',
  kicker: 'Two things in one image: the dashboard, and the nginx that is the only way in.',
  summary: 'The image serves the React dashboard as static files. Its nginx also sends each URL prefix to the right service, so the browser sees one origin and each service keeps its own session cookie.',
  size: [1440, 770],
  groups: [
    g('app', 40, 96, 580, 440, 'The React app: it ships in the image and runs in the browser', { subject: true }),
    g('ngx', 740, 96, 220, 560, 'nginx in the image', { subject: true }),
  ],
  nodes: [
    n('screens', 'part', 60, 130, 540, 64, 'Screens', ['reviews, work items, runs, analytics, settings']),
    n('cache', 'part', 60, 240, 540, 60, 'React Query cache', ['one copy of each server answer']),
    n('sock', 'part', 60, 350, 170, 70, 'useLiveSocket', ['the only socket client']),
    n('live', 'part', 245, 350, 170, 70, 'liveCache.ts', ['puts frames in the cache']),
    n('api', 'part', 430, 350, 170, 70, 'api.ts', ['REST reads and writes']),
    n('auth', 'part', 60, 450, 170, 64, 'auth.ts, useMe', ['one session per prefix']),

    n('idp', 'ext', 60, 600, 170, 64, 'Identity provider', ['OIDC']),
    n('forges', 'ext', 380, 560, 220, 64, 'Forges', ['GitHub, GitLab, Bitbucket Cloud']),

    loc('st', 130, 'Static files', '/ falls back to index.html'),
    loc('lapi', 230, '/api', 'read timeout 3600 s'),
    loc('lwk', 330, '/wk'),
    loc('lgw', 450, '/gw', 'read timeout 3600 s'),
    loc('lwh', 560, '/webhooks', 'placed before the fallback'),

    n('orch', 'service', 1150, 215, 250, 94, 'spire-orchestrator', ['almost all REST,', 'five WebSocket feeds']),
    n('wk', 'service', 1150, 320, 250, 84, 'spire-review-worker', ['the context card']),
    n('gw', 'service', 1150, 440, 250, 194, 'spire-gateway', ['webhook registrations,', 'one WebSocket feed,', 'forge webhooks']),
  ],
  edges: [
    e('screens', 'cache', 'sync', 'useQuery'),
    e('sock', 'live', 'sync'),
    e('live', 'cache', 'sync', 'frames'),
    e('api', 'cache', 'sync', 'answers', { bo: 185 }),
    e('auth', 'idp', 'sync', 'redirect'),
    e('app', 'ngx', 'sync', 'HTTPS, one origin', { bo: -60 }),
    e('forges', 'lwh', 'sync', 'webhook POST'),
    e('lapi', 'orch', 'sync'),
    e('lwk', 'wk', 'sync'),
    e('lgw', 'gw', 'sync', '', { bo: -55 }),
    e('lwh', 'gw', 'sync', '', { bo: 55 }),
  ],
  sections: [
    { h: 'What it is responsible for', items: [
      'It shows reviews, work items, approvals, agent runs and analytics, live.',
      'It gives an admin the settings screens: accounts, repositories, LLM providers, harness credentials, webhooks, prompts, dead letters.',
      'Its nginx is the single entry of the packaged system. It also receives the forge webhooks and passes them to the gateway.',
    ] },
    { h: 'How the app gets data', items: [
      'There is one way. React Query keeps each server answer, and a WebSocket frame says that something changed. No screen polls.',
      '`api.ts` wraps every REST call. An auth failure (HTTP 499) starts a sign-in for the prefix that refused.',
      '`useLiveSocket` is the only place that opens a socket. After a drop it checks the session, then connects again after 1.5 s.',
      '`liveCache.ts` is the only place where a frame reaches the cache. It first cancels a REST read of the same key, so an old answer cannot replace a new frame.',
    ] },
    { h: 'Where each prefix goes', wide: true, head: ['Prefix', 'Service', 'What uses it'], rows: [
      ['`/api`', '`spire-orchestrator`', 'Almost every REST call, and the sockets `/api/ws/reviews`, `/api/ws/runs`, `/api/ws/runs/transcript`, `/api/ws/work-items`, `/api/ws/attention`.'],
      ['`/gw`', '`spire-gateway`', 'The webhook registrations (`/gw/webhook-repos`) and the socket `/gw/ws/webhook-attention`.'],
      ['`/wk`', '`spire-review-worker`', 'One read: `/wk/review-context/...`, for the context card of a review.'],
      ['`/webhooks`', '`spire-gateway`', 'Forge webhooks. This is the only public path that needs no sign-in.'],
      ['`/`', 'static files', 'The React bundle. The app uses hash routes (`#/...`), so every page is `index.html`.'],
    ] },
    { h: 'Sign-in, from the browser', items: [
      '`useMe` reads `/api/me` once. If a sign-in is needed, the browser goes to `/api/auth/login?chain=1`.',
      'Each service is its own OIDC client, and its cookie is scoped to its prefix: `/api`, `/gw`, `/wk`.',
      'After the `/api` session exists, the app checks `/gw/auth/login` and `/wk/auth/login`, and chains one more sign-in if a session is missing.',
      'Sign-out posts to `/gw/auth/logout` and `/wk/auth/logout`, then goes to `/api/auth/logout`, which also ends the session at the identity provider.',
    ] },
    { h: 'Built with', p: [
      'React 19, react-router 8 with hash routes, TanStack React Query 5, Vite 8, TypeScript, Vitest. Icons are lucide-react.',
      'The image is nginx-unprivileged 1.30 and listens on 8080. In development, the Vite proxy does the job of nginx.',
    ] },
    { h: 'Coupling to watch', wide: true, items: [
      'The attention bell merges two sockets in the browser: one from the orchestrator, one from the gateway.',
      'A new backend status is not seen by the TypeScript types. The status arrives as JSON at run time, so an unlisted value can show as success.',
      'The three upstream URLs are settings of this image (`SPIRE_GATEWAY_URL` and the two others). The same image works in Compose and in Kubernetes because the routing stays inside it.',
      'In nginx, a `location` that sets one proxy header loses all headers from the server block. All headers are set once, on the server block.',
    ] },
  ],
};
