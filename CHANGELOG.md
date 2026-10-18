# Changelog

## 1.1.44 — unreleased

- **MIT licensed**, in the published artifact as well as the source. 1.1.43
  was published before the relicensing, so its pod and JitPack metadata still
  name the commercial licence and support@voxera.ai.
- **Session tokens.** `VoxeraConfig.sessionToken` takes a short-lived token your
  backend mints at `POST /api/v1/sessions/token`, so the app no longer has to
  ship a publishable key. A publishable key's origin allowlist means nothing to
  a native app, so anyone who extracted one from the APK could spend the
  tenant's minutes and call slots. It is sent as `sessionToken` in
  `init-session-connection`.
- `appKey` is now optional (`String?`, default `null`; `sessionToken` is the last constructor parameter, so positional calls are unaffected). Set exactly one of `appKey` or
  `sessionToken`; `VoxeraConfig` throws `IllegalArgumentException` otherwise, and when a `sessionToken` has no `sessionId` — the
  token is bound to the `sessionId` returned with it.
