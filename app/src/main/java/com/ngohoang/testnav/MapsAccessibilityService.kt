package com.ngohoang.testnav

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MapsAccessibilityService :
    AccessibilityService() {

    companion object {

        private const val TAG =
            "TestNavMaps"

        private const val MAPS_PACKAGE =
            "com.google.android.apps.maps"

        private const val ID_STEP_INSTRUCTION =
            "com.google.android.apps.maps:id/step_instruction_container"

        private const val ID_TIME_REMAINING =
            "com.google.android.apps.maps:id/navigation_time_remaining_label"

        private const val ID_SPEEDOMETER =
            "com.google.android.apps.maps:id/speedometer"
    }

    override fun onServiceConnected() {

        super.onServiceConnected()

        Log.d(
            TAG,
            "ACCESSIBILITY SERVICE CONNECTED"
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent
    ) {

        val packageName =
            event.packageName?.toString()
                ?: return

        // ==========================================
        // FOREGROUND APP TRACKING (mọi package)
        // ==========================================

        /*
         * TYPE_WINDOW_STATE_CHANGED là tín hiệu đáng tin cậy nhất
         * cho việc "app nào đang ở foreground". Ta xử lý event
         * này cho MỌI package (không chỉ Maps) để biết chính xác
         * thời điểm người dùng rời khỏi màn hình Maps, thay vì
         * chỉ dựa vào timeout.
         *
         * Điều này khớp với yêu cầu: khi thoát khỏi Maps, dùng
         * ngay dữ liệu từ Notification để bù vào, không cần đợi.
         */

        if (
            event.eventType ==
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {

            if (packageName == MAPS_PACKAGE) {

                NavStateManager.onMapsForegroundChanged(
                    true
                )

            } else {

                NavStateManager.onMapsForegroundChanged(
                    false
                )

                /*
                 * App khác không phải Maps -> không cần đọc
                 * node tree nữa.
                 */
                return
            }
        }

        if (packageName != MAPS_PACKAGE) {
            return
        }

        /*
         * Chỉ xử lý những event có khả năng làm thay đổi
         * navigation UI.
         */
        if (
            event.eventType !=
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType !=
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        val root =
            rootInActiveWindow
                ?: return

        try {

            val data =
                readNavigationState(root)
                    ?: return

            /*
             * Đọc được dữ liệu nghĩa là Maps chắc chắn đang
             * foreground. Gọi lại đây như một lớp an toàn bổ
             * sung, phòng trường hợp lỡ không nhận được event
             * TYPE_WINDOW_STATE_CHANGED (một số thiết bị/OEM có
             * thể bỏ event này khi chuyển màn hình rất nhanh).
             */
            NavStateManager.onMapsForegroundChanged(
                true
            )

            /*
             * Accessibility cập nhật tất cả thông tin ngoại trừ
             * nextRoad chính thức (vẫn ưu tiên từ Notification).
             *
             * roadHint chỉ là dữ liệu DỰ PHÒNG, được
             * NavStateManager dùng khi Notification bị chậm.
             */
            NavStateManager.updateFromAccessibility(

                instruction =
                    data.instruction,

                instructionDistance =
                    data.instructionDistance,

                remainingDistance =
                    data.remainingDistance,

                remainingTime =
                    data.remainingTime,

                eta =
                    data.eta,

                speed =
                    data.speed,

                roadHint =
                    data.roadHint
            )

        } finally {

            root.recycle()
        }
    }

    // ==========================================
    // READ NAVIGATION STATE
    // ==========================================

    private fun readNavigationState(
        root: AccessibilityNodeInfo
    ): AccessibilityData? {

        var instruction = ""
        var instructionDistance = ""
        var roadHint = ""

        var remainingDistance = ""
        var remainingTime = ""

        var eta = ""
        var speed = ""

        // ==========================================
        // INSTRUCTION
        // ==========================================

        val instructionNode =
            root.findAccessibilityNodeInfosByViewId(
                ID_STEP_INSTRUCTION
            ).firstOrNull()

        if (instructionNode != null) {

            val rawInstruction =
                instructionNode.contentDescription
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: instructionNode.text
                        ?.toString()
                        ?.trim()
                        .orEmpty()

            if (rawInstruction.isNotEmpty()) {

                /*
                 * Đây là nơi duy nhất xử lý instruction.
                 *
                 * Ví dụ:
                 *
                 * 250 meters, At the roundabout,
                 * continue straight
                 *
                 * =>
                 *
                 * 250m continue straight
                 */
                instruction =
                    simplifyInstruction(
                        rawInstruction
                    )

                /*
                 * Distance được lấy từ instruction GỐC,
                 * trước khi simplify.
                 */
                instructionDistance =
                    extractDistance(
                        rawInstruction
                    )

                /*
                 * Trích tên đường dự phòng từ instruction gốc,
                 * ví dụ:
                 *
                 * "Turn right onto Nguyễn Trãi"
                 * -> roadHint = "Nguyễn Trãi"
                 *
                 * "Continue onto QL1A"
                 * -> roadHint = "QL1A"
                 *
                 * Chỉ dùng khi Notification (nguồn chính) bị
                 * chậm/thiếu.
                 */
                roadHint =
                    extractRoadHint(
                        rawInstruction
                    )
            }
        }

        // ==========================================
        // REMAINING TIME
        // ==========================================

        val timeNode =
            root.findAccessibilityNodeInfosByViewId(
                ID_TIME_REMAINING
            ).firstOrNull()

        if (timeNode != null) {

            remainingTime =
                timeNode.contentDescription
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: timeNode.text
                        ?.toString()
                        ?.trim()
                        .orEmpty()
        }

        // ==========================================
        // SPEED
        // ==========================================

        val speedNode =
            root.findAccessibilityNodeInfosByViewId(
                ID_SPEEDOMETER
            ).firstOrNull()

        if (speedNode != null) {

            speed =
                speedNode.contentDescription
                    ?.toString()
                    ?.trim()
                    .orEmpty()
        }

        // ==========================================
        // REMAINING DISTANCE + ETA
        // ==========================================

        findNodeWithDescription(
            root,
            "Distance remaining is"
        )?.let { node ->

            val description =
                node.contentDescription
                    ?.toString()
                    ?.trim()
                    .orEmpty()

            val parsed =
                parseRemainingInfo(
                    description
                )

            remainingDistance =
                parsed.distance

            eta =
                parsed.eta
        }

        // ==========================================
        // NOTHING FOUND
        // ==========================================

        if (
            instruction.isEmpty() &&
            remainingTime.isEmpty() &&
            remainingDistance.isEmpty() &&
            eta.isEmpty() &&
            speed.isEmpty()
        ) {
            return null
        }

        /*
         * Log một dòng duy nhất cho mỗi state thực sự
         * đọc được.
         *
         * Không dump toàn bộ Accessibility tree.
         */
        Log.d(
            TAG,
            "ACCESSIBILITY | " +
                    "instruction=[$instruction] | " +
                    "distance=[$instructionDistance] | " +
                    "roadHint=[$roadHint] | " +
                    "remaining=[$remainingDistance] | " +
                    "time=[$remainingTime] | " +
                    "eta=[$eta] | " +
                    "speed=[$speed]"
        )

        return AccessibilityData(

            instruction =
                instruction,

            instructionDistance =
                instructionDistance,

            roadHint =
                roadHint,

            remainingDistance =
                remainingDistance,

            remainingTime =
                remainingTime,

            eta =
                eta,

            speed =
                speed
        )
    }

    // ==========================================
    // SIMPLIFY INSTRUCTION
    // ==========================================

    private fun simplifyInstruction(
        raw: String
    ): String {

        var text =
            raw
                .trim()
                .replace(
                    Regex("""\s+"""),
                    " "
                )

        // ==========================================
        // DISTANCE
        // ==========================================

        /*
         * 250 meters -> 250m
         * 250 m      -> 250m
         */

        text =
            text.replace(
                Regex(
                    """(\d+(?:[.,]\d+)?)\s*(meters?|m)\b""",
                    RegexOption.IGNORE_CASE
                )
            ) { match ->

                "${match.groupValues[1]}m"
            }

        /*
         * 1.2 kilometers -> 1.2km
         * 1.2 km         -> 1.2km
         */

        text =
            text.replace(
                Regex(
                    """(\d+(?:[.,]\d+)?)\s*(kilometers?|km)\b""",
                    RegexOption.IGNORE_CASE
                )
            ) { match ->

                "${match.groupValues[1]}km"
            }

        // ==========================================
        // ROUNDABOUT
        // ==========================================

        /*
         * Remove:
         *
         * At the roundabout,
         *
         * Example:
         *
         * 250m, At the roundabout, Take the 1st exit
         *
         * =>
         *
         * 250m Take the 1st exit
         */

        text =
            text.replace(
                Regex(
                    """,?\s*At the roundabout,?\s*""",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )

        text =
            text.replace(
                Regex(
                    """^\s*At the roundabout,?\s*""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )

        // ==========================================
        // TAKE THE EXIT
        // ==========================================

        /*
         * Take the 1st exit -> Take 1st exit
         * Take the 2nd exit -> Take 2nd exit
         * Take the 3rd exit -> Take 3rd exit
         * Take the 4th exit -> Take 4th exit
         */

        text =
            text.replace(
                Regex(
                    """\bTake\s+the\s+(\d+(?:st|nd|rd|th))\s+exit\b""",
                    RegexOption.IGNORE_CASE
                )
            ) { match ->

                "Take ${match.groupValues[1]} exit"
            }

        // ==========================================
        // MERGE
        // ==========================================

        /*
         * Merge onto the ramp -> Merge ramp
         */

        text =
            text.replace(
                Regex(
                    """\bMerge\s+onto\s+the\s+ramp\b""",
                    RegexOption.IGNORE_CASE
                ),
                "Merge ramp"
            )

        /*
         * Merge onto [road]
         *
         * Không tự động bỏ tên đường vì tên đường
         * có thể quan trọng.
         */

        // ==========================================
        // TURN
        // ==========================================

        /*
         * Turn slight left -> Slight left
         * Turn slight right -> Slight right
         */

        text =
            text.replace(
                Regex(
                    """\bTurn\s+slight\s+(left|right)\b""",
                    RegexOption.IGNORE_CASE
                )
            ) { match ->

                "Slight ${match.groupValues[1]}"
            }

        /*
         * Turn sharp left -> Sharp left
         * Turn sharp right -> Sharp right
         */

        text =
            text.replace(
                Regex(
                    """\bTurn\s+sharp\s+(left|right)\b""",
                    RegexOption.IGNORE_CASE
                )
            ) { match ->

                "Sharp ${match.groupValues[1]}"
            }

        // ==========================================
        // CONTINUE
        // ==========================================

        /*
         * Hiện tại giữ nguyên:
         *
         * Continue straight
         *
         * vì vẫn đủ ngắn và dễ hiểu.
         *
         * Nếu sau này ODO quá hẹp, có thể đổi thành:
         *
         * Straight
         */

        // ==========================================
        // REMOVE COMMAS
        // ==========================================

        text =
            text.replace(
                ",",
                " "
            )

        // ==========================================
        // CLEANUP
        // ==========================================

        text =
            text.replace(
                Regex("""\s+"""),
                " "
            )
                .trim()

        return text
    }

    // ==========================================
    // EXTRACT DISTANCE
    // ==========================================

    private fun extractDistance(
        text: String
    ): String {

        val regex =
            Regex(
                """^\s*([\d.,]+\s*(?:meters?|m|kilometers?|km))\b""",
                RegexOption.IGNORE_CASE
            )

        return regex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.replace(
                Regex("""\s+"""),
                ""
            )
            ?.replace(
                Regex(
                    """meters?""",
                    RegexOption.IGNORE_CASE
                ),
                "m"
            )
            ?.replace(
                Regex(
                    """kilometers?""",
                    RegexOption.IGNORE_CASE
                ),
                "km"
            )
            ?: ""
    }

    // ==========================================
    // EXTRACT ROAD HINT (dự phòng cho nextRoad)
    // ==========================================

    /*
     * Trích tên đường/địa danh từ các cụm phổ biến trong câu
     * chỉ đường của Google Maps:
     *
     * "... onto <road>"
     * "... toward <road>"
     *
     * Chỉ lấy phần sau cụm từ khóa, cắt bỏ nếu có thêm mệnh đề
     * phía sau bị dính do cấu trúc câu dài.
     */
    private fun extractRoadHint(
        raw: String
    ): String {

        val ontoRegex =
            Regex(
                """\bonto\s+(.+)$""",
                RegexOption.IGNORE_CASE
            )

        val towardRegex =
            Regex(
                """\btoward(?:s)?\s+(.+)$""",
                RegexOption.IGNORE_CASE
            )

        val ontoMatch =
            ontoRegex.find(raw)

        if (ontoMatch != null) {

            return ontoMatch
                .groupValues[1]
                .trim()
                .trimEnd('.')
        }

        val towardMatch =
            towardRegex.find(raw)

        if (towardMatch != null) {

            return towardMatch
                .groupValues[1]
                .trim()
                .trimEnd('.')
        }

        return ""
    }

    // ==========================================
    // FIND NODE
    // ==========================================

    private fun findNodeWithDescription(
        node: AccessibilityNodeInfo?,
        phrase: String
    ): AccessibilityNodeInfo? {

        if (node == null) {
            return null
        }

        val description =
            node.contentDescription
                ?.toString()
                ?: ""

        if (
            description.contains(
                phrase,
                ignoreCase = true
            )
        ) {
            return node
        }

        for (i in 0 until node.childCount) {

            val child =
                node.getChild(i)

            val result =
                findNodeWithDescription(
                    child,
                    phrase
                )

            if (result != null) {
                return result
            }

            child?.recycle()
        }

        return null
    }

    // ==========================================
    // PARSE REMAINING INFO
    // ==========================================

    private fun parseRemainingInfo(
        text: String
    ): RemainingInfo {

        var distance = ""
        var eta = ""

        val distanceRegex =
            Regex(
                """Distance remaining is\s+(.+?),\s*estimated time""",
                RegexOption.IGNORE_CASE
            )

        val etaRegex =
            Regex(
                """estimated time of arrival is\s+(.+)$""",
                RegexOption.IGNORE_CASE
            )

        distanceRegex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.let {
                distance = it
            }

        etaRegex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.let {
                eta = it
            }

        return RemainingInfo(
            distance = distance,
            eta = eta
        )
    }

    // ==========================================
    // INTERRUPT
    // ==========================================

    override fun onInterrupt() {

        Log.d(
            TAG,
            "ACCESSIBILITY SERVICE INTERRUPTED"
        )
    }

    // ==========================================
    // DATA
    // ==========================================

    private data class AccessibilityData(

        val instruction: String,

        val instructionDistance: String,

        val roadHint: String,

        val remainingDistance: String,

        val remainingTime: String,

        val eta: String,

        val speed: String
    )

    private data class RemainingInfo(

        val distance: String,

        val eta: String
    )
}