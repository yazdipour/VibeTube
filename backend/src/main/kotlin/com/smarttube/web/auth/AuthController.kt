package com.smarttube.web.auth

import com.smarttube.web.youtube.YouTubeAuthService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val youtubeAuthService: YouTubeAuthService,
) {
    @GetMapping("/status")
    fun getStatus(): AuthStatusResponse = AuthMappers.toStatusResponse(youtubeAuthService.getSession())

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun login(@RequestBody(required = false) request: AuthLoginRequest?): AuthLoginResponse {
        val started = youtubeAuthService.startDeviceLogin()
        return AuthMappers.toLoginResponse(started, request?.provider ?: "google")
    }

    @GetMapping("/login/{loginId}")
    fun pollLogin(@PathVariable loginId: String): AuthLoginPollResponse {
        val poll = youtubeAuthService.pollDeviceLogin(loginId)
        val status = if (poll.completed) AuthMappers.toStatusResponse(youtubeAuthService.getSession()) else null
        return AuthMappers.toLoginPollResponse(poll, status)
    }

    @PostMapping("/logout")
    fun logout(): AuthLogoutResponse {
        youtubeAuthService.clearSession()
        return AuthLogoutResponse(
            signedOut = true,
            stub = com.smarttube.web.stub.liveMetadata("Auth logout", "youtube-device-auth"),
        )
    }
}
