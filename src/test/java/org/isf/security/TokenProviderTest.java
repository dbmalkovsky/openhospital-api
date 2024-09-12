/*
 * Open Hospital (www.open-hospital.org)
 * Copyright © 2006-2024 Informatici Senza Frontiere (info@informaticisenzafrontiere.org)
 *
 * Open Hospital is a free and open source software for healthcare data management.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * https://www.gnu.org/licenses/gpl-3.0-standalone.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.isf.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.assertj.core.data.Offset;
import org.isf.OpenHospitalApiApplication;
import org.isf.security.jwt.TokenProvider;
import org.isf.security.jwt.TokenValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

@SpringBootTest(classes = OpenHospitalApiApplication.class)
public class TokenProviderTest {

	@Autowired
	private TokenProvider tokenProvider;

	@BeforeEach
	public void setUp() {
		tokenProvider.init();
	}

	@Test
	public void testGenerateJwtToken() throws NoSuchFieldException, IllegalAccessException {
		Authentication authentication = createAuthentication();
		Key key = extractKeyFromTokenProvider();

		String token = tokenProvider.generateJwtToken(authentication, false);

		// Get Claims from token
		Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();

		// Assert the claims
		assertThat(claims.getSubject()).isEqualTo("testuser");
		assertThat(claims.get("auth")).isEqualTo("ROLE_USER");
	}

	@Test
	public void testValidateToken_Valid() {
		Authentication authentication = createAuthentication();

		// Generate token
		String token = tokenProvider.generateJwtToken(authentication, false);

		// Validate the token using tokenProvider
		TokenValidationResult result = tokenProvider.validateToken(token);

		// Assert that the token is valid
		assertThat(result).isEqualTo(TokenValidationResult.VALID);
	}

	@Test
	public void testValidateToken_Expired() throws Exception {
		Key key = extractKeyFromTokenProvider();

		// Create an expired token by setting the expiration date in the past
		String expiredToken = Jwts.builder()
						.setSubject("testuser")
						.claim("auth", "ROLE_USER")
						.signWith(key, SignatureAlgorithm.HS512)
						.setExpiration(new Date(System.currentTimeMillis() - 1000))
						.compact();

		// Validate the expired token
		TokenValidationResult result = tokenProvider.validateToken(expiredToken);

		// Assert that the token is expired
		assertThat(result).isEqualTo(TokenValidationResult.EXPIRED);
	}

	@Test
	public void testValidateToken_Malformed() {
		String malformedToken = "malformed.token";

		// Validate the token using tokenProvider
		TokenValidationResult result = tokenProvider.validateToken(malformedToken);

		assertThat(result).isEqualTo(TokenValidationResult.MALFORMED);
	}

	@Test
	public void testValidateToken_InvalidSignature() {
		String invalidSignatureToken = "eyJhbGciOiJIUzI1NiJ9.MISSING_PART.HMAC_SIGNATURE";

		// Validate the token using tokenProvider
		TokenValidationResult result = tokenProvider.validateToken(invalidSignatureToken);

		assertThat(result).isEqualTo(TokenValidationResult.INVALID_SIGNATURE);
	}

	@Test
	public void testValidateToken_Unsupported() throws Exception {
		KeyPair keyPair = generateRsaKeyPair();

		// Create a JWT token signed with RS256 (RSA algorithm) instead of HS512
		String unsupportedToken = Jwts.builder()
						.setSubject("testuser")
						.signWith(keyPair.getPrivate(), SignatureAlgorithm.RS256)
						.compact();

		// Validate the token using tokenProvider
		TokenValidationResult result = tokenProvider.validateToken(unsupportedToken);

		assertThat(result).isEqualTo(TokenValidationResult.UNSUPPORTED);
	}

	@Test
	public void testValidateToken_EmptyClaims() throws Exception {
		Key key = extractKeyFromTokenProvider();

		// Create a token with empty claims
		String emptyClaimsToken = Jwts.builder()
						.setSubject("") // Set empty subject (claims are present but empty)
						.claim("auth", "") // Set empty authority claims
						.signWith(key, SignatureAlgorithm.HS512)
						.compact();

		// Validate the token using tokenProvider
		TokenValidationResult result = tokenProvider.validateToken(emptyClaimsToken);

		assertThat(result).isEqualTo(TokenValidationResult.EMPTY_CLAIMS);
	}

	@Test
	public void testGetAuthentication() {
		Authentication authentication = createAuthentication();

		// Generate token
		String token = tokenProvider.generateJwtToken(authentication, false);

		// Retrieve Authentication object from token
		Authentication result = tokenProvider.getAuthentication(token);

		// Verify that the result is of the correct type and has the expected details
		assertThat(result).isInstanceOf(UsernamePasswordAuthenticationToken.class);

		UsernamePasswordAuthenticationToken authToken = (UsernamePasswordAuthenticationToken) result;

		// Check principal
		assertThat(((User) authToken.getPrincipal()).getUsername()).isEqualTo("testuser");

		// Check authorities
		Collection< ? extends GrantedAuthority> resultAuthorities = authToken.getAuthorities();
		assertThat(resultAuthorities).extracting(GrantedAuthority::getAuthority).contains("ROLE_USER");

		// Check credentials
		assertThat(authToken.getCredentials()).isEqualTo(token);
	}

	@Test
	public void testSetJwtParser() {
		JwtParser mockJwtParser = mock(JwtParser.class);
		tokenProvider.setJwtParser(mockJwtParser);
	}

	@Test
	public void testGetUsernameFromToken() {
		Authentication authentication = createAuthentication();

		// Generate token
		String token = tokenProvider.generateJwtToken(authentication, false);
		String username = tokenProvider.getUsernameFromToken(token);

		assertThat(username).isEqualTo("testuser");
	}

	@Test
	public void testGetAllClaimsFromToken() {
		Authentication authentication = createAuthentication();

		// Generate token
		String token = tokenProvider.generateJwtToken(authentication, false);
		Claims claims = tokenProvider.getAllClaimsFromToken(token);

		assertThat(claims).isNotNull();
		assertThat(claims.getSubject()).isEqualTo("testuser");
	}

	@Test
	public void testGetExpirationDateFromToken() {
		Authentication authentication = createAuthentication();

		// Generate token
		String token = tokenProvider.generateJwtToken(authentication, false);
		Date expirationDate = tokenProvider.getExpirationDateFromToken(token);

		assertThat(expirationDate).isNotNull();
		assertThat(expirationDate).isAfter(new Date());
	}

	@Test
	public void testGetClaimFromToken() {
		Authentication authentication = createAuthentication();

		// Generate token
		String token = tokenProvider.generateJwtToken(authentication, false);

		// Get claim from token
		String subject = tokenProvider.getClaimFromToken(token, Claims::getSubject);

		// Assert the claim
		assertThat(subject).isEqualTo("testuser");
	}

	@Test
	public void testIsTokenExpired() throws Exception {
		Authentication authentication = createAuthentication();
		Key key = extractKeyFromTokenProvider();

		// Generate token
		String token = tokenProvider.generateJwtToken(authentication, false);

		// Adjust the token to be expired by manually creating an expired token
		Date now = new Date();
		Date expiredDate = new Date(now.getTime() - 1000); // 1 second in the past
		String expiredToken = Jwts.builder()
						.setClaims(Jwts.parserBuilder()
										.setSigningKey(key)
										.build()
										.parseClaimsJws(token)
										.getBody())
						.setExpiration(expiredDate)
						.signWith(key, SignatureAlgorithm.HS512)
						.compact();

		// Test if the token is expired
		Boolean isExpired = tokenProvider.isTokenExpired(expiredToken);
		assertThat(isExpired).isTrue();
	}

	@Test
	public void testGenerateJwtToken_WithRememberMe() {
		Authentication authentication = createAuthentication();
		boolean rememberMe = true;

		// Generate token
		String token = tokenProvider.generateJwtToken(authentication, rememberMe);
		Date now = new Date();
		Date expectedExpirationDate = new Date(now.getTime() + tokenProvider.getTokenValidityInMillisecondsForRememberMe());

		// Retrieve expiration date from the token
		Date actualExpirationDate = tokenProvider.getExpirationDateFromToken(token);

		// Assert
		long allowedSkew = 1000L; // Allow for a 1-second skew
		assertThat(actualExpirationDate.getTime()).isCloseTo(expectedExpirationDate.getTime(), Offset.offset(allowedSkew));
	}

	// Helper method to generate RSA key pair
	private KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
		KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
		keyPairGenerator.initialize(2048);
		return keyPairGenerator.generateKeyPair();
	}

	// Helper method to create an Authentication
	private Authentication createAuthentication() {
		// Create an Authentication object with mock authorities
		List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
		Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
		return authentication;
	}

	// Helper method to extract key by reflection
	private Key extractKeyFromTokenProvider() throws NoSuchFieldException, IllegalAccessException {
		Field keyField = TokenProvider.class.getDeclaredField("key");
		keyField.setAccessible(true);
		Key key = (Key) keyField.get(tokenProvider);
		return key;
	}
}