# Vaccination 

## ValueSet
HTML
	https://fhir.ch/ig/ch-term/3.3.0/ValueSet-ch-vacd-swissmedic-vaccines-vs.html
JSON
	https://fhir.ch/ig/ch-term/3.3.0/ValueSet-ch-vacd-swissmedic-vaccines-vs.json

## CodeSystem
HTML
	https://fhir.ch/ig/ch-term/3.3.0/CodeSystem-ch-vacd-swissmedic-cs.html
JSON
	https://fhir.ch/ig/ch-term/3.3.0/CodeSystem-ch-vacd-swissmedic-cs.

# Terminology Server

https://tx.fhir.ch

## CodeSystem
Form
	https://tx.fhir.ch/r4/CodeSystem
	
Direct Call
	https://tx.fhir.ch/r4/CodeSystem?url=http%3A%2F%2Ffhir.ch%2Fig%2Fch-vacd%2FCodeSystem%2Fch-vacd-swissmedic-cs&version=&name=&title=&status=&publisher=&description=&identifier=&jurisdiction=&date=&content-mode=&supplements=&system=&_sort=&_elements=id&_elements=url

Direct Call as JSON
	https://tx.fhir.ch/r4/CodeSystem?url=http%3A%2F%2Ffhir.ch%2Fig%2Fch-vacd%2FCodeSystem%2Fch-vacd-swissmedic-cs&version=&name=&title=&status=&publisher=&description=&identifier=&jurisdiction=&date=&content-mode=&supplements=&system=&_sort=&_elements=id&_elements=url&_format=json
	

## ValueSet
https://tx.fhir.ch/r4/ValueSet


## Translate VaccineCode to targetDisease:

1 vaccine code (683, FSME-Immun 0.25 ml Junior) to 1 target disease (712986001, Central European encephalitis (disorder))
	https://tx.fhir.ch/r4/ConceptMap/$translate?url=http://fhir.ch/ig/ch-vacd/ConceptMap/ch-vacd-vaccines-targetdiseases-cm&system=http://fhir.ch/ig/ch-vacd/CodeSystem/ch-vacd-swissmedic-cs&code=683&_format=json

1 vaccine code (681, Boostrix Polio) to 4 target disease (398102009, Acute poliomyelitis (disorder);76902006, Tetanus (disorder);27836007, Pertussis (disorder);397430003, Diphtheria caused by Corynebacterium diphtheriae (disorder))
	https://tx.fhir.ch/r4/ConceptMap/$translate?url=http://fhir.ch/ig/ch-vacd/ConceptMap/ch-vacd-vaccines-targetdiseases-cm&system=http://fhir.ch/ig/ch-vacd/CodeSystem/ch-vacd-swissmedic-cs&code=681&_format=json

