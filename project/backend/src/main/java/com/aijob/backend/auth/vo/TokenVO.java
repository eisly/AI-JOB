package com.aijob.backend.auth.vo;

public record TokenVO(String accessToken, long expiresIn, UserVO user) {
}