package app.roadmaxxing.shared.domain

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

// NOT VERIFIED: iosMain cannot be compiled on the developer's Windows machine; check on the next CI run.
actual fun currentTimeMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
