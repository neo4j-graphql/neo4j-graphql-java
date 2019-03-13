## Filter Test TCK


### Schema
```schema

```

### Basic Query with Parameter

```graphql
{  Movie(title: "River Runs Through It, A")  {  title }  }
```
```params
{"movieTitle":"River Runs Through It, A"}
```
```cypher
MATCH (movie:Movie)
WHERE  movie.title = $movieTitle  
RETURN movie { .title } AS movie
```

### Paging

```graphql
{
  Movie("title" to  "River Runs Through It, A", first: 1, offset: 0) {
    title
    year
  }
}
```

```params
{"movieTitle": "River Runs Through It, A", "first": 1, "offset": 0}
```

```cypher
MATCH (movie:Movie) 
WHERE movie.title = $movieTitle 
RETURN movie { .title , .year } AS movie 
SKIP $offset LIMIT $first
```

### Relationship Expansion

```graphql
{
    Movie(title: "River Runs Through It, A") {
      title
      actors {
        name
      }
    }
  }
```

```params
{"movieTitle": "River Runs Through It, A"}
```

```cypher
MATCH (movie:Movie)  
WHERE movie.title = $movieTitle 
RETURN movie { .title,actors:[(movie)<-[:ACTED_IN]-(movieActors:Actor) | movieActors { .name }] } AS movie 
```

### Projection with sub-paging

```graphql
{
    Movie(title: "River Runs Through It, A") {
      title
      actors(first:3) {
        name
      }
    }
  }
```
#### todo ,"actorsFirst":3 
```params
{"movieTitle": "River Runs Through It, A"}
```

```cypher
MATCH (movie:Movie)  
WHERE movie.title = $movieTitle 
RETURN movie { .title,actors:[(movie)<-[:ACTED_IN]-(movieActors:Actor) | movieActors { .name }][0..3] } AS movie 
```
### Subquery Cypher Directive

```graphql
{
    Movie {
      title
      similar {
        title
      }
    }
  }
```

```params
{}
```

```cypher
MATCH (movie:Movie)  
RETURN movie { .title,similar:[movieSimilar IN apoc.cypher.runFirstColumnMany('WITH $this AS this,$first AS first,$offset AS offset MATCH (this)--(:Genre)--(o:Movie) RETURN o',{this:movie,first:$movieFirst,offset:$movieOffset}) | movieSimilar { .title }] } AS movie 
```
