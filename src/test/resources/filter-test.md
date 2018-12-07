## Filter Test TCK

```schema

```

```graphql
{ person(filter: { gender: male }) { name }}
```
```params
{"filterPersonGender":"male"}
```
```cypher
MATCH (person:Person)
WHERE person.gender = $filterPersonGender
RETURN person { .name } AS person
```
```graphql
{ person(filter: { gender_not: male }) { name }}
```
```params
{"filterPersonGender":"male"}
```
```cypher
MATCH (person:Person)
WHERE NOT person.gender = $filterPersonGender
RETURN person { .name } AS person
```
```graphql
{ person(filter: { gender_not_in: [male] }) { name }}
```
```params
{"filterPersonGender":["male"]}
```
```cypher
MATCH (person:Person)
WHERE NOT person.gender IN $filterPersonGender
RETURN person { .name } AS person
```
```graphql
{ person(filter: { gender_in: [male] }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.gender IN ["male"])
RETURN person { .name } AS person
```
```graphql
query filterQuery($name: String) { person(filter: {name : $name}) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.name = {name})
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ person(filter: { company: null }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT (person)-[:WORKS_AT]->())
RETURN person { .name } AS person
```
```graphql
{ person(filter: { company_not: null }) { name }}
```
```cypher
MATCH (person:Person)
WHERE ((person)-[:WORKS_AT]->())
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ person(filter: { id: "jane" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.id = "jane")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { id_starts_with: "ja" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.id STARTS WITH "ja")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { id_not_starts_with: "ja" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.id STARTS WITH "ja")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { id_ends_with: "ne" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.id ENDS WITH "ne")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { id_not_ends_with: "ne" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.id ENDS WITH "ne")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { id_contains: "an" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.id CONTAINS "an")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { id_not_contains: "an" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.id CONTAINS "an")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { id_in: ["jane"] }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.id IN ["jane"])
RETURN person { .name } AS person
```
```graphql
{ person(filter: { id_not_in: ["joe"] }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.id IN ["joe"])
RETURN person { .name } AS person
```
```graphql
{ person(filter: { id_not: "joe" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.id = "joe")
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ person(filter: { OR: [{ AND: [{fun: true},{height:1.75}]},{name_in: ["Jane"]}]  }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (((((person.fun = true) AND (person.height = 1.75))) OR (person.name IN ["Jane"])))
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ person(filter: { age: 38 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.age = 38)
RETURN person { .name } AS person
```
```graphql
{ person(filter: { age_in: [38] }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.age IN [38])
RETURN person { .name } AS person
```
```graphql
{ person(filter: { age_not_in: [38] }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.age IN [38])
RETURN person { .name } AS person
```
```graphql
{ person(filter: { age_lte: 40 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.age <= 40)
RETURN person { .name } AS person
```
```graphql
{ person(filter: { age_lt: 40 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.age < 40)
RETURN person { .name } AS person
```
```graphql
{ person(filter: { age_gt: 40 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.age > 40)
RETURN person { .name } AS person
```
```graphql
{ person(filter: { age_gte: 40 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.age >= 40)
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ person(filter: { name: "Jane" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.name = "Jane")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { name_starts_with: "Ja" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.name STARTS WITH "Ja")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { name_not_starts_with: "Ja" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.name STARTS WITH "Ja")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { name_ends_with: "ne" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.name ENDS WITH "ne")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { name_not_ends_with: "ne" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.name ENDS WITH "ne")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { name_contains: "an" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.name CONTAINS "an")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { name_not_contains: "an" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.name CONTAINS "an")
RETURN person { .name } AS person
```
```graphql
{ person(filter: { name_in: ["Jane"] }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.name IN ["Jane"])
RETURN person { .name } AS person
```
```graphql
{ person(filter: { name_not_in: ["Joe"] }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.name IN ["Joe"])
RETURN person { .name } AS person
```
```graphql
{ person(filter: { name_not: "Joe" }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.name = "Joe")
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ person(filter: { company : { name : "ACME" } }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (ALL(person_Company_Cond IN [(person)-[:WORKS_AT]->(person_Company) | (person_Company.name = "ACME")] WHERE person_Company_Cond))
RETURN person { .name } AS person
```
```graphql
{ person(filter: { company_not : { name : "ACME" } }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT ALL(person_Company_Cond IN [(person)-[:WORKS_AT]->(person_Company) | (person_Company.name = "ACME")] WHERE person_Company_Cond))
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ person(filter: { AND: [{ fun: true, name: "Jane"}]  }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (((person.fun = true AND  person.name = "Jane")))
RETURN person { .name } AS person
```
```graphql
{ person(filter: { AND: [{ fun: true},{name: "Jane"}]  }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (((person.fun = true) AND (person.name = "Jane")))
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ p: Company { employees(filter: { name: "Jane" }) { name }}}
```
```cypher
MATCH (company:Company)
RETURN graphql.labels(company) AS _labels,
[ (company)<-[:WORKS_AT]-(company_employees:Person) WHERE (company_employees.name = "Jane") | company_employees {_labels : graphql.labels(company_employees), .name}] AS employees
```
```graphql
{ p: Company { employees(filter: { OR: [{ name: "Jane" },{name:"Joe"}]}) { name }}}
```
```cypher
MATCH (company:Company)
RETURN graphql.labels(company) AS _labels,
[ (company)<-[:WORKS_AT]-(company_employees:Person) WHERE (((company_employees.name = "Jane") OR (company_employees.name = "Joe"))) | company_employees {_labels : graphql.labels(company_employees), .name}] AS employees
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
query filterQuery($filter: _PersonFilter) { person(filter: $filter) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.name = "Jane")
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ person(filter: { OR: [{ fun: false, name_not: "Jane"}]  }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (((person.fun = false AND NOT person.name = "Jane")))
RETURN person { .name } AS person
```
```graphql
{ person(filter: { OR: [{ fun: true},{name_in: ["Jane"]}]  }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (((person.fun = true) OR (person.name IN ["Jane"])))
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ p: Company(filter: { employees : { name_in : ["Jane","Joe"] } }) { name }}
```
```cypher
MATCH (company:Company)
WHERE (ALL(company_Person_Cond IN [(company)<-[:WORKS_AT]-(company_Person) | (company_Person.name IN ["Jane","Joe"])] WHERE company_Person_Cond))
RETURN graphql.labels(company) AS _labels,
company.name AS name
```
```graphql
{ p: Company(filter: { employees_some : { name : "Jane" } }) { name }}
```
```cypher
MATCH (company:Company)
WHERE (ANY(company_Person_Cond IN [(company)<-[:WORKS_AT]-(company_Person) | (company_Person.name = "Jane")] WHERE company_Person_Cond))
RETURN graphql.labels(company) AS _labels,
company.name AS name
```
```graphql
{ p: Company(filter: { employees_every : { name : "Jill" } }) { name }}
```
```cypher
MATCH (company:Company)
WHERE (ALL(company_Person_Cond IN [(company)<-[:WORKS_AT]-(company_Person) | (company_Person.name = "Jill")] WHERE company_Person_Cond))
RETURN graphql.labels(company) AS _labels,
company.name AS name
```
```graphql
{ p: Company(filter: { employees_some : { name : "Jill" } }) { name }}
```
```cypher
MATCH (company:Company)
WHERE (ANY(company_Person_Cond IN [(company)<-[:WORKS_AT]-(company_Person) | (company_Person.name = "Jill")] WHERE company_Person_Cond))
RETURN graphql.labels(company) AS _labels,
company.name AS name
```
```graphql
{ p: Company(filter: { employees_none : { name : "Jane" } }) { name }}
```
```cypher
MATCH (company:Company)
WHERE (NONE(company_Person_Cond IN [(company)<-[:WORKS_AT]-(company_Person) | (company_Person.name = "Jane")] WHERE company_Person_Cond))
RETURN graphql.labels(company) AS _labels,
company.name AS name
```
```graphql
{ p: Company(filter: { employees_none : { name : "Jill" } }) { name }}
```
```cypher
MATCH (company:Company)
WHERE (NONE(company_Person_Cond IN [(company)<-[:WORKS_AT]-(company_Person) | (company_Person.name = "Jill")] WHERE company_Person_Cond))
RETURN graphql.labels(company) AS _labels,
company.name AS name
```
```graphql
{ p: Company(filter: { employees_single : { name : "Jill" } }) { name }}
```
```cypher
MATCH (company:Company)
WHERE (SINGLE(company_Person_Cond IN [(company)<-[:WORKS_AT]-(company_Person) | (company_Person.name = "Jill")] WHERE company_Person_Cond))
RETURN graphql.labels(company) AS _labels,
company.name AS name
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ person(filter: { fun: true }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.fun = true)
RETURN person { .name } AS person
```
```graphql
{ person(filter: { fun_not: true }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.fun = true)
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
query filterQuery($filter: _PersonFilter) { person(filter: $filter) { name }}
```
```cypher
MATCH (person:Person)
WHERE (((person.name = "Jane" AND  ALL(person_Company_Cond IN [(person)-[:WORKS_AT]->(person_Company) | (person_Company.name ENDS WITH "ME")] WHERE person_Company_Cond))))
RETURN person { .name } AS person
```
class graphql.language.EnumTypeDefinition
class graphql.language.EnumTypeDefinition
```graphql
{ person(filter: { height: 1.75 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.height = 1.75)
RETURN person { .name } AS person
```
```graphql
{ person(filter: { height_not: 1.75 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.height = 1.75)
RETURN person { .name } AS person
```
```graphql
{ person(filter: { height_in: [1.75] }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.height IN [1.75])
RETURN person { .name } AS person
```
```graphql
{ person(filter: { height_not_in: [1.75] }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (NOT person.height IN [1.75])
RETURN person { .name } AS person
```
```graphql
{ person(filter: { height_lte: 1.80 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.height <= 1.8)
RETURN person { .name } AS person
```
```graphql
{ person(filter: { height_lt: 1.80 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.height < 1.8)
RETURN person { .name } AS person
```
```graphql
{ person(filter: { height_gte: 1.80 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.height >= 1.8)
RETURN person { .name } AS person
```
```graphql
{ person(filter: { height_gt: 1.80 }) { name }}
```
```cypher
MATCH (person:Person)
WHERE (person.height > 1.8)
RETURN person { .name } AS person
```
