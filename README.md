# sspr-duo-shim

A small Java web application that lets OpenText (NetIQ) **SSPR 4.8 Forgotten Password** verify users with **Duo's Universal Prompt** (Web SDK v4, second factor only). SSPR itself is not modified.

SSPR's *External OAuth Authentication* verification method expects an OAuth server with a non-standard `/sign` endpoint. Duo has none, and Duo SSO asks for the user's password first, which defeats the purpose of a password reset. The shim implements SSPR's OAuth dialect on one side and the Duo Web SDK on the other.

- [How it works](#how-it-works)
- [Build](#build)
- [Deploy on Tomcat next to SSPR](#deploy-on-tomcat-next-to-sspr)
- [Duo setup](docs/DUO-SETUP.md) (separate page)
- [SSPR Forgotten Password settings](#sspr-forgotten-password-settings)
- [Network requirements](#network-requirements)
- [Clustering](#clustering)
- [Upgrades](#upgrades)
- [Troubleshooting](#troubleshooting)
- [What was verified against SSPR](#what-was-verified-against-sspr)
- [Security](SECURITY.md)

## How it works

```
 SSPR server            Browser                   shim (/duoshim)                  Duo
 ──────────             ───────                   ───────────────                  ───
 1. POST /oauth/sign  ───────────────────────────▶ store username under a
    (Basic auth, data=[{"username":"jdoe"}])       single-use handle (2 min)
                      ◀─────────────────────────── {"data":"<handle>"}
 2. redirect ────────▶  GET /oauth/auth ─────────▶ check client_id, exact redirect_uri,
                        ?client_id&redirect_uri    consume handle → jdoe,
                        &state&parameters=<handle> new Duo client, health check ─────────▶
                                                   set DUOSHIM_TXN cookie
                        ◀───────────────────────── 302 to Duo Universal Prompt
                        user approves in Duo ─────────────────────────────────────────────▶
                        GET /duo/callback ◀─────────────────────────────────────────────── 302
                        ?state&duo_code ─────────▶ cookie → transaction, check Duo state,
                                                   exchange duo_code for jdoe ───────────▶
                                                   require ALLOW and preferred_username=jdoe
                        ◀───────────────────────── 302 to SSPR /public/oauth?code&state
 3. POST /oauth/token ───────────────────────────▶ consume code (60 s) → access token (60 s)
 4. POST /oauth/userinfo (Bearer) ───────────────▶ consume token → {"username":"jdoe"}
```

The username that comes back to SSPR is always the one SSPR registered in step 1, and Duo must have approved exactly that user. Nothing the browser sends can choose or change it. Every handle, transaction, code and token is 256-bit random, single use and short-lived.

| Endpoint | Caller | Purpose |
|---|---|---|
| `POST /oauth/sign` | SSPR server | Register the user to verify; returns a handle |
| `GET /oauth/auth` (also `/oauth/grant`) | Browser | Start the Duo prompt |
| `GET /duo/callback` | Browser (from Duo) | Finish the Duo prompt; redirect to SSPR with a code |
| `POST /oauth/token` | SSPR server | Code → access token |
| `GET` or `POST /oauth/userinfo` | SSPR server | Access token → verified username |

## Build

Requires JDK 17 and Maven 3.9+.

```bash
mvn clean package
```

The output is `target/duoshim.war`. It targets **javax.servlet 4.0 (Tomcat 9)**, the same container generation as SSPR 4.8.0.600, whose WAR is compiled against `javax.servlet`. A Tomcat 10+ (`jakarta.*`) deployment would need a jakarta build of the shim.

The compiled code needs a **Java 17 or newer** runtime. Check the JVM that runs SSPR's Tomcat (`bin/version.sh`). If it is Java 11, run the shim's Tomcat on 17+, or change `maven.compiler.release` to 11 in `pom.xml`. The code uses Java 17 features (records, switch expressions), so building for 11 also means rewriting those.

Runtime dependencies are the Duo Universal SDK 1.4.0 and what it brings in (Retrofit, OkHttp, Jackson, java-jwt). There is no web framework.

## Deploy on Tomcat next to SSPR

1. **Configuration file.** Copy `duoshim.properties.example` to a path outside the WAR, for example `/etc/duoshim/duoshim.properties`, fill it in, and restrict it:
   ```bash
   chown root:tomcat /etc/duoshim/duoshim.properties && chmod 640 /etc/duoshim/duoshim.properties
   ```
2. **Point Tomcat at it.** In `bin/setenv.sh` (create it if missing):
   ```bash
   CATALINA_OPTS="$CATALINA_OPTS -Dduoshim.config=/etc/duoshim/duoshim.properties"
   ```
   Alternatively, set the environment variable `DUOSHIM_CONFIG`.
3. **Deploy** `duoshim.war` into the same `webapps/` directory as `sspr.war`. The context path becomes `/duoshim`, matching the URLs below.
4. **Audit log (recommended).** Audit lines go to the java.util.logging logger `duoshim.audit`. To write them to their own file, add this to `conf/logging.properties`:
   ```properties
   handlers = 1catalina.org.apache.juli.AsyncFileHandler, ..., 9duoshim.org.apache.juli.AsyncFileHandler
   9duoshim.org.apache.juli.AsyncFileHandler.level = INFO
   9duoshim.org.apache.juli.AsyncFileHandler.directory = ${catalina.base}/logs
   9duoshim.org.apache.juli.AsyncFileHandler.prefix = duoshim-audit.
   9duoshim.org.apache.juli.AsyncFileHandler.maxDays = 90
   duoshim.audit.handlers = 9duoshim.org.apache.juli.AsyncFileHandler
   duoshim.audit.useParentHandlers = false
   ```
   (Keep your existing handlers in the `handlers =` list; `...` stands for them.)
5. **Restart Tomcat.** On success, `catalina.out` shows `duoshim started: ShimConfig{...}`, which lists no secrets. On any configuration problem it shows `duoshim startup aborted: <every problem found>` and the `/duoshim` context does not start.
6. **Reverse proxy or load balancer.** The shim takes the source IP for rate limiting and audit from `request.getRemoteAddr()`. Behind a proxy, configure Tomcat's `RemoteIpValve` so this is the real client IP. The shim does not read `X-Forwarded-For` itself.

## Duo setup

See **[docs/DUO-SETUP.md](docs/DUO-SETUP.md)**. It covers creating the Web SDK application, the application policy, user names and network access. The policy settings decide whether a reset really requires a second factor, so don't skip them.

## SSPR Forgotten Password settings

In the SSPR configuration editor, open **Settings ▸ Modules ▸ Public ▸ Forgotten Password ▸ Profiles ▸ *your profile***.

1. **Verification Methods:** enable **External OAuth Authentication** as required (or optional, if that fits your policy).
2. **OAuth** section of the profile (`{base}` is the public `https://host[:port]`):

| SSPR setting | Value |
|---|---|
| OAuth Login URL | **SSPR 4.8:** `{base}/duoshim/oauth/auth` · SSPR 4.5: `{base}/duoshim/oauth/grant` |
| OAuth Code Resolve Service URL | `{base}/duoshim/oauth/token` |
| OAuth Profile Service URL | `{base}/duoshim/oauth/userinfo` |
| OAuth Client ID | same as `sspr.clientId` |
| OAuth Shared Secret | same as `sspr.clientSecret` |
| OAuth User Name/DN Login Attribute | same as `userinfo.attribute` (default `username`) |
| OAuth Inject User Name Value | A macro giving the user's **Duo username**, for example `@LDAP:cn@` or `@LDAP:mail@`. **Required:** if it is blank, SSPR never calls `/sign` and the shim rejects the request. |
| Skip Authorization header in OAuth Code Resolve request | **Off.** In 4.8 this also removes client authentication from the `/sign` call, which the shim then rejects. |
| OAuth Web Service Server Certificate | The shim's TLS certificate (or its CA), if SSPR doesn't already trust it. SSPR calls the shim over HTTPS. |
| Add login hint optional parameter… | Blank (the shim ignores it). |

Why the Login URL ends in `/auth` on 4.8: SSPR 4.8 builds the `/sign` URL by keeping everything up to the last `/auth` in the Login URL and appending `sign`. `{base}/duoshim/oauth/auth` therefore gives `{base}/duoshim/oauth/sign`. With a Login URL ending in `/grant`, SSPR 4.8 computes a sign URL of just `sign` and the reset fails. SSPR 4.5 instead replaced `/grant` with `/sign`. The shim serves both `/oauth/auth` and `/oauth/grant`.

3. **Elsewhere in SSPR:**
   - **Settings ▸ Application ▸ Application ▸ Site URL** must be set. SSPR builds its OAuth return address as `<Site URL>/public/oauth`, and `sspr.redirectUri` must equal it **exactly** (scheme, host, port, path, no trailing slash). The `oauth.returnUrlOverride` app property, if set, takes precedence over the Site URL.
   - **SSO ▸ OAuth ▸ OAuth Profile / UserInfo Service Method Type** (4.8): `POST` or `GET` both work.
   - The *Inject User Name Value* must also resolve to the same user through SSPR's **LDAP Username Search Filter**. SSPR looks up the returned username that way and compares the DN with the user found on the search page. For example, if the macro is `@LDAP:mail@`, the username search filter must match `mail`.

The shim never returns an `id_token`. That keeps SSPR 4.8 out of its OIDC branch, which would read the username using the **SSO** profile's attribute name instead of the Forgotten Password profile's. It is also why the separate `SSPR-oauth/patch` bytecode fix is not needed with the shim.

## Network requirements

| From | To | Why |
|---|---|---|
| SSPR server | Shim's **public** URL (`{base}/duoshim/...`), HTTPS | `/sign`, `/token`, `/userinfo` are server-to-server calls to the URLs configured in SSPR. When SSPR and the shim share a server, that server must be able to reach its **own public host name** (DNS and NAT hairpin, or a hosts-file entry pointing it at itself). |
| Shim server | `duo.apiHost`:443 | Health check and code exchange. Duo's CAs are pinned, so no TLS interception. |
| Browsers | Shim public URL, `duo.apiHost` | Prompt start, Universal Prompt, callback. |

The host name in the **OAuth Login URL** and the host name in `duo.redirectUri` must be the **same**. The transaction cookie set at `/oauth/auth` is only sent back to that host's `/duoshim/duo` path.

## Clustering

**The shim keeps all state in memory on one node.** The SSPR server calls `/sign`, `/token` and `/userinfo`, which carry no browser cookie. The browser calls `/oauth/auth` and `/duo/callback`. All five must reach the **same shim instance**, and sticky sessions cannot arrange that for the server-to-server calls.

- **Supported:** run exactly one shim instance and point every SSPR node at its one URL. That works with a clustered SSPR. The shim is light, and if it goes down only Duo-based forgotten password stops.
- **Not supported as built:** several shim instances behind a load balancer. That needs a shared store (for example Redis or a database with an atomic get-and-delete, so codes stay single use). The stores are confined to `duoshim.store.ExpiringStore`, so that is a contained change.
- A restart of the shim drops in-flight transactions. Affected users simply start the reset again.

## Upgrades

- **SSPR upgrades:** the shim depends on SSPR's OAuth client behaviour, not on SSPR code. After any SSPR upgrade, re-check `password/pwm/http/servlet/oauth/OAuthMachine.class` (see [What was verified](#what-was-verified-against-sspr)). Especially check how the `/sign` URL is derived, the token-response parsing (`id_token` handling), and the userinfo method. Then run one reset end to end.
- **Moving to Tomcat 10+:** SSPR itself must move to `jakarta.*` first. The shim would need a jakarta build (rename the imports, swap the servlet API dependency).
- **Duo SDK:** bump `duo.sdk.version` in `pom.xml`, run `mvn clean package` (the tests use a fake Duo), then test against Duo.
- **Shim upgrades:** replace `duoshim.war`. The configuration file stays where it is, and in-flight transactions are lost (see above).
- **Secrets:** to rotate `sspr.clientSecret`, change it in SSPR and the properties file together, then restart the shim. To rotate `duo.clientSecret`, rotate it in Duo, update the file, then restart.

## Troubleshooting

Start with the shim's audit log (`event=… user="…" result=… ip=…`) and `catalina.out`.

| Symptom | Likely cause | Fix |
|---|---|---|
| `/duoshim` returns 404; log shows `duoshim startup aborted: …` | Missing or invalid configuration; the message lists every problem | Fix the properties file and restart. |
| SSPR: *error during OAuth pre-inject username signing service* with URL `sign` | SSPR 4.8 with a Login URL that doesn't contain `/auth` | Use `{base}/duoshim/oauth/auth`. |
| SSPR: *unexpected HTTP status code (401) during OAuth pre-inject…*; audit `event=sign result=invalid_client` | Client ID/secret mismatch, or *Skip Authorization header* on | Match `sspr.clientId`/`sspr.clientSecret`; turn the skip option off. |
| Shim page *This verification link has expired or was already used*; audit `invalid_or_expired_handle` | *Inject User Name Value* blank (no `/sign` call), more than 2 minutes between `/sign` and the browser arriving, or a reload | Set the macro; start again. |
| Shim page *unexpected return address*; audit `invalid_redirect_uri` | `sspr.redirectUri` doesn't exactly equal `<Site URL>/public/oauth` | Compare character by character (trailing slash, port, http vs https). |
| Shim page *Duo verification is unavailable*; audit `duo_unavailable` | Shim can't reach `duo.apiHost`, TLS interception, wrong Duo credentials | `curl https://<apiHost>` from the shim server; check proxy exemptions; check the Duo values. |
| Shim page *not started in this browser, or cookies are blocked*; audit `no_transaction_cookie` | Login URL host differs from the `duo.redirectUri` host, cookies blocked, or `cookie.secure=true` over plain HTTP | Use one host name for both; use HTTPS. |
| Duo prompt shows *user not found / not enrolled*, or audit `denied` | Duo username differs from the injected value, or New User policy = Deny for an unenrolled user | Fix the macro or the Duo username/alias; enrol the user. |
| Audit `user_mismatch` | Duo normalised the username differently from what SSPR sent | Check Duo username normalisation; send the normalised form. |
| Audit `state_mismatch` | Two Duo prompts in one browser (the newest cookie wins), or a tampered callback | Finish one reset at a time. |
| SSPR: *OAuth server did not respond with an username value for configured attribute* | `userinfo.attribute` ≠ SSPR's *OAuth User Name/DN Login Attribute* | Make them equal. |
| SSPR: *oauth server username does not match previously identified user* | The injected value resolves, through SSPR's LDAP Username Search Filter, to a different DN than the one the search page found | Use an attribute the username search filter matches (for example `cn`). See also the DN-case fix in `SSPR-oauth/patch`. |
| SSPR: token error 400; audit `invalid_grant` | Code older than 60 s, or the redirect URI SSPR sent differs | Check clock and load; check Site URL. |
| HTTP 429 or *Too many attempts* | Rate limit reached | Wait for `ratelimit.windowSeconds`, or tune the limits. |

## What was verified against SSPR

The SSPR client behaviour was taken from disassembled `OAuthMachine`, `OAuthSettings`, `OAuthConsumerServlet`, `ForgottenPasswordServlet` and `AppProperty.properties` of two builds:

- **SSPR 4.5.0.1**, the compiled classes in the SSPR-DUO-UNIVERSAL reference repo (`lib/classes`).
- **SSPR 4.8.0.600 build 478**, the WAR in the parent `SSPR-oauth` folder.

| Behaviour | 4.5.0.1 | 4.8.0.600 | Shim |
|---|---|---|---|
| `/sign` URL | Login URL with `/grant` replaced by `/sign` | Login URL up to and including the `/` before the last `/auth`, plus `sign` | Serves `/oauth/sign`; grant on both `/oauth/grant` and `/oauth/auth` |
| `/sign` request | POST form `data=[{"username":…}]`, HTTP Basic | Same; Basic omitted if *Skip Authorization header* is on | Requires client auth |
| `/sign` response | JSON `data` field | Same | `{"data":"<handle>"}` |
| Browser redirect params | `client_id`, `response_type=code`, `state`, `redirect_uri`, `scope` (if set), `parameters` | Same plus `login_hint` (if set); `scope` always `openid` | Uses `client_id`, `redirect_uri`, `response_type`, `state`, `parameters`; ignores the rest |
| Token request | POST form `code`, `grant_type`, `redirect_uri`, `client_id`, `client_secret` + Basic | Same; Basic omitted if skip is on | Accepts Basic or form credentials |
| Token response | Reads `access_token`, `expires_in`, `refresh_token` | Parses JSON; if `id_token` is present, takes the username from it using the SSO attribute | Returns `access_token`, `token_type`, `expires_in`, **no `id_token`** |
| Userinfo | POST, Bearer header + form `access_token`, `attributes` | POST, or GET with Bearer only when the global method type is GET | Accepts both; token from Bearer, or form field on POST |
| Return check | Not checked | Resolves the returned username via LDAP and compares DNs exactly | Returns the injected value unchanged |

**Where the original brief differed and the bytecode was followed:**
- The brief described the `/grant` → `/sign` rule. That is 4.5 only; 4.8 needs a Login URL ending in `/auth`.
- The brief had userinfo as POST only. 4.8 can send GET.
- The token response must not include `id_token`, because of 4.8's OIDC branch.

**Not verified:**
- A live end-to-end run against SSPR 4.8 and Duo. The tests use a fake Duo and simulated requests.
- 4.8 builds other than 4.8.0.600.
- How SSPR's HTTP client handles the shim's TLS certificate without *OAuth Web Service Server Certificate* set.
- The exact effect of Duo username normalisation on `preferred_username`.
