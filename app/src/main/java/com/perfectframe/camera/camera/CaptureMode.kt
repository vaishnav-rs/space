package com.perfectframe.camera.camera

/** The two top-level shooting experiences (spec extension: "two modes"). */
enum class CaptureMode(val label: String) {
    /** The smart autoframer: tap inside the suggested frame to crop, straighten, and capture. */
    FRAMEICA("FRAMEICA"),

    /** A skeuomorphic film-roll ritual: load a stock, wind between shots, develop for real time. */
    FILM("FILM"),
}
