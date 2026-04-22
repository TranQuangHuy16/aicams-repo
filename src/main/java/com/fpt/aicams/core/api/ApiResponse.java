package com.fpt.aicams.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // Quan trọng: Nếu data null thì không hiện field đó trong JSON
public class ApiResponse<T> {

    private int status;           // 200, 400, 500...
    private String message;       // Thông báo: "Success", "Validation Failed"...
    private T data;               // Dữ liệu chính (VD: Student info)
    private Object errors;        // Chi tiết lỗi (VD: { "email": "Invalid format" })
    
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    // Constructor nhanh cho thành công
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .status(200)
                .message("Success")
                .data(data)
                .build();
    }

    // Constructor nhanh cho lỗi
    public static <T> ApiResponse<T> error(int status, String message, Object errors) {
        return ApiResponse.<T>builder()
                .status(status)
                .message(message)
                .errors(errors)
                .build();
    }
}