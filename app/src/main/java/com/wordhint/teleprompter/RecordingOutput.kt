/*
 * Modified for Wazza's QCam by Warren Gerdes, 2026. See LICENSE and NOTICE.
 */

package com.wordhint.teleprompter

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingOutput {
    const val MIME_TYPE = "video/mp4"
    const val ALBUM_NAME = "WazzaCueCam"

    fun displayName(now: Date = Date()): String =
        "VID_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(now)}.mp4"

    fun relativePath(movieDirectory: String): String =
        "$movieDirectory/$ALBUM_NAME"
}
