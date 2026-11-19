package app.roadmaxxing.platform

/** ISO 3166 country code of the device locale (e.g. "TR", "US"), or null when unknown. */
expect fun deviceRegionCode(): String?
