package com.mecanicadm.saga_api.infra.security;

public record TokenClaims(String subject, String role) {
}