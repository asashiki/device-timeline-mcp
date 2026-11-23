# Hibi Mac

Hibi Mac lives in the menu bar. It records foreground app activity and idle time, sends battery state, and can share current context through your collector's private endpoint. The dashboard can open your collector, its Notion view, and selected local apps.

## Install on your Mac

Install Apple's Command Line Tools if `swiftc` is missing. The installer also uses Python 3. Run from this directory:

```sh
./install.sh
```

On first install, enter your collector's HTTPS URL and the token assigned to this Mac. The installer stores the token in `~/.config/hibi/mac.json` with owner-only permissions. It installs `~/Applications/Hibi Mac.app` and starts a user LaunchAgent. Existing settings and the previous app are retained. Open **Hibi Mac** from the menu bar to pause or resume recording.

Optional: add `"toolNames": ["Calendar", "Notes"]` to your configuration to show launch buttons for installed apps. If you want current window titles, choose **允许读取窗口标题** in the app and enable Hibi Mac in System Settings. The title and desktop number go to the private current context endpoint. A named desktop is omitted when Mission Control cannot identify it. Music and Spotify playback use their local player notifications.

```sh
./install.sh --build-only /tmp/hibi-preview
```

This builds an ad hoc signed app for the current architecture in the chosen directory. Installing on a different Mac can require that Mac's normal app approval. Build again on that Mac if its CPU architecture differs. The Python reporter in the adjacent directory is an alternative. Run one reporter per configured device identity.
