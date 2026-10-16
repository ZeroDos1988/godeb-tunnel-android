# GoDeb WireGuard Android

Fork of [WireGuard/wireguard-android](https://github.com/WireGuard/wireguard-android) for the GoDeb Debian admin stack.

- Application ID: `de.godeb.wireguard`
- Features: trusted-network auto-disconnect, GoDeb theme, Light/Dark/System
- Full docs: [GODEB.md](./GODEB.md)

## Remotes

| Remote | URL |
|--------|-----|
| `origin` | `git@github.com:ZeroDos1988/godeb-wireguard-android.git` (your fork — create on GitHub if missing) |
| `upstream` | Official WireGuard Android |

## Create the GitHub repository

`gh` is not installed on this VM. On a machine with GitHub CLI:

```bash
# one-time
sudo apt install gh
gh auth login

# create empty repo under your user/org, then push
gh repo create ZeroDos1988/godeb-wireguard-android --private --source=/home/<user>/Projects/wireguard-android --remote=origin
# if origin already exists (as prepared here):
cd /home/<user>/Projects/wireguard-android
git add -A
git status   # review
git commit -m "feat: GoDeb trusted-network auto-disconnect and theme"
git push -u origin master
```

Or create the empty repo in the GitHub UI, then:

```bash
git push -u origin master
```

Keep GoDeb and this Android client as **separate repositories**.
