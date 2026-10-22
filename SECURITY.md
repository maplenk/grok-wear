# Security policy

Please report vulnerabilities privately using GitHub's **Report a vulnerability** button on this repository's Security tab. Do not open a public issue for a security problem.

Include what you found, how to reproduce it, and the affected version. Never include real sign-in tokens, account identifiers, private conversations or audio. Redact them, or describe them instead.

You will get an acknowledgement within a week. Fixes are released as a new version, and the advisory is published once users can update.

## Scope

In scope: credential storage and handling, the sign-in flow, call admission (the microphone must only open after an explicit Talk), data sent to servers other than Grok Bot's and xAI's, and local data left behind after sign-out.

Out of scope: the Grok Bot service itself, which belongs to xAI, and problems that need physical access to an unlocked watch with developer options enabled.
