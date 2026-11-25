# Security

## What is being protected

The shim's output is an assertion to SSPR: "Duo verified this user." SSPR acts on it by letting the user set a new password. A forged or misdirected assertion therefore means **account takeover of any user who can reset their password**. Everything below exists to make sure the shim only asserts a username that Duo verified for that very transaction.

## Actors and trust

| Actor | Trust |
|---|---|
| SSPR server | Trusted OAuth client. Authenticated with `sspr.clientId` and `sspr.clientSecret`. Chooses which user to verify (via `/sign`). |
| User's browser | Untrusted. Carries handles, state and codes between parties, but can't choose the username. |
| Duo | Trusted to verify the second factor according to the application policy. Its results are signed and checked by the SDK. |
| Network | Untrusted. Everything runs over HTTPS. |
| Shim host and config file | Trusted. They hold the Duo client secret and the SSPR client secret. |

## Threats and checks

| Threat | Check |
|---|---|
| Attacker picks the username in the browser | No endpoint takes a username from the browser. `/oauth/auth` gets the user only from a handle that SSPR created at `/sign`, which needs client authentication. |
| Duo approval for user A used to finish user B's reset | The transaction stores the username. Duo's code exchange is bound to that username and a per-transaction nonce (verified by the SDK). The shim then checks again that Duo's `preferred_username` matches, ignoring case only. A unit test covers this case. |
| Replay of handles, transactions, codes or tokens | Every one is single use: atomically removed from the store when read. Short lifetimes: handle 2 min, Duo transaction 5 min (configurable), code 60 s, token 60 s. |
| Guessing handles, codes or tokens | 256 bits from `SecureRandom`. Stores are keyed by SHA-256 of the value, so lookups never compare the secret directly. Client secrets and Duo state are compared in constant time. |
| Callback forged or played in another browser (login CSRF) | The callback requires the transaction cookie (`HttpOnly`, `Secure`, `SameSite=Lax`, path limited to the shim's own context, `/duoshim`) **and** the Duo state stored in that transaction. |
| Authorization code sent to an attacker | `redirect_uri` must equal `sspr.redirectUri` exactly. The token endpoint checks it again. Failures show an error page with a link back to SSPR and never redirect with a code. |
| Code stolen in transit and redeemed | `/oauth/token` needs the SSPR client secret. |
| Duo unreachable or misconfigured | Fails closed: no Duo health check means no prompt, and no `allow` means no code. There is no "fail open" mode. |
| Shared state between users | A new Duo SDK client per request. No static or servlet-field per-user state. Stores and limiters are concurrent maps. |
| Brute force or enumeration | Per-username and per-source-IP fixed-window limits on `/oauth/sign` and `/oauth/auth`. Stores have a size cap, and a sweeper removes expired entries every 30 s. |
| Secrets in logs | No secrets, handles, codes, tokens or Duo client secret are logged. The audit log writes one line per outcome: event, username, result, source IP. Control characters in usernames are neutralised to prevent log forging. `ShimConfig.toString()` excludes secrets. |
| Caching, framing, sniffing | Every response sends `Cache-Control: no-store`, `Pragma: no-cache`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, a `default-src 'none'` CSP, and HSTS over HTTPS. HTML output is escaped. |
| Misconfiguration at startup | Every required key is checked, including https-only redirect URIs, the callback path and a minimum client-secret length. Any problem stops the web application from starting. |

## Residual risks (outside the shim)

- **Duo policy.** Duo returns `allow` without a second factor in some cases: users in *Bypass* status, *New User policy = Allow access without 2FA*, *Bypass 2FA* policies, authorized networks and remembered devices. *New User policy = Require enrollment* lets an attacker enrol their own device for a not-yet-enrolled user. The shim can't tell these apart from a real approval. See [docs/DUO-SETUP.md](docs/DUO-SETUP.md).
- **Which user SSPR asks for.** The shim verifies the username SSPR injects. SSPR must identify the user correctly (search form, *Inject User Name Value* macro, LDAP Username Search Filter).
- **Secret storage.** Both client secrets are in `duoshim.properties`, and the SSPR client secret is also in SSPR's configuration. Protect both files and rotate the secrets if either leaks.
- **Single node.** State is in memory. There is no replication, and restarts drop in-flight transactions (fail closed). See README ▸ Clustering.
- **Source IP behind proxies.** Rate limits use `getRemoteAddr()`. Without Tomcat's `RemoteIpValve`, every user appears as the proxy's IP, and the per-IP limit then applies to everyone together.
- **One prompt per browser.** A second reset started in the same browser replaces the first one's cookie. The first then fails closed.
- **Attempts that never return.** Duo's deny page (for example for an unenrolled user) ends at Duo, so SSPR never learns the attempt is over and keeps sending the user back. When `/oauth/auth` sees a transaction cookie from such an attempt, it voids that transaction and shows a page with *Try Duo again* or *Start over*. *Start over* is SSPR's own cancel path. Neither link carries a code.

## Reporting

Report suspected vulnerabilities privately to the repository owners. Please don't open a public issue.
