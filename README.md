# ConnectBot Z-Modem

Sender and receiver for classic Z-Modem. The library supports incremental
detection, CRC-16/CRC-32 reception, CRC-16 transmission, escaped data, batches,
offset retransmission, and cancellation.  KStateMachine declares the transfer
lifecycle. A bounded incremental codec handles ZDLE framing and checksums.
Kaitai is not needed for the five-byte decoded header; its generated
complete-buffer parser would not replace the incremental framing.

## Build and test

```
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew build :testapp:installDist
python3 tests/interop.py  # requires lrzsz (sz and rz) on PATH
python3 tests/benchmark.py  # compares ACK windows with 10 ms response delay
./gradlew :zmodemlib:publishToMavenLocal
```

Formatting uses the same Spotless/ktlint, EditorConfig, and Apache license-header
rules as cbssh. `spotlessApply` formats Kotlin sources, Gradle scripts, workflow
YAML, XML, TOML, and project text files. It works in a new checkout without an
`origin/main` ref. `build` runs `spotlessCheck` and public API compatibility checks.

## GitHub Actions and releases

CI verifies builds and `lrzsz` interoperability on JDK 17, 21, and 25. A manual
benchmark workflow records acknowledgment-window performance. Release issue forms
use the shared `connectbot/actions` release flow; successful tag and snapshot CI
runs use its isolated, attested Maven Central publisher.

Licensed under Apache-2.0. Copyright 2026 Kenny Root.
