package com.ngohoang.testnav

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListenerService :
    NotificationListenerService() {

    companion object {

        private const val TAG =
            "TestNavNoti"

        private const val MAPS_PACKAGE =
            "com.google.android.apps.maps"
    }

    private fun simplifyNextRoad(
        raw: String?
    ): String? {

        var road =
            raw
                ?.trim()
                ?.replace(
                    Regex("""\s+"""),
                    " "
                )
                ?: return null

        if (road.isBlank()) {
            return null
        }

        // ==========================================
        // ROUNDABOUT WITHOUT ROAD NAME
        // ==========================================

        /*
         * Google Maps đôi khi gửi:
         *
         * At the roundabout, Take the 1st exit
         *
         * Accessibility đã có thông tin:
         *
         * Take the 1st exit
         *
         * Vì vậy chỉ giữ:
         *
         * At the roundabout
         */

        val roundaboutRegex =
            Regex(
                """^\s*(At the roundabout)(?:,\s*.*)?$""",
                RegexOption.IGNORE_CASE
            )

        val roundaboutMatch =
            roundaboutRegex.find(road)

        if (roundaboutMatch != null) {

            return roundaboutMatch
                .groupValues[1]
                .trim()
        }

        // ==========================================
        // CLEANUP
        // ==========================================

        road =
            road.replace(
                ",",
                " "
            )

        road =
            road.replace(
                Regex("""\s+"""),
                " "
            )
                .trim()

        return road
    }

    // ==========================================
    // PARSE SUBTEXT (dữ liệu dự phòng)
    // ==========================================

    /*
     * Google Maps subText thường có dạng:
     *
     * "4 hr 27 min · 284 km · 3:43 PM ETA"
     *
     * tách bằng dấu chấm giữa (· U+00B7), theo thứ tự:
     * [thời gian còn lại] · [khoảng cách còn lại] · [ETA]
     *
     * Dùng dấu chấm giữa thay vì dấu phẩy vì subText có thể
     * chứa dấu phẩy trong tên địa danh ở một số ngôn ngữ.
     *
     * Nếu Maps đổi định dạng/dấu phân cách, hàm này chỉ đơn
     * giản trả về rỗng cho phần không tách được, không throw
     * lỗi, không ảnh hưởng tới các trường khác.
     */
    private data class SubTextInfo(

        val remainingTime: String = "",
        val remainingDistance: String = "",
        val eta: String = ""
    )

    private fun parseSubText(
        raw: String?
    ): SubTextInfo {

        val text =
            raw
                ?.trim()
                ?: return SubTextInfo()

        if (text.isBlank()) {
            return SubTextInfo()
        }

        val parts =
            text
                .split("·", "•")
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }

        val remainingTime =
            parts.getOrNull(0)
                ?: ""

        val remainingDistance =
            parts.getOrNull(1)
                ?: ""

        val etaRaw =
            parts.getOrNull(2)
                ?: ""

        /*
         * "3:43 PM ETA" -> "3:43 PM"
         */
        val eta =
            etaRaw
                .replace(
                    Regex(
                        """\bETA\b""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .trim()

        return SubTextInfo(
            remainingTime = remainingTime,
            remainingDistance = remainingDistance,
            eta = eta
        )
    }

    // ==========================================
    // CREATED
    // ==========================================

    override fun onListenerConnected() {

        super.onListenerConnected()

        Log.d(
            TAG,
            "NOTIFICATION LISTENER CONNECTED"
        )
    }

    // ==========================================
    // NOTIFICATION
    // ==========================================

    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {

        if (
            sbn.packageName != MAPS_PACKAGE
        ) {
            return
        }

        val notification =
            sbn.notification

        val extras =
            notification.extras
                ?: return

        val category =
            notification.category
                ?: ""

        /*
         * Chỉ lấy navigation notification.
         */

        if (
            category !=
            Notification.CATEGORY_NAVIGATION
        ) {
            return
        }

        val title =
            extras.getCharSequence(
                Notification.EXTRA_TITLE
            )
                ?.toString()
                ?.trim()
                ?: ""

        val text =
            extras.getCharSequence(
                Notification.EXTRA_TEXT
            )
                ?.toString()
                ?.trim()
                ?: ""

        val bigText =
            extras.getCharSequence(
                Notification.EXTRA_BIG_TEXT
            )
                ?.toString()
                ?.trim()
                ?: ""

        val subText =
            extras.getCharSequence(
                Notification.EXTRA_SUB_TEXT
            )
                ?.toString()
                ?.trim()
                ?: ""

        /*
         * Google Maps navigation notification
         *
         * title:
         * 40 m
         *
         * text:
         * ĐL Thăng Long
         *
         * subText:
         * 4 hr 32 min · 291 km · ...
         *
         * Ta lấy:
         *
         * title   -> nextDistance
         * text    -> nextRoad
         * subText -> remainingTime / remainingDistance / eta
         *            (dữ liệu DỰ PHÒNG, dùng khi Accessibility
         *            không có sẵn - ví dụ khi Maps bị đẩy xuống
         *            nền)
         */

        val nextDistance =
            title
                .takeIf {
                    it.isNotBlank()
                }


        val nextRoad =
            text
                .takeIf {
                    it.isNotBlank()
                }

        val simplifiedNextRoad =
            simplifyNextRoad(nextRoad)

        val subTextInfo =
            parseSubText(subText)


        NavStateManager.updateFromNotification(

            nextDistance =
                nextDistance,

            nextRoad =
                simplifiedNextRoad,

            remainingDistance =
                subTextInfo.remainingDistance
                    .takeIf {
                        it.isNotBlank()
                    },

            remainingTime =
                subTextInfo.remainingTime
                    .takeIf {
                        it.isNotBlank()
                    },

            eta =
                subTextInfo.eta
                    .takeIf {
                        it.isNotBlank()
                    }
        )

        /*
         * Không log toàn bộ notification mỗi lần.
         *
         * Chỉ log khi cần debug.
         */

        Log.d(
            TAG,
            "NAV NOTIFICATION | " +
                    "distance=[$nextDistance] " +
                    "road=[$nextRoad] " +
                    "bigText=[$bigText] " +
                    "subText=[$subText]"
        )
    }

    // ==========================================
    // REMOVED
    // ==========================================

    override fun onNotificationRemoved(
        sbn: StatusBarNotification
    ) {

        if (
            sbn.packageName != MAPS_PACKAGE
        ) {
            return
        }

        val category =
            sbn.notification
                ?.category
                ?: ""

        /*
         * Chỉ coi là "navigation kết thúc" khi đúng notification
         * navigation bị gỡ - không phải một noti khác (bình luận
         * timeline, quảng cáo, v.v.) của Maps.
         *
         * NavStateManager tự có debounce ngắn trước khi thực sự
         * clear, để tránh nhấp nháy nếu Maps remove rồi post lại
         * gần như ngay lập tức.
         */

        if (
            category ==
            Notification.CATEGORY_NAVIGATION
        ) {

            Log.d(
                TAG,
                "Navigation notification removed -> lên lịch clear state"
            )

            NavStateManager.onNavigationNotificationRemoved()
        }
    }

    // ==========================================
    // DISCONNECTED
    // ==========================================

    override fun onListenerDisconnected() {

        Log.d(
            TAG,
            "NOTIFICATION LISTENER DISCONNECTED"
        )

        super.onListenerDisconnected()
    }
}