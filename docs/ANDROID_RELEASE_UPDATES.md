# Intermix Android release-origin updates

This update lane keeps the DS215j deliberately simple: it serves static bytes over HTTPS. It does
not approve releases, choose device eligibility, rewrite manifests, or hold either signing private
key. Every Intermix device authenticates the manifest, APK hash, package/version, and APK signing
certificate locally before asking Android PackageInstaller to proceed.

## Trust boundary

| Component | May do | Must not do |
|---|---|---|
| Protected CI/publisher | Build and sign APKs, sign exact manifest bytes, choose rollout percentage | Put private signing keys in source, APK, or NAS content |
| DS215j release origin | Serve the published tree as immutable HTTPS bytes with range requests | Decide trust, redirect clients, generate manifests, expose administrative APIs |
| Intermix device | Verify the pinned Ed25519 key, manifest validity, cohort, APK SHA-256, package/version, and pinned Android signer | Trust a value merely because the NAS returned it |
| Android PackageInstaller | Enforce platform signing/update rules and collect user confirmation when required | Bypass the app's local verification/checkpoint sequence |

The first community APK must already be signed by the long-lived production Android certificate.
Android will not update an APK signed by a different identity. Keep that APK private key in the
reviewer-protected signing environment. Keep the independent Ed25519 manifest private key in a
protected publisher workspace; neither private key belongs on the NAS. Maintain encrypted recovery
copies offline before inviting community devices into the update lineage.

### CI signing isolation

The Android workflow deliberately separates compilation from signing:

1. Push and pull-request jobs check out source, run repository code, test, and retain an unprivileged
   debug artifact without receiving any signing secret.
2. Dogfood signing runs only after an explicit `workflow_dispatch` with `sign_dogfood=true`.
3. The dispatch validates all three public trust anchors, then assembles a separate unsigned release
   APK. Gradle refuses every release build with missing or malformed anchors. CI also proves the APK
   is non-debuggable and unsigned before handing it across the job boundary.
4. The signing job enters the `dogfood-signing` GitHub environment, which must have at least one required
   reviewer. For the one-person lane, self-approval may remain enabled so this is a deliberate
   release checkpoint rather than a second-person dependency.
5. The signing runner never checks out repository code. It downloads the exact candidate artifact
   ID produced by its required build job, accepts only the five expected bounded files, and verifies
   run ID, commit, SHA-256, non-debuggable state, and trust provenance before signing.
6. The embedded APK certificate pin must equal the protected certificate's SHA-256. The runner
   zip-aligns, signs, and re-verifies the final APK. Every external GitHub Action is pinned to a full
   commit SHA. Signing material is scoped only to that step, removed before artifact upload, and
   never enters the DS215j.

Before initializing the dogfood identity, create **Settings → Environments → dogfood-signing** and
add the repository owner as a required reviewer. Then run
`tools/termux_dogfood_update.sh --initialize-key`. The helper refuses an unprotected environment,
stores the two signing values as environment secrets, deletes same-named repository-level secrets,
and stops safely until the public origin is known. Do not leave fallback signing-key copies in
repository or organization secrets: another workflow could otherwise request them without crossing
the environment approval gate.

## Pinned build values

Debug builds may leave all three values empty, which keeps update checks disabled. Every release
build requires all three values and fails before APK assembly if any value is missing or malformed.
The protected signing job then independently checks that the certificate pin equals the certificate
it actually uses.

| Gradle property or environment variable | Value |
|---|---|
| `INTERMIX_RELEASE_ORIGIN_URL` | HTTPS base URL ending at the static release tree, for example `https://updates.example.net/intermix/` |
| `INTERMIX_RELEASE_MANIFEST_PUBLIC_KEY_B64` | Base64 of the Ed25519 public key in X.509 DER form |
| `INTERMIX_RELEASE_APK_CERT_SHA256` | Lowercase SHA-256 of the production APK signing certificate |

The origin is compiled into the APK. A manifest cannot redirect a device to another host, and HTTP
redirects are rejected. The public key and certificate hash are trust anchors, not secrets.

Once the outbound-only tunnel/CDN hostname is live, configure the public anchors and dispatch the
first hardened candidate from the existing Termux checkout:

```bash
tools/termux_dogfood_update.sh \
  --configure-release-trust \
  --origin https://YOUR_PUBLIC_HOST/intermix/ \
  --branch feature/anicloud-release-origin-20260917
```

The helper reuses the protected APK identity, creates or reuses a separate Ed25519 manifest private
key under Termux-private storage, validates the complete trust tuple, and writes only the origin,
manifest public key, and certificate fingerprint to GitHub repository variables. It never uploads
the manifest private key. Back up
`$HOME/.local/share/anicloud-release-manifest/manifest-ed25519.pem` before publishing a manifest.
If GitHub already contains either public pin but the matching local identity is absent or different,
the helper refuses silent rotation. Restore the original backup; an Android signer or manifest-key
rotation requires an explicitly designed transition release and cannot be repaired by overwriting a
variable.

