# App signing: always the same key

MatNotes releases must always be signed with the same release keystore. Android only installs an update over an
installed app when the signature matches. A different key makes the install fail with "App not installed"
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and forces the user to uninstall first, which deletes the app data.

## Rules

- Release APKs come only from the GitHub Actions workflow `.github/workflows/release.yml` (tag `v*`). It signs with the
  keystore stored in the repository secrets. Never build a release locally with another key.
- The keystore file (`matnotes-release.jks`) and its passwords are the owner's. Keep a backup outside the repo (password
  manager). Without it no update can ever be signed again. Never commit it (`*.jks` is gitignored), never paste it in chat.
- Repository secrets (Settings, Secrets and variables, Actions): `MATNOTES_KEYSTORE_B64` (`base64 -w0 matnotes-release.jks`),
  `MATNOTES_KEYSTORE_PASSWORD`, `MATNOTES_KEY_ALIAS`, `MATNOTES_KEY_PASSWORD`. Without them the workflow builds an
  unsigned APK, which cannot be installed.
- Debug builds use another application id (`com.matnotes.app.debug`) and the automatic debug key. They install next to the
  release app and never replace it.
- Every release needs a higher `versionCode` in `app/build.gradle.kts` (Android refuses to install the same or a lower one).

## Check which key an APK has

```
~/Android/Sdk/build-tools/<version>/apksigner verify --print-certs app.apk
```

Two APKs can update each other only when the `SHA-256 digest` of the certificate is identical.

## Release

```
# bump versionCode and versionName in app/build.gradle.kts, commit, push
git tag v0.2.0 && git push origin v0.2.0
```

The workflow attaches `matnotes-v0.2.0.apk` to a GitHub Release. If the build of a tag fails, fix it, then move the tag
(`git tag -d`, `git tag`, `git push -f origin <tag>`) or re-run the failed run with `gh run rerun <id>`.
