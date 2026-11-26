# Security

## Reporting a problem privately

If you find a security problem in FluidMessages, please **don't open a public issue**. Use GitHub's private report instead:

1. Open [Report a vulnerability](https://github.com/Lin8x/FluidMessages/security/advisories/new). You can also get there from the [Security tab](https://github.com/Lin8x/FluidMessages/security) of this repository.
2. Say what you found, how to make it happen, and what someone could do with it.
3. Choose **Submit report**.

Only the maintainer can see the report. You'll get an answer as soon as possible, and credit in the release notes if you'd like it.

Please use made-up names, numbers, and messages in your report. Never send real conversations, passwords, keys, or link codes.

## What counts

Anything that could let someone read, change, or delete messages they shouldn't; get past app lock, the private folder, decoy mode, or emergency erase; read backups without the password; sign in to FluidMessages Web without permission; or install a fake update.

## What is honest to know today

- **Regular texts** are stored by Android, like in any texting app. FluidMessages locks chat-service messages, picture messages, and saved accounts and passwords on the phone, but not Android's own text storage.
- **Locked texts between two FluidMessages users** use a secret code that both people type in. There is no automatic key swap or safety-number check yet.
- **Linking a computer** copies your texts to the computer you run FluidMessages Web on, where they are stored readable by that computer.
- **Downloads** come from GitHub Releases. The Android app checks that updates are signed with the same key before installing them. The Windows and Mac apps aren't code-signed yet. [Check your download](docs/verify.md) shows the official signing key and how to compare SHA-256 sums.

See [docs/privacy.md](docs/privacy.md) for what the app sends and where.