Derive those two public anchors in a protected workspace; do not paste either private key into an
issue, repository variable, server, or support chat:

```bash
openssl pkey -in manifest-ed25519.pem -pubout -outform DER | base64 -w0
apksigner verify --print-certs intermix-release.apk
```

Use the first command's output as the manifest public-key value and normalize the reported signer
certificate SHA-256 to 64 lowercase hexadecimal characters.

## Static origin layout

```text
intermix/
  channels/community/release.json
  releases/community-23/intermix-23.apk
```

The APK path is immutable and content-addressed by the signed SHA-256 in the manifest. The signed
manifest and detached signature are wrapped in one atomically replaced channel envelope. Publish
the release APK first, then `release.json`. A device decodes only that bounded transport wrapper
before verifying the signature over the exact inner manifest bytes; no manifest field is trusted
before verification.

The HTTPS layer should support byte ranges, `Content-Length`, TLS 1.2 or newer, and no redirects.
Give the web-serving account read-only access. Give a restricted publisher account write access
only to this release tree. Do not expose DSM administration or a general file browser at the update
hostname.

## Router and origin isolation

Do not port-forward the DS215j, DSM, SSH, WebDAV, or its web server to the public internet. Put a
read-only reverse proxy/tunnel in front of the one release path. The preferred topology is:

```text
Intermix device -> public HTTPS CDN/tunnel -> hardened LAN gateway -> read-only DS215j release path
```

Run the tunnel connector on a maintained gateway, small Linux host, or supported container host if
the DS215j cannot run a current connector. It should make outbound-only connections; the firewall
should reject unsolicited inbound WAN traffic. Route only `GET` and `HEAD` for the release prefix,
disable directory listing and uploads, rate-limit abusive clients, and cache immutable APK paths.
Assume the home's public IP will eventually be discovered: the acceptance condition is that it has
no reachable release, DSM, SSH, or router-administration listener. Address secrecy is a useful side
effect, not the security boundary.

For a Cloudflare-backed deployment, the public DNS record must resolve only to Cloudflare and the
tunnel must be the sole origin route—never retain a second DNS-only record pointing home. APK is a
default cacheable extension, and the app's 512 MiB maximum matches the documented Free/Pro/Business
cacheable-file limit. Configure `/releases/*` as cache-eligible for one year with immutable origin
headers. Configure the single `/channels/community/release.json` envelope for a short edge TTL such
as 60 seconds, then purge that one URL after publication. Range requests remain cacheable, and cache
locking collapses simultaneous misses so only one fetch per edge location reaches the origin.

This makes user count mostly a CDN concern. Each device is already assigned a stable local cohort,
delayed across a six-hour window, limited to unmetered networking, and scheduled about once per day.
The NAS serves the first cache fill and occasional revalidation, not one full APK per device.

### Always-on router and NAS baseline

- Remove every WAN port-forward to the NAS/gateway and disable UPnP/NAT-PMP automatic mappings.
- Disable router administration from WAN; manage the router and DSM only from LAN or a private VPN.
- Disable unused Synology QuickConnect/DDNS relay features and never map public ports 80 or 443 to
  the NAS. The public HTTPS endpoint belongs to the CDN/tunnel, not the router.
- Apply the same default-deny policy to IPv6, where NAT is not an accidental firewall.
- Give the NAS a fixed LAN address, but never publish that address or the router address in DNS.
- If the router supports it, isolate the gateway and NAS on a server VLAN. Allow the gateway to read
  only the release listener; deny the NAS new outbound sessions except explicit update/health needs.
- Serve only a dedicated read-only share/path. Disable directory indexes, CGI/PHP, WebDAV, uploads,
  and authentication endpoints on the release virtual host.
- Keep the tunnel token on the maintained gateway, not in the APK, repository, release tree, or
  NAS web share. Restrict the gateway so it can reach only the NAS release listener.
- Keep DSM and installed packages at the newest version the hardware supports. If security support
  ends, retain the NAS only as isolated storage and replace the gateway—not the trust model.
- Enable SMART/volume alerts, free-space alerts, UPS-safe shutdown if available, tunnel-health and
  TLS-expiry alerts, and an external check that validates the envelope plus a sampled APK range.
- Keep APK and manifest signing private keys off the NAS and gateway. Compromise of either serving
  layer can cause downtime, but cannot mint an update accepted by devices.
- After every router or tunnel change, scan the home IPv4 and IPv6 addresses externally and confirm
  that no forwarded service is reachable. Replace or bridge an ISP router that cannot receive
  security updates or enforce this policy.

