# Contributing

Issues and pull requests are welcome.

## Before you open a pull request

1. Run the unit tests: `./gradlew :app:testDebugUnitTest`.
2. Check a release build still compiles: `./gradlew :app:assembleRelease`.
3. For UI changes, check the screen in the debug preview on a round 438 × 438 emulator, and say what you checked.

## Ground rules

- **No personal data.** Never commit sign-in tokens, account or Bot identifiers, chat content, audio, logs from a real account, device serials or network addresses. Use synthetic data in tests.
- **The watch stays read-only.** Don't expose tools that change things (sending tasks, approvals, writes) without a design that shows the user exactly what will happen and needs an explicit confirmation.
- **The microphone opens only after an explicit Talk.** Don't add background listening, wake words or automatic call starts.
- **Trademarks.** Don't add xAI logos or other brand assets. See [NOTICE.md](NOTICE.md).
- Keep changes small and match the surrounding code style.
