# AGENTS.md

## Scope

- These scripts provide local Android emulator and device workflows for Rommio.
- They assume the Android project root is the repository root.

## Expectations

- Keep scripts POSIX shell friendly where practical and non-interactive by default.
- Update `common.sh` first when repository paths or package identifiers change.
- Prefer adding new helper scripts here rather than embedding long device setup instructions in the README.
