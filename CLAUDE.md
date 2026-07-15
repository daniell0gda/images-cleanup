# Python

Use Python 3.11. It can be found at C:\Python311\python.exe

# Git

Use [Conventional Commits](https://www.conventionalcommits.org/) for all commit messages:
```
<type>[optional scope]: <short description>

[optional body — explain WHY, not what]
```
Common types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`.

Keep commits small and single-purpose — one logical change per commit. If a task touches multiple concerns (e.g. a config change and a sorter change), split into separate commits. A good commit can be understood and reverted in isolation.


# Development

After any frontend change after you are done with a required request call `./copy-docker-essentials.ps1 -Destination \\JUSZKOWO_NAS\quick_access_for_pc\daniel\image-sorter` to update Production Version