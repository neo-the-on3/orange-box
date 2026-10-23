# Legacy reference images: for reference only

These images show the legacy **CarooLive** app by Pokevian Inc. They come from the `screenshots/` folder of
[Pokevian/caroolive-app](https://github.com/Pokevian/caroolive-app) (Apache License 2.0, © 2017 Pokevian Inc.).
**Modified:** the status bar is cropped off (it showed other apps' notification icons) and the images are downscaled to 50 %.

- They are a **visual reference** for `docs/ui/LEGACY_OBD_UI.md` only.
- They are **never** packaged, embedded, traced, or used as artwork in Orange Box. All app artwork is our own vector drawables or Compose Canvas drawing.
- Not committed: images that show personal data (an e-mail address in the drawer and service-montage shots), dashcam footage (dashcam is dropped), screens that move to Material 3 (trip list, settings), and Kiran's own device screenshots. Those stay on the build box under `/workspace/analysis/ui-reference/`.

| File | Legacy screen |
|---|---|
| `driving_mode.png` | Driving dashboard, landscape, clusters (no data, engine off) |
| `driving_details.png` | Driving dashboard, landscape, "More Info." detail grid, page 1 |
| `driving_pattern.png` | Driving Pattern (server web page) |
| `trip_stats.png` | Trip Stats (server web page) |
| `car_status.png` | Car Status (server web page) |
