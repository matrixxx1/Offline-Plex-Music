# Offline Plex Music 0.11.1

Fixes a download-time freeze path: phone controls no longer wait on the UI thread while downloads save the library. Download finalization now uses one atomic library update and keeps slow document-provider calls outside its lock. Transfers also skip unnecessary filter rebuilding, and Android Auto browse refresh work runs in the background.

Install over your existing app to retain your library, downloaded files, queued ratings, and playlist edits. Then open Downloads > Transfers > Resume / retry if the queue needs restarting.

Includes a regression with 31,382 songs and an intentionally blocked library writer. No physical phone was connected, so the exact ANR trace from the reported device has not been verified.
