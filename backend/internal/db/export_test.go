package db

// SetTripsHook makes s call hook at named points inside a sync walk:
// "snapshot" after the walk's snapshot is taken, "checked" after the
// token checks and before the page is read.
func SetTripsHook(s *Trips, hook func(stage string)) { s.hook = hook }
