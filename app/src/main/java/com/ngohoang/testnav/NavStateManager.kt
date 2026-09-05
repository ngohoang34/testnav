package com.ngohoang.testnav

import android.os.Handler
import android.os.Looper
import android.util.Log

object NavStateManager {

    private const val TAG = "TestNavMaps"

    /*
     * Nếu Accessibility không cập nhật gì trong khoảng thời gian
     * này, coi như dữ liệu đã cũ (an toàn dự phòng phòng trường
     * hợp lỡ không bắt được sự kiện đổi foreground app).
     */
    private const val ACCESSIBILITY_STALE_TIMEOUT_MS = 6_000L

    /*
     * nextRoad ưu tiên lấy từ Notification, nhưng nếu Notification
     * không cập nhật trong khoảng thời gian này (bị hệ thống/OEM
     * throttle), sẽ dùng road name gợi ý được trích từ câu lệnh
     * chỉ đường của Accessibility (nếu có) thay vì hiển thị dữ
     * liệu quá cũ.
     */
    private const val NEXT_ROAD_NOTIFICATION_STALE_MS = 8_000L

    /*
     * Khi notification navigation bị remove, đợi một khoảng ngắn
     * trước khi thực sự clear state. Một số thiết bị/OEM remove
     * rồi post lại notification gần như ngay lập tức (do rebuild
     * silent), nên clear ngay sẽ gây nhấp nháy UI.
     */
    private const val NOTIFICATION_REMOVE_GRACE_MS = 2_500L

    private val mainHandler =
        Handler(Looper.getMainLooper())

    // ==========================================
    // RAW SOURCES (dữ liệu thô, chưa merge)
    // ==========================================

    private data class NotificationRaw(

        val nextDistance: String = "",
        val nextRoad: String = "",

        val remainingDistance: String = "",
        val remainingTime: String = "",
        val eta: String = "",

        val lastUpdateAt: Long = 0L
    )

    private data class AccessibilityRaw(

        val instruction: String = "",
        val instructionDistance: String = "",

        val remainingDistance: String = "",
        val remainingTime: String = "",
        val eta: String = "",
        val speed: String = "",

        /*
         * Tên đường gợi ý, trích từ instruction (vd: "... onto
         * Nguyễn Trãi" -> "Nguyễn Trãi"). Chỉ dùng làm dự phòng
         * khi Notification bị chậm/thiếu.
         */
        val roadHint: String = "",

        val lastUpdateAt: Long = 0L
    )

    @Volatile
    private var notificationRaw = NotificationRaw()

    @Volatile
    private var accessibilityRaw = AccessibilityRaw()

    @Volatile
    private var mapsInForeground = true

    @Volatile
    private var navigationActive = false

    private var pendingClearRunnable: Runnable? = null

    @Volatile
    private var state = NavState()

    private val listeners =
        mutableSetOf<Listener>()

    interface Listener {

        fun onNavStateChanged(
            state: NavState
        )
    }

    // ==========================================
    // CURRENT STATE
    // ==========================================

    val currentState: NavState
        get() = state

    // ==========================================
    // LISTENERS
    // ==========================================

    @Synchronized
    fun addListener(
        listener: Listener
    ) {

        listeners.add(listener)

        listener.onNavStateChanged(
            state
        )
    }

    @Synchronized
    fun removeListener(
        listener: Listener
    ) {

        listeners.remove(listener)
    }

    private fun notifyListeners(
        newState: NavState
    ) {

        val snapshot =
            synchronized(this) {
                listeners.toList()
            }

        snapshot.forEach { listener ->

            try {

                listener.onNavStateChanged(
                    newState
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Listener error",
                    e
                )
            }
        }
    }

    // ==========================================
    // NOTIFICATION UPDATE
    // ==========================================

