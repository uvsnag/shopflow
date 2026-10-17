package com.shopflow.auth.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.shopflow.auth.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Signs access tokens with RS256 (NFR-02). Other services only need the
 * public key from /.well-known/jwks.json.
 *
 * The key pair comes from JWT_PRIVATE_KEY_PEM (NFR-07: env locally, AWS
 * Secrets Manager in the cloud). Without it a key is generated at startup —
 * fine for dev, but every restart then invalidates all live access tokens.
 * Generate one with: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final RSAKey rsaKey;
    private final Duration accessTokenTtl;

    public JwtService(@Value("${shopflow.jwt.access-ttl:15m}") Duration accessTokenTtl,
                      @Value("${shopflow.jwt.private-key-pem:}") String privateKeyPem) throws Exception {
        this.accessTokenTtl = accessTokenTtl;

        RSAPublicKey publicKey;
        RSAPrivateKey privateKey;
        if (privateKeyPem != null && !privateKeyPem.isBlank()) {
            String base64 = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
            privateKey = crtKey;
        } else {
            log.warn("JWT_PRIVATE_KEY_PEM not set — generating a throwaway key pair. "
                    + "Every restart will invalidate all access tokens (dev only).");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            publicKey = (RSAPublicKey) keyPair.getPublic();
            privateKey = (RSAPrivateKey) keyPair.getPrivate();
        }
        // kid = key thumbprint, so it stays stable across restarts with the same key
        this.rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE)
                .keyIDFromThumbprint()
                .build();
    }

    public String createAccessToken(User user) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(String.valueOf(user.getId()))
                    .issuer("shopflow-auth")
                    .claim("email", user.getEmail())
                    .claim("role", user.getRole().name())
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plus(accessTokenTtl)))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID(rsaKey.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign JWT", e);
        }
    }

    /** Public keys only, served at /.well-known/jwks.json. */
    public Map<String, Object> jwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }
}
