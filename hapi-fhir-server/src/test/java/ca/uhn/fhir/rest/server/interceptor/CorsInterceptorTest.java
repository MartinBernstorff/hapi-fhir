package ca.uhn.fhir.rest.server.interceptor;

import ca.uhn.fhir.rest.api.Constants;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CorsInterceptorTest {

	private static final Logger ourLog = LoggerFactory.getLogger(CorsInterceptorTest.class);

	@Test
	public void testCustomCorsConfig() {
		CorsConfiguration corsConfiguration = new CorsConfiguration();
		corsConfiguration.applyPermitDefaultValues();
		corsConfiguration.setAllowedMethods(Collections.singletonList("*"));
		corsConfiguration.setExposedHeaders(Arrays.asList(
			Constants.HEADER_CONTENT_LOCATION,
			Constants.HEADER_DATE,
			Constants.HEADER_ETAG,
			Constants.HEADER_LOCATION,
			Constants.HEADER_REQUEST_ID,
			Constants.HEADER_X_CORRELATION_ID
		));
		CorsInterceptor corsInterceptor = new CorsInterceptor(corsConfiguration);

		assertThat(corsInterceptor.getConfig()).isSameAs(corsConfiguration);
		assertNull(corsConfiguration.getAllowCredentials());
		assertNotNull(corsConfiguration.getAllowedHeaders());
		assertNotNull(corsConfiguration.getAllowedMethods());
		assertNotNull(corsConfiguration.getAllowedOrigins());
		assertNotNull(corsConfiguration.getExposedHeaders());
		assertEquals(Long.valueOf(1800L), corsConfiguration.getMaxAge());
		assertNotNull(corsConfiguration.checkHeaders(Collections.singletonList("Content-Type")));
		assertNotNull(corsConfiguration.checkHeaders(Collections.singletonList("Authorization")));
		assertNotNull(corsConfiguration.checkHeaders(Arrays.asList("Authorization", "Content-Type")));
		assertNotNull(corsConfiguration.checkHttpMethod(HttpMethod.GET));
		assertNotNull(corsConfiguration.checkOrigin("http://clinfhir.com"));

		ourLog.info("Custom CorsConfiguration:  allowCredentials = {};  allowedHeaders = {};  " +
			"allowedMethods = {};  allowedOrigins = {};  exposedHeaders = {};  maxAge = {}",
			corsConfiguration.getAllowCredentials(),
			Arrays.toString(corsConfiguration.getAllowedHeaders().toArray()),
			Arrays.toString(corsConfiguration.getAllowedMethods().toArray()),
			Arrays.toString(corsConfiguration.getAllowedOrigins().toArray()),
			Arrays.toString(corsConfiguration.getExposedHeaders().toArray()),
			corsConfiguration.getMaxAge());
	}

	@Test
	public void testDefaultCorsConfig() {
		CorsInterceptor corsInterceptor = new CorsInterceptor();
		CorsConfiguration corsConfiguration = corsInterceptor.getConfig();

		assertNull(corsConfiguration.getAllowCredentials());
		assertNotNull(corsConfiguration.getAllowedHeaders());
		assertNotNull(corsConfiguration.getAllowedMethods());
		assertNotNull(corsConfiguration.getAllowedOrigins());
		assertNotNull(corsConfiguration.getExposedHeaders());
		assertNull(corsConfiguration.getMaxAge());
		assertNotNull(corsConfiguration.checkHeaders(Collections.singletonList("Content-Type")));
//		assertNotNull(corsConfiguration.checkHeaders(Arrays.asList(new String[] {"Authorization"})));
		assertNotNull(corsConfiguration.checkHeaders(Arrays.asList("Authorization", "Content-Type")));
		assertNotNull(corsConfiguration.checkHttpMethod(HttpMethod.GET));
		assertNotNull(corsConfiguration.checkOrigin("http://clinfhir.com"));

		ourLog.info("Default CorsConfiguration:  allowCredentials = {};  allowedHeaders = {};  " +
			"allowedMethods = {};  allowedOrigins = {};  exposedHeaders = {};  maxAge = {}",
			corsConfiguration.getAllowCredentials(),
			Arrays.toString(corsConfiguration.getAllowedHeaders().toArray()),
			Arrays.toString(corsConfiguration.getAllowedMethods().toArray()),
			Arrays.toString(corsConfiguration.getAllowedOrigins().toArray()),
			Arrays.toString(corsConfiguration.getExposedHeaders().toArray()),
			corsConfiguration.getMaxAge());
	}
}
