package com.mecanicadm.saga_api.infra.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.mecanicadm.saga_api.infra.security.exception.InvalidTokenException;
import com.mecanicadm.saga_api.infra.security.exception.TokenGenerationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class TokenService {
    @Value("${api.security.token.secret}")
    private String secret;

    private static final String ISSUER = "workorder_api";

    public String generateToken(String email) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            return JWT.create()
                    .withIssuer(ISSUER)
                    .withSubject(email)
                    .withExpiresAt(genExpirationDate())
                    .sign(algorithm);
        } catch (JWTCreationException exception) {
            throw new TokenGenerationException("token.generation.error", exception);
        }
    }

    public String validateToken(String token) {
        return decodeToken(token).subject();
    }

    public TokenClaims decodeToken(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            DecodedJWT decoded = JWT.require(algorithm)
                    .withIssuer(ISSUER)
                    .build()
                    .verify(token);
            return new TokenClaims(decoded.getSubject(), decoded.getClaim("role").asString());
        } catch (JWTVerificationException exception) {
            throw new InvalidTokenException();
        }
    }

    private Instant genExpirationDate() {
        return LocalDateTime.now(ZoneOffset.of("-03:00")).plusHours(2).toInstant(ZoneOffset.of("-03:00"));
    }
}
