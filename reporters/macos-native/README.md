# Hibi Mac

Hibi Mac lives in the menu bar. Click its clock icon to see the last successful sync, pause or resume recording, or quit. The collector receives foreground app changes, idle time, battery charge and power source.

## Install on your Mac

Install Apple's Command Line Tools if `swiftc` is missing. The installer also uses Python 3. Run from this directory:

```sh
./install.sh
```

On first install, enter your collector's HTTPS URL and the token assigned to this Mac. The installer stores the token in `~/.config/hibi/mac.json` with owner-only permissions. It installs `~/Applications/Hibi Mac.app` and starts a user LaunchAgent. Existing settings and the previous app are retained. The menu follows your macOS appearance.

For window titles, choose **允许读取窗口标题…** in the menu and enable Hibi Mac in System Settings. Hibi sends the current foreground app, available window title, and Mission Control desktop number to the private Mac context endpoint.

On macOS 14.2 or later, the same private context includes the app identifiers of currently open audio output streams. This works when the screen is active or idle, and clears when locked or paused. Audio output activity can include silent streams. Apple Music and Spotify separately report play, pause and stop events with song and artist information when available. These events use the authenticated playback endpoint and survive temporary loss of connectivity.

```sh
./install.sh --build-only /tmp/hibi-preview
```

This builds an ad hoc signed app for the current architecture in the chosen directory. Installing on a different Mac can require that Mac's normal app approval. Build again on that Mac if its CPU architecture differs. The Python reporter in the adjacent directory is an alternative. Run one reporter per configured device identity.