    @Synchronized
    fun updateFromNotification(

        nextDistance: String? = null,
        nextRoad: String? = null,

        remainingDistance: String? = null,
        remainingTime: String? = null,
        eta: String? = null
    ) {

        /*
         * Có notification navigation mới -> chuyến đi đang active.
         * Hủy lịch clear đang chờ, phòng trường hợp Maps vừa remove
         * rồi post lại gần như ngay lập tức.
         */
        cancelPendingClear()

        navigationActive = true

        val old = notificationRaw

        notificationRaw =
            old.copy(

                nextDistance =
                    nextDistance
                        ?: old.nextDistance,

                nextRoad =
                    nextRoad
                        ?: old.nextRoad,

                remainingDistance =
                    remainingDistance
                        ?: old.remainingDistance,

                remainingTime =
                    remainingTime
                        ?: old.remainingTime,

                eta =
                    eta
                        ?: old.eta,

                lastUpdateAt =
                    System.currentTimeMillis()
            )

        recompute()
    }

    // ==========================================
    // NOTIFICATION REMOVED (nav kết thúc / bị hủy)
    // ==========================================

    @Synchronized
    fun onNavigationNotificationRemoved() {

        cancelPendingClear()

        val runnable =
            Runnable {

                synchronized(this) {

                    Log.d(
                        TAG,
                        "Navigation notification removed quá lâu -> CLEAR state"
                    )

                    clearInternal()
                }
            }

        pendingClearRunnable = runnable

        mainHandler.postDelayed(
            runnable,
            NOTIFICATION_REMOVE_GRACE_MS
        )
    }

    private fun cancelPendingClear() {

        pendingClearRunnable?.let {

            mainHandler.removeCallbacks(it)
        }

        pendingClearRunnable = null
    }

    // ==========================================
    // ACCESSIBILITY UPDATE
    // ==========================================

    @Synchronized
    fun updateFromAccessibility(

        instruction: String? = null,
        instructionDistance: String? = null,

        remainingDistance: String? = null,
        remainingTime: String? = null,
        eta: String? = null,

        speed: String? = null,

        roadHint: String? = null
    ) {

        navigationActive = true

        val old = accessibilityRaw

        accessibilityRaw =
            old.copy(

                instruction =
                    instruction
                        ?: old.instruction,

                instructionDistance =
                    instructionDistance
                        ?: old.instructionDistance,

                remainingDistance =
                    remainingDistance
                        ?: old.remainingDistance,

                remainingTime =
                    remainingTime
                        ?: old.remainingTime,

                eta =
                    eta
                        ?: old.eta,

                speed =
                    speed
                        ?: old.speed,

                roadHint =
                    roadHint
                        ?: old.roadHint,

                lastUpdateAt =
                    System.currentTimeMillis()
            )

        recompute()
    }

    // ==========================================
    // MAPS FOREGROUND STATE
    // ==========================================

    /*
     * Gọi khi AccessibilityService phát hiện app foreground đổi
     * từ/sang Google Maps. Đây là tín hiệu tức thời, đáng tin hơn
     * so với việc chỉ dựa vào timeout.
     */
    @Synchronized
    fun onMapsForegroundChanged(
        inForeground: Boolean
    ) {

        if (mapsInForeground == inForeground) {
            return
        }

        mapsInForeground = inForeground

        Log.d(
            TAG,
            "Maps foreground changed -> $inForeground"
        )

        if (!inForeground) {

            /*
             * Rời khỏi Maps: Accessibility sẽ không còn cập nhật.
             * speed không có nguồn thay thế nào khác (Notification
             * không chứa tốc độ) -> xóa để tránh hiển thị tốc độ
             * cũ/sai, có thể gây hiểu lầm cho người lái.
             */
            accessibilityRaw =
                accessibilityRaw.copy(
                    speed = ""
                )
        }

        recompute()
    }

    // ==========================================
    // RECOMPUTE DISPLAY STATE
    // ==========================================

