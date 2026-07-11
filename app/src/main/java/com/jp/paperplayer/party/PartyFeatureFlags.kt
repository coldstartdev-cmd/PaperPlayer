package com.jp.paperplayer.party

/**
 * Toggles for party-mode sync logic that goes beyond the simple, well-proven
 * baseline: each guest chasing HOST_SYNC directly and correcting itself with
 * its own nudge/seek loop. Everything gated here — the host recomputing a
 * guest's drift against its own timeline and forcibly resyncing a guest it
 * judges to be falling behind — is only checkable by ear, one adjustment at
 * a time, which makes it hard to tune with real confidence. Off by default
 * so the reliable path is what ships; flip a flag on here to keep iterating.
 */
object PartyFeatureFlags {
    /**
     * Host-side auto-resync watchdog ([PartyHostEngine.maybeResync]): forces
     * a guest through a fresh SYNC_CHECK + START (a real, audible pause) when
     * host-verified drift looks like it's falling out of sync. The guest's
     * own drift loop keeps self-correcting against HOST_SYNC regardless of
     * this flag — this only controls whether the host additionally
     * intervenes on top of that.
     */
    const val HOST_ASSISTED_RESYNC_ENABLED = false
}
