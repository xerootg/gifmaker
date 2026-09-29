# Release signing

Android only installs an APK as an update if it is signed with the same key as the installed
copy, so every release must be signed with one long-lived key. That key is never committed.
The workflow reads it from these repository secrets (Settings → Secrets and variables → Actions):

| Secret                   | Value                                             |
|--------------------------|---------------------------------------------------|
| `SIGNING_KEYSTORE_B64`   | the `.jks` / PKCS#12 keystore, base64 (one line)  |
| `SIGNING_STORE_PASSWORD` | keystore password                                 |
| `SIGNING_KEY_ALIAS`      | key alias inside the keystore                     |
| `SIGNING_KEY_PASSWORD`   | key password (same as the store password for PKCS#12) |

Generate a keystore once and keep it somewhere safe; losing it means users must uninstall to
take the next update:

```
keytool -genkeypair -keystore gifmaker-release.jks -storetype PKCS12 -alias gifmaker \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=GIF Maker"
base64 -w0 gifmaker-release.jks   # → SIGNING_KEYSTORE_B64
```

When the secrets are missing, CI still produces a release, signed with a key generated for that
run only, and marks the release notes and workflow log accordingly. Those builds install fine but
do not update over each other (or over a properly signed build), and a properly signed build
does not update over them; uninstall once after adding the secrets.

Pull-request builds never receive the secrets and always use a per-run key.

Locally, `./gradlew :app:assembleRelease` signs with the debug key unless the `SIGNING_KEYSTORE`,
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` environment variables
point at a keystore.