    /*
     * Quy tắc ưu tiên:
     *
     * - nextRoad: ưu tiên Notification nếu còn "mới" (trong
     *   ngưỡng NEXT_ROAD_NOTIFICATION_STALE_MS). Nếu Notification
     *   bị chậm/throttle, dùng roadHint trích từ Accessibility.
     *   Nếu cả hai đều không có, giữ giá trị Notification cũ thay
     *   vì để trống.
     *
     * - instruction/instructionDistance/remainingDistance/
     *   remainingTime/eta/speed: ưu tiên Accessibility KHI Maps
     *   đang foreground VÀ dữ liệu còn mới. Khi Maps không
     *   foreground (hoặc dữ liệu accessibility đã cũ quá ngưỡng),
     *   dùng dữ liệu suy ra từ Notification làm phương án dự
     *   phòng - "instruction" chi tiết (loại rẽ) không có trong
     *   Notification nên tạm dùng nextRoad/nextDistance để ODO
     *   không bị trống hoàn toàn.
     */
    private fun recompute() {

        val now =
            System.currentTimeMillis()

        val accessibilityFresh =
            mapsInForeground &&
                    accessibilityRaw.lastUpdateAt > 0 &&
                    (now - accessibilityRaw.lastUpdateAt) <=
                    ACCESSIBILITY_STALE_TIMEOUT_MS

        val notificationRoadFresh =
            notificationRaw.nextRoad.isNotBlank() &&
                    (now - notificationRaw.lastUpdateAt) <=
                    NEXT_ROAD_NOTIFICATION_STALE_MS

        val resolvedNextRoad =
            when {

                notificationRoadFresh ->
                    notificationRaw.nextRoad

                accessibilityRaw.roadHint.isNotBlank() ->
                    accessibilityRaw.roadHint

                else ->
                    notificationRaw.nextRoad
            }

        val newState =
            if (accessibilityFresh) {

                state.copy(

                    nextDistance =
                        notificationRaw.nextDistance,

                    nextRoad =
                        resolvedNextRoad,

                    instruction =
                        accessibilityRaw.instruction,

                    instructionDistance =
                        accessibilityRaw.instructionDistance,

                    remainingDistance =
                        accessibilityRaw.remainingDistance
                            .ifBlank {
                                notificationRaw.remainingDistance
                            },

                    remainingTime =
                        accessibilityRaw.remainingTime
                            .ifBlank {
                                notificationRaw.remainingTime
                            },

                    eta =
                        accessibilityRaw.eta
                            .ifBlank {
                                notificationRaw.eta
                            },

                    speed =
                        accessibilityRaw.speed,

                    isNavigationActive =
                        navigationActive,

                    isMapsForeground =
                        mapsInForeground
                )

            } else {

                state.copy(

                    nextDistance =
                        notificationRaw.nextDistance,

                    nextRoad =
                        resolvedNextRoad,

                    /*
                     * Không có nguồn nào khác cho "instruction"
                     * chi tiết khi Maps ở nền -> tạm hiển thị
                     * tên đường/khoảng cách kế tiếp thay vì để
                     * trống hoặc giữ dữ liệu accessibility đã
                     * lỗi thời (dễ gây hiểu lầm hơn là hữu ích).
                     */
                    instruction =
                        resolvedNextRoad,

                    instructionDistance =
                        notificationRaw.nextDistance,

                    remainingDistance =
                        notificationRaw.remainingDistance
                            .ifBlank {
                                accessibilityRaw.remainingDistance
                            },

                    remainingTime =
                        notificationRaw.remainingTime
                            .ifBlank {
                                accessibilityRaw.remainingTime
                            },

                    eta =
                        notificationRaw.eta
                            .ifBlank {
                                accessibilityRaw.eta
                            },

                    speed =
                        "",

                    isNavigationActive =
                        navigationActive,

                    isMapsForeground =
                        mapsInForeground
                )
            }

        if (newState != state) {

            state = newState

            notifyListeners(state)
        }
    }

    // ==========================================
    // CLEAR
    // ==========================================

    @Synchronized
    fun clear() {

        cancelPendingClear()

        clearInternal()
    }

    private fun clearInternal() {

        val oldState = state

        notificationRaw = NotificationRaw()
        accessibilityRaw = AccessibilityRaw()

        mapsInForeground = true
        navigationActive = false

        state = NavState()

        if (state != oldState) {

            notifyListeners(state)
        }
    }
}