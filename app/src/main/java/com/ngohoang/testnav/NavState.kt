package com.ngohoang.testnav

data class NavState(

    // ==========================================
    // FROM NOTIFICATION
    // ==========================================

    val nextDistance: String = "",
    val nextRoad: String = "",

    // ==========================================
    // FROM ACCESSIBILITY (hoặc fallback từ Notification
    // khi Accessibility không có dữ liệu mới)
    // ==========================================

    val instruction: String = "",
    val instructionDistance: String = "",

    val remainingDistance: String = "",
    val remainingTime: String = "",
    val eta: String = "",

    val speed: String = "",

    // ==========================================
    // TRẠNG THÁI NGUỒN DỮ LIỆU (phục vụ debug/UI)
    // ==========================================

    /*
     * true nếu đang có một chuyến đi navigation thực sự
     * (có notification hoặc accessibility data).
     *
     * false sau khi navigation kết thúc/bị hủy -> UI nên
     * hiển thị trạng thái "không có chỉ đường" thay vì dữ
     * liệu cũ.
     */
    val isNavigationActive: Boolean = false,

    /*
     * true nếu Google Maps đang là app hiển thị ở foreground.
     *
     * false nếu người dùng đã rời màn hình Maps (Accessibility
     * không còn đọc được UI) -> các trường instruction/speed
     * lúc này là giá trị suy ra từ Notification, không còn
     * chi tiết như khi đọc trực tiếp từ Accessibility.
     */
    val isMapsForeground: Boolean = true
)