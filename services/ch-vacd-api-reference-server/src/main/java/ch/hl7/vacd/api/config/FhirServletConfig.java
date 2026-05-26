package ch.hl7.vacd.api.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.openapi.OpenApiInterceptor;
import ca.uhn.fhir.rest.server.IResourceProvider;
import jakarta.servlet.Servlet;

import org.hl7.fhir.common.hapi.validation.support.CachingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.List;

@Configuration
public class FhirServletConfig {
	
	private Logger loggger = LoggerFactory.getLogger(FhirServletConfig.class);

	@Value("${fhir.providers:}")
	private List<String> resourceProviderClassNames;
	
	@Value(value = "${fhir.ig.files}")
	private List<String> igFiles;

	@Bean
	FhirContext fhirContext() {
		FhirContext ctx = FhirContext.forR4();
		addNpmPackageValidationSupport(ctx);

		return ctx;
	}

	private void addNpmPackageValidationSupport(FhirContext ctx) {
		// Create a support chain including the NPM Package Support
		ValidationSupportChain validationSupportChain = new ValidationSupportChain();

		ChVacdNpmPackageValidationSupport npmPackageSupport = new ChVacdNpmPackageValidationSupport(ctx);
		igFiles.forEach(file -> {
//    				loggger.info("ig file: " + file);
			try {
				// load the npm package of the ig
				npmPackageSupport.loadPackageFromClasspath(file);
//    					validationSupportChain.addValidationSupport(npmPackageSupport);
			} catch (IOException e) {
				loggger.error("Error loading IG from package for validation (" + file + ")", e);
			}
		});
		validationSupportChain.addValidationSupport(new DefaultProfileValidationSupport(ctx));

		IValidationSupport validationSupport = new CachingValidationSupport(validationSupportChain);
		ctx.setValidationSupport(validationSupport);
	}

	@Bean
	public ServletRegistrationBean<Servlet> fhirServlet(FhirContext fhirContext,
			java.util.Collection<IResourceProvider> providers) {
		RestfulServer server = new RestfulServer(fhirContext);
		// Register all discovered resource providers
		if (resourceProviderClassNames.isEmpty()) {
			server.setResourceProviders(providers);
		} else {
			List<IResourceProvider> filteredProviders = new java.util.ArrayList<>();
			providers.forEach(provider -> {
				String providerName = provider.getClass().getSimpleName();
				if (resourceProviderClassNames.contains(providerName)) {
					filteredProviders.add(provider);
				}
			});
			server.setResourceProviders(filteredProviders);
		}

		// Try to register the HAPI OpenAPI interceptor if present on the classpath
		try {
			OpenApiInterceptor openApiInterceptor = new OpenApiInterceptor();
			server.registerInterceptor(openApiInterceptor);
		} catch (Exception ignored) {
			// ignore - openapi support is optional
			System.out
					.println("OpenAPI interceptor not registered - OpenAPI support is not available on the classpath.");
		}
		ServletRegistrationBean<Servlet> registration = new ServletRegistrationBean<>(server, "/fhir/*");
		registration.setName("FhirServlet");
		return registration;
	}
}