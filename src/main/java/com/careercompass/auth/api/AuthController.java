package com.careercompass.auth.api;

import com.careercompass.auth.application.AuthService;
import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "1. 인증", description = "API_SPEC §1 — 소셜 로그인·재발급·로그아웃·지문 등록")
@RestController
@RequestMapping("/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  public record SocialLoginRequest(
      @NotBlank String accessToken, @NotBlank String deviceId, String fcmToken) {}

  public record UserSummary(Long id, String email, String name, boolean onboardingDone) {}

  public record LoginResponse(String accessToken, String refreshToken, UserSummary user) {}

  @Operation(summary = "소셜 로그인", description = "provider 는 kakao | google. 최초 로그인 시 사용자 생성.")
  @PostMapping("/social/{provider}")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<LoginResponse> socialLogin(
      @PathVariable String provider, @Valid @RequestBody SocialLoginRequest request) {
    AuthService.LoginResult result =
        authService.socialLogin(
            provider, request.accessToken(), request.deviceId(), request.fcmToken());
    return ApiResponse.success(
        new LoginResponse(
            result.accessToken(),
            result.refreshToken(),
            new UserSummary(
                result.userId(), result.email(), result.name(), result.onboardingDone())));
  }

  public record RefreshRequest(@NotBlank String refreshToken) {}

  public record RefreshResponse(String accessToken, String refreshToken) {}

  @Operation(
      summary = "토큰 재발급",
      description = "리프레시는 매번 회전. 유예(30초) 밖에서 소비된 토큰이 재사용되면 해당 기기 세션 전체가 폐기되고 AUTH_INVALID.")
  @PostMapping("/refresh")
  public ApiResponse<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
    AuthService.TokenPair pair = authService.refresh(request.refreshToken());
    return ApiResponse.success(new RefreshResponse(pair.accessToken(), pair.refreshToken()));
  }

  public record LogoutRequest(@NotBlank String deviceId) {}

  @Operation(summary = "로그아웃", description = "이 기기의 리프레시 무효화 + FCM 해제 + 지문 등록 폐기. 멱등.")
  @PostMapping("/logout")
  public ApiResponse<Void> logout(
      @CurrentUserId Long userId, @Valid @RequestBody LogoutRequest request) {
    authService.logout(userId, request.deviceId());
    return ApiResponse.success(null);
  }

  public record BiometricRegisterRequest(@NotBlank String deviceId) {}

  @Operation(
      summary = "지문 로그인 기기 등록",
      description = "기기 등록은 (계정, 기기) 단위 — 같은 기기의 다른 계정 등록을 덮지 않는다.")
  @PostMapping("/biometric/register")
  public ApiResponse<Void> registerBiometric(
      @CurrentUserId Long userId, @Valid @RequestBody BiometricRegisterRequest request) {
    authService.registerBiometric(userId, request.deviceId());
    return ApiResponse.success(null);
  }
}
