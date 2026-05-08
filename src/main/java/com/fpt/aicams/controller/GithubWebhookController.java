package com.fpt.aicams.controller;

import com.fpt.aicams.core.api.ApiResponse;
import com.fpt.aicams.core.util.GithubSignatureValidator;
import com.fpt.aicams.dto.webhook.WebhookMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/github/webhooks")
@RequiredArgsConstructor
@Tag(name = "GitHub Webhook Integration",description = "API for receiving and processing Webhook events from GitHub (Push, Pull Request)")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class GithubWebhookController {

    GithubSignatureValidator signatureValidator;
    RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.exchange}")
    @NonFinal
    String exchangeName;

    @Value("${app.rabbitmq.routing-key}")
    @NonFinal
    String routingKey;

    @Operation(
            summary = "Receive events from GitHub Webhook",
            description = "This endpoint is called by GitHub whenever a Push or Pull Request event occurs. It validates the HMAC signature (X-Hub-Signature-256) and pushes the payload to RabbitMQ for asynchronous processing, preventing server bottlenecks."
    )
    @PostMapping()
    public ResponseEntity<ApiResponse<String>> handleGithubWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestBody String payload) {

        if (signature == null || eventType == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "Missing headers", null));
        }

        if (!signatureValidator.isValidSignature(payload, signature)) {
            System.err.println("Webhook nhận được nhưng sai chữ ký!");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "Invalid signature", null));
        }

        // ĐÓNG GÓI VÀ GỬI VÀO RABBITMQ
        WebhookMessage message = new WebhookMessage(eventType, payload);
        rabbitTemplate.convertAndSend(exchangeName, routingKey, message);

        System.out.println("Đã đẩy Webhook event '" + eventType + "' vào RabbitMQ.");

        return ResponseEntity.ok(ApiResponse.success("Webhook Queued Successfully"));
    }
}
