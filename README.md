# PPNAM Station 5 Android

Station 5 Zone 8 cage-loading handheld for Packs Rev 2. The operator selects a cage, allocates approved Packs to it and confirms cage completion.

## Build and test

```
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

## Wire contract

`../PPNAM-Station-5/MQTT_CONTRACT.md` — the only source of truth; do not copy it here.

## Design

Spec: [docs/superpowers/specs/2026-11-03-cage-loading-design.md](docs/superpowers/specs/2026-11-03-cage-loading-design.md)

## Releases

Releases are cut by pushing a `v*` tag, which triggers the GitHub workflow. This is done manually by a maintainer.
