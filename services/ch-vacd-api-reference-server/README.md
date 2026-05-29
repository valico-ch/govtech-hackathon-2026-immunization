# CH VACD API Reference Server

## Intro

This is an implementation of a possible RESTFull FHIR API as described in the [CH-VACD Implementation Guide](https://build.fhir.org/ig/hl7ch/ch-vacd/index.html).
As of a principle of a FHIR Facade Server.

## Basics

### Framework
It is setup as a spring-boot service with resource providers (HAPI FHIR) to server the resources as they will be usefull in the context of the immunization.

### Storage
There is a primitive resource storage defined using an sql database via spring-data/JPA.

### Validation
There is not yet an fhir validation configured.

### OpenAPI/Swagger-UI
There is an openapi/swagger-ui added to have a simple test possibility

### Security
There is no security layer implemented. So everybody can access the API.


## Usage

You can start it with maven 

	./mvnw spring-boot:run

or directly with java 

	java -jar ch-vacd-api-reference-server-1.0.0-SNAPSHOT.jar
	
or use it as Docker Container
	
	see Docker/readme.md


## Credits
Copied from https://github.com/ralych, prepared by Roeland Luykx.
	

	