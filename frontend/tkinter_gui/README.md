# kei Tkinter GUI

Tkinter desktop GUI for the kei manga translator. Run from repo root:

    python -m frontend.tkinter_gui.main [chapter path]

Requires `pip install -e backend`. Talks to the backend only via
`kei_core.service` / `kei_core.config`; slow work runs on a worker thread.