This keeps the NAS and router address out of client-visible DNS and prevents direct-origin bypass.
Intermix does not need, store, or act on a user's source IP. The CDN will necessarily see network
source addresses while delivering files; do not forward those headers to the NAS, and keep origin
logging to operational errors and aggregate health rather than client identities.

The APK sends only a generic `Intermix-Updater/1` user agent, fixed channel paths, and optional HTTP
Range offsets. It sends no device identifier, cohort value, installed version, prompt, Matrix data,
model information, or workspace content. Android cleartext traffic is explicitly disabled, only
system certificate authorities are trusted, the configured origin must be a public DNS hostname on
standard HTTPS, and redirects are rejected. The signed manifest remains the release-integrity root
even if the delivery network is hostile.

| Threat | Control | Residual boundary |
|---|---|---|
| Modified NAS/CDN response | Exact-byte Ed25519 manifest signature plus APK size/SHA-256 | A hostile origin can deny service, but cannot create an installable release |
| DNS/TLS interception | System TLS validation, HTTPS-only policy, no redirects, signed manifest | Network observers can still learn timing/volume and, on older Android, usually the hostname |
| Replay or downgrade | Signed validity window, higher `version_code`, minimum-current-version gate | Clock failure can delay updates; it cannot authorize an APK with the wrong signer |
| Malicious APK substitution | Pinned package, target version, SHA-256, and Android signer certificate | Both release keys require independent protected custody |
| Partial-download poisoning | Signed byte count, bounded range resume, final whole-file SHA-256 | Repeated corruption can consume bandwidth but never reaches PackageInstaller |
| Device tracking | No app/device/cohort ID or version in requests; generic user agent | Source IP, request time, path, and range offset remain visible to the CDN |
| NAS discovery/attack | Outbound-only tunnel, no WAN port-forward, read-only route, CDN cache/rate limits | A leaked historical address still requires firewall enforcement |
| Compromised update process | Independent SQLite ledger, private checkpoint, Android user confirmation | Android rollback is not silently available; recovery ships a newer signed build |

## Build a publication tree

After signing and verifying the APK with Android build-tools, run:

```bash
python tools/publish_android_update.py \
  --apk /protected/path/intermix-release.apk \
  --output-dir /staging/intermix-origin \
  --manifest-private-key /protected/path/manifest-ed25519.pem \
  --release-id community-23 \
  --version-code 23 \
  --version-name 0.8.12-release-origin \
  --expected-apk-signer-sha256 "$INTERMIX_RELEASE_APK_CERT_SHA256" \
  --source-commit "$GITHUB_SHA" \
  --rollout-basis-points 500 \
  --min-current-version-code 21
```

The default rollout is 500 basis points (5%). Raise the signed value in measured steps—such as
5%, 20%, 50%, then 100%—without changing the immutable APK. Each device owns a stable random cohort,
checks about once per day on an unmetered connection, and receives an initial delay spread across
six hours. Interrupted downloads resume with HTTP Range.

Upload or synchronize the generated tree through the restricted publisher channel. Do not copy the
manifest private key. Before changing the channel pointer, independently compare the remote APK's
byte count and SHA-256 with the publisher summary.

## Device state and failure behavior

The updater uses an independent SQLite ledger so it remains recoverable even if the main Matrix
schema cannot open. Before PackageInstaller, it checkpoints the Matrix database plus active mission
and project-state metadata into app-private no-backup storage and verifies the checkpoint hash.

After Android installs the APK, startup opens the Matrix to run transactional schema migrations,
runs SQLite quick/foreign-key/table checks through the Librarian integrity gate, and marks the
release complete only after those checks pass. A failure retains the pre-update checkpoint and
reports a blocked/quarantined state; it never claims that local state is healthy.

If Android cannot show confirmation from the background, Intermix posts a persistent notification
for one-tap continuation. Denial or cancellation leaves the current installed version, verified APK,
and durable checkpoint intact. Android does not provide an application-controlled rollback to an
older APK; recovery must ship a newer, correctly signed version or use an explicitly tested restore
procedure.

## First integration gate

Before enabling the origin in a community build:

1. Run the release-trust helper with the exact public HTTPS base URL and verify the protected CI
   artifact reports the expected manifest-key and production-certificate fingerprints.
2. Prove the origin rejects redirects and supports a resumed download from several offsets.
3. Install the production-signed bootstrap APK on a disposable device and preserve its local state.
4. Publish a higher `version_code` to a 1-device/5% cohort and interrupt download, reboot, decline
   install, approve install, and reboot again.
5. Verify the post-install ledger reaches `Complete`, Matrix counts are unchanged, the Librarian
   check passes, and no prompt, memory, model, or workspace bytes appeared in origin logs.
6. Expand the rollout only after the device report and CI artifact hashes agree.
