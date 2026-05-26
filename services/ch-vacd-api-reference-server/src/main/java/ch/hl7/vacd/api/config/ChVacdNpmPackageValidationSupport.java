/**
 * Author: Roeland Luykx
 * 
 * Copyright (c) 2026+ by RALY GmbH
 */

package ch.hl7.vacd.api.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.NpmPackage;

import ca.uhn.fhir.context.FhirContext;

/**
 * 
 */
public class ChVacdNpmPackageValidationSupport extends NpmPackageValidationSupport {

	public ChVacdNpmPackageValidationSupport(FhirContext theFhirContext) {
		super(theFhirContext);
	}

	@Override
	public void loadPackageFromClasspath(String theClasspath) throws IOException {
		if (StringUtils.isNotEmpty(theClasspath) && theClasspath.startsWith("file")) {

			String fileName = theClasspath.substring("file:".length());
			try (InputStream is = new FileInputStream(fileName)) {
				NpmPackage pkg = NpmPackage.fromPackage(is);
				if (pkg.getFolders().containsKey("package")) {
					loadResourcesFromPackage(pkg);
					loadBinariesFromPackage(pkg);
				}
			}

		} else {
			super.loadPackageFromClasspath(theClasspath);
		}

	}

	private void loadResourcesFromPackage(NpmPackage thePackage) {
		NpmPackage.NpmPackageFolder packageFolder = thePackage.getFolders().get("package");

		for (String nextFile : packageFolder.listFiles()) {
			if (nextFile.toLowerCase(Locale.US).endsWith(".json")) {
				String input = new String(packageFolder.getContent().get(nextFile), StandardCharsets.UTF_8);
				IBaseResource resource = getFhirContext().newJsonParser().parseResource(input);
				super.addResource(resource);
			}
		}
	}

	private void loadBinariesFromPackage(NpmPackage thePackage) throws IOException {
		List<String> binaries = thePackage.list("other");
		for (String binaryName : binaries) {
			addBinary(thePackage.load("other", binaryName).readAllBytes(), binaryName);
		}
	}
}
