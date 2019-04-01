## Cypher Directive Test 

```schema

```

### Simple Cypher Directive on Field

```graphql
{ person { name }}
```
```params
{}
```
```cypher
MATCH (person:Person) 
RETURN person { name:apoc.cypher.runFirstColumnSingle('WITH $this AS this RETURN this.name',{this:person}) } AS person
```



```graphql
{ person { name }}
```
```params
{}
```
```cypher
MATCH (person:Person) 
RETURN person { name:apoc.cypher.runFirstColumnSingle('WITH $this AS this RETURN this.name',{this:person}) } AS person
```
