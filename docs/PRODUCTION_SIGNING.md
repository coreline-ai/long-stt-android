# Production Signing Pipeline

> [!IMPORTANT]
> This repository never creates, stores, prints, or commits production signing keys. Keep the upload keystore and its passwords in the release operator's approved secret store. Losing or replacing an upload key changes the Play Console recovery procedure.

## What the pipeline does

`./gradlew :app:productionReleaseBundle` is a fail-closed release gate. When every signing input is supplied, it:

1. connects the `release` build type to the external `productionRelease` signing config;
2. builds `app/build/outputs/bundle/release/app-release.aab`;
3. checks the AAB's JAR signature with the JDK `jarsigner` tool; and
4. writes `app/build/outputs/security/release-provenance.json` with the artifact SHA-256, version, source revision, timestamp, and verification result.

The provenance file contains no key material. `:app:verifyProductionSigning` deliberately fails before bundle creation if signing configuration is incomplete or the keystore file is unavailable.

## Local release operator procedure

Use either the environment variables below or identically purposed Gradle properties (`longSttReleaseStoreFile`, `longSttReleaseStorePassword`, `longSttReleaseKeyAlias`, `longSttReleaseKeyPassword`). Never put their values in `gradle.properties` tracked by Git.

```bash
export LONG_STT_RELEASE_STORE_FILE="/secure/path/coreline-upload.jks"
export LONG_STT_RELEASE_STORE_PASSWORD="<keystore-password>"
export LONG_STT_RELEASE_KEY_ALIAS="<key-alias>"
export LONG_STT_RELEASE_KEY_PASSWORD="<key-password>"

./gradlew :app:productionReleaseBundle
```

Before uploading, keep the AAB and its JSON provenance together and independently verify the signed bundle if required by the release process:

```bash
"$JAVA_HOME/bin/jarsigner" -verify -certs app/build/outputs/bundle/release/app-release.aab
shasum -a 256 app/build/outputs/bundle/release/app-release.aab
cat app/build/outputs/security/release-provenance.json
```

## CI workflow

[`.github/workflows/production-release.yml`](../.github/workflows/production-release.yml) is manual-only and targets the protected `production` GitHub Environment. Configure the Environment with reviewer protection and these repository/environment secrets:

| Secret | Purpose |
|---|---|
| `LONG_STT_RELEASE_STORE_BASE64` | Base64-encoded upload keystore; decoded only into the ephemeral runner temp directory |
| `LONG_STT_RELEASE_STORE_PASSWORD` | Keystore password |
| `LONG_STT_RELEASE_KEY_ALIAS` | Upload key alias |
| `LONG_STT_RELEASE_KEY_PASSWORD` | Upload key password |

The workflow uploads only the signed AAB and non-secret provenance as a short-retention artifact. It does **not** publish to Google Play. A release operator must review the artifact identity, Play Console track, release notes, privacy declaration, Data safety form, and staged rollout settings separately.

## Key custody rules

- Keep the original upload keystore in a restricted, backed-up secret system owned by Coreline.
- Grant production Environment access to the minimum release operators; do not expose secrets to pull requests.
- Rotate or recover an upload key only through the platform's documented account process.
- Do not attach keystores, passwords, screenshots of secrets, or generated signed artifacts to source-control commits.
