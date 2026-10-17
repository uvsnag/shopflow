package com.shopflow.auth.service;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.shopflow.auth.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** NFR-02 (RS256 + JWKS), FR-02 (15-minute access token), NFR-07 (key from env). */
class JwtServiceTest {

    private static User user() {
        User u = new User("ann@example.com", "hash", "Ann");
        ReflectionTestUtils.setField(u, "id", 12L);
        return u;
    }

    @Test
    void tokenIsSignedWithRs256AndVerifiableFromJwks() throws Exception {
        JwtService service = new JwtService(Duration.ofMinutes(15), "");

        SignedJWT jwt = SignedJWT.parse(service.createAccessToken(user()));
        RSAKey publicKey = (RSAKey) JWKSet.parse(service.jwks()).getKeys().get(0);

        assertEquals("RS256", jwt.getHeader().getAlgorithm().getName());
        assertEquals(publicKey.getKeyID(), jwt.getHeader().getKeyID());
        assertTrue(jwt.verify(new RSASSAVerifier(publicKey)));
        assertNull(publicKey.toRSAPrivateKey(), "JWKS exposes the public key only");

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertEquals("12", claims.getSubject());
        assertEquals("CUSTOMER", claims.getStringClaim("role"));
        assertEquals("ann@example.com", claims.getStringClaim("email"));
        assertEquals("shopflow-auth", claims.getIssuer());
        long ttlSeconds = (claims.getExpirationTime().getTime() - Instant.now().toEpochMilli()) / 1000;
        assertTrue(ttlSeconds > 14 * 60 && ttlSeconds <= 15 * 60, "about 15 minutes, was " + ttlSeconds + "s");
    }

    @Test
    void keyFromPemIsStableAcrossRestarts() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        JwtService beforeRestart = new JwtService(Duration.ofMinutes(15), pem);
        JwtService afterRestart = new JwtService(Duration.ofMinutes(15), pem);

        SignedJWT jwt = SignedJWT.parse(beforeRestart.createAccessToken(user()));
        RSAKey keyAfter = (RSAKey) JWKSet.parse(afterRestart.jwks()).getKeys().get(0);
        assertTrue(jwt.verify(new RSASSAVerifier(keyAfter)), "tokens survive a restart with the same key");
        assertEquals(jwt.getHeader().getKeyID(), keyAfter.getKeyID(), "kid is the key thumbprint");
    }

    @Test
    void generatedDevKeysDifferPerInstance() throws Exception {
        JwtService a = new JwtService(Duration.ofMinutes(15), "");
        JwtService b = new JwtService(Duration.ofMinutes(15), "");
        SignedJWT jwt = SignedJWT.parse(a.createAccessToken(user()));
        RSAKey keyB = (RSAKey) JWKSet.parse(b.jwks()).getKeys().get(0);
        assertFalse(jwt.verify(new RSASSAVerifier(keyB)));
    }
}
