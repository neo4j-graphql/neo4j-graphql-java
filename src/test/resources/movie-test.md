## Filter Test TCK


### Schema
```schema

```

### Basic Test

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

### Testing Paging

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

### Testing Projection

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

### Testing Projection with sub-paging

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
