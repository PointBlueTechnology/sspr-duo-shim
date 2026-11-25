# Changelog

## 1.0.0 (2026-11-25)

First release. An OAuth shim that lets OpenText (NetIQ) SSPR Forgotten Password verify users with the Duo Universal Prompt (Web SDK v4, second factor only), using SSPR's *External OAuth Authentication* method. SSPR itself is not modified.

### Features

- SSPR-facing OAuth endpoints: `/oauth/sign`, `/oauth/auth` (also `/oauth/grant` for SSPR 4.5), `/oauth/token` and `/oauth/userinfo` (GET and POST).
- Duo Universal Prompt via the Duo Universal SDK 1.4.0, with a new Duo client per request, a health check before each prompt, and a per-transaction state and nonce.
- The username returned to SSPR is always the one SSPR registered, approved by Duo for that exact transaction.
- *Did not finish* page when a Duo attempt never returned (deny, unenrolled user, timeout). It offers *Try Duo again*, or *Start over* through `/oauth/startover`, which clears the attempt in the shim and in SSPR.
- `/health` for load balancers, with an optional cached Duo check (`?duo=1`).
- Configuration file outside the WAR (`-Dduoshim.config` or `DUOSHIM_CONFIG`). Startup fails on any missing, invalid or `CHANGE_ME` value.
- `deploy/install-duoshim.sh`: checks for Tomcat 9 and Java 11+, then installs the WAR, config template and `setenv.sh` entry without overwriting existing config.

### Security

- 256-bit single-use handles, transactions, codes and tokens, stored by SHA-256 digest, with short lifetimes (2 min, 5 min, 60 s, 60 s).
- Exact `redirect_uri` matching, constant-time client authentication, and an exact username match with Duo's result.
- Fails closed: no code is ever issued on a Duo error, denial or outage.
- Per-username and per-IP rate limits. `/oauth/sign` authenticates before any per-IP limit, and failed attempts have their own budget.
- Duo state is checked before a transaction is used up, so forged callbacks can't cancel a pending attempt.
- `no-store`, HSTS, CSP, `nosniff` and frame denial on every response, including error pages.
- An audit log line per outcome, free of secrets, with log-forging characters neutralised.

### Compatibility

- Runs on Java 11+ in Tomcat 9 (`javax.servlet` 4.0), alongside SSPR. Building needs JDK 17+.
- Tested end to end on SSPR 4.8.0.600 (Tomcat 9.0.124, Java 11) with a real Duo Web SDK application: approved push, unenrolled user, denied push, timed-out push, and *Start over*.
- SSPR 4.7 and 4.8 need an OAuth Login URL ending in `/oauth/auth`; SSPR 4.5 uses `/oauth/grant`. See README ▸ SSPR compatibility.
- One active instance at a time; active/standby is documented (README ▸ Clustering).

### Dependencies

Duo Universal SDK 1.4.0, Jackson 2.18.12, Retrofit 2.11.0, OkHttp 3.14.9, Okio 1.17.6, java-jwt 4.0.0.
