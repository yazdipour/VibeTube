package com.smarttube.web.health

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(
    val status: String,
)

@RestController
@RequestMapping("/health", "/api/health")
class HealthController {
    @GetMapping
    fun health(): HealthResponse = HealthResponse(status = "ok")
}
