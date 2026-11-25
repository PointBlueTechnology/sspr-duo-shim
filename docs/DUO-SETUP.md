# Duo setup for sspr-duo-shim

The shim uses Duo's **Web SDK** (Universal Prompt, Web SDK v4) to check a second factor only. Duo never sees or checks a password. This page covers everything to configure on the Duo side.

## 1. Create the Web SDK application

1. Sign in to the Duo Admin Panel as an Owner or Application Manager.
2. Go to **Applications ▸ Protect an Application**, search for **Web SDK**, and click **Protect**.
3. Name it something people will recognise in the prompt and in Duo logs, for example `SSPR password reset`.
4. Copy these three values into `duoshim.properties`:

   | Duo Admin Panel | duoshim.properties |
   |---|---|
   | Client ID | `duo.clientId` |
   | Client secret | `duo.clientSecret` |
   | API hostname | `duo.apiHost` (host name only, no `https://`) |

5. Save the application.

The shim sends its callback address, `duo.redirectUri` (for example `https://sspr.example.com/duoshim/duo/callback`), in each signed request to Duo. If your Admin Panel shows a redirect URI field for the application, enter the same value there.

Handle the client secret like a password. Keep it only in `duoshim.properties` (mode 600, readable by the Tomcat user). If it is ever committed to source control or pasted into a ticket, rotate it in the Admin Panel.

## 2. Application policy (security-critical)

Password reset is only as strong as this policy. The shim gives SSPR a verified identity whenever Duo reports `allow`. **Duo reports `allow` in several cases where no second factor was checked**, and the shim cannot tell them apart from a real approval. Create a **custom policy for this application only** (**Applications ▸ your app ▸ Apply a policy**) with these settings:

| Policy setting | Set to | Why |
|---|---|---|
| **New User policy** | **Deny access** | With *Allow access without 2FA*, anyone who knows the username of a user not enrolled in Duo passes. With *Require enrollment*, an attacker could enrol **their own** phone for that user during the reset. |
| **Authentication policy** | **Require 2FA** | *Bypass 2FA* turns the check off entirely. |
| **Remembered devices** | **Off** for this application | A reset should require a fresh second factor, not one remembered from an earlier login. |
| **Authorized networks** | No "allow without 2FA" networks | An exempt network would let anyone on it reset passwords without 2FA. |
| **User location / Device health / Authentication methods** | Your organisation's standard for high-risk actions | Prefer phishing-resistant methods (Verified Push, WebAuthn) over SMS and phone call. |

Global policy settings also apply unless the custom policy overrides them, so check the global policy for the same items.

## 3. Users

- **Usernames must match.** The username the shim sends to Duo is the value SSPR computes from **OAuth Inject User Name Value** (for example `@LDAP:cn@` or `@LDAP:mail@`). That value must be a Duo username, or one of the user's Duo username aliases. If Duo synchronises from AD or LDAP, use the same attribute Duo's directory sync uses for the username.
- **Username normalization.** Duo can normalise usernames (Settings ▸ Username normalization, for example dropping `DOMAIN\` or `@domain`). The shim compares the username in Duo's signed result with the one it sent, ignoring case only. If normalisation makes those differ, the shim rejects the attempt with `result=user_mismatch` in the audit log. Test with real accounts.
- **Bypass status.** A user whose Duo status is **Bypass** gets `allow` without a second factor. Don't leave users in Bypass, and review who has it.
- **Locked out or disabled users** are denied by Duo, so the shim never issues a code for them.
- Optionally, restrict the application to the groups allowed to use self-service reset (**Permitted groups** on the application).

## 4. Network

- The shim server needs outbound HTTPS (TCP 443) to `duo.apiHost`. Duo calls it at two points: the health check before each prompt, and the code exchange after it.
- The Duo SDK pins Duo's certificate authorities. A TLS-inspecting proxy between the shim and Duo will break the connection, so exempt `*.duosecurity.com`.
- Users' browsers need to reach `duo.apiHost` (the Universal Prompt) and the shim's public URL.

## 5. Test

1. Start a password reset in SSPR for a test user who is enrolled in Duo.
2. The browser goes to Duo's Universal Prompt and shows the application name from step 1. Approve the prompt.
3. You land back in SSPR with the OAuth verification method satisfied.
4. Check the Duo **Authentication Log**: it should list the user and the application with the factor used. Check the shim's audit log: it should have `event=duo_callback ... result=allow`.

Then confirm the negative cases. Each must end at a shim error page, never at an SSPR password-change page:

- A user who isn't enrolled in Duo (expect a deny, from the New User policy). Duo's deny page doesn't send the browser back. If the user returns to SSPR's forgotten password page, the shim shows *Your last Duo verification did not finish*, with *Start over* and *Try Duo again*.
- A Duo denial or timeout.
- A user in Bypass status: expect it to **pass**. This is why Bypass must not be used.
