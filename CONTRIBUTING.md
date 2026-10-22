# Contributing

Issues and pull requests are welcome.

**New here?** Start with an issue labelled [good first issue](https://github.com/maplenk/grok-wear/labels/good%20first%20issue). They are small, self-contained and don't need a Grok Bot account to work on.

**No code needed:** a [device report](https://github.com/maplenk/grok-wear/issues/new?template=device_report.yml) telling us whether Grok Wear works on your watch is one of the most useful contributions.

## Before you open a pull request

1. Run the unit tests: `./gradlew :app:testDebugUnitTest`.
2. Check a release build still compiles: `./gradlew :app:assembleRelease`.
3. For UI changes, check the screen in the debug preview on a round 438 × 438 emulator, and say what you checked.

## Ground rules

- **No personal data.** Never commit sign-in tokens, account or Bot identifiers, chat content, audio, logs from a real account, device serials or network addresses. Use synthetic data in tests.
- **Calls stay read-only.** Don't expose tools that change things (sending tasks, approvals, writes) to the voice model without a design that shows the user exactly what will happen and needs an explicit confirmation. Outside calls the watch writes only what the official apps do: the end-of-call handoff (`CallHandoff`) and pin or unpin. Keep both matching the official clients.
- **The microphone opens only after an explicit Talk.** Don't add background listening, wake words or automatic call starts.
- **Trademarks.** Don't add xAI logos or other brand assets. See [NOTICE.md](NOTICE.md).
- Keep changes small and match the surrounding code style.
