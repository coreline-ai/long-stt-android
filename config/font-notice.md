# Font notice

Long STT Android does not package `.ttf`, `.otf`, `.woff`, or other redistributed font files.

`app/src/main/java/com/stt/benchmark/ui/theme/Theme.kt` intentionally uses Android platform
`FontFamily.SansSerif` and `FontFamily.Serif` fallbacks. Their rendering and licensing are supplied
by the device system image; this repository makes no separate font redistribution claim.

Any future bundled font must be added to `config/asset-manifest.tsv` with its exact file size,
SHA-256, source, and redistribution notice before it can pass the Android pre-build gate.
