## Filter Test TCK


### Schema
```schema

```

### Top Level Query

```graphql
query {
  Movie {
    movieId
  }
}
```

```params
{}
```

```cypher
MATCH (movie:Movie)
RETURN movie { .movieId } AS movie
```

### Query Single Object

```graphql
  {
    MovieById(movieId: "18") {
      title
    }
  }
```

```params
{"movieByIdMovieId":"18"}
```

```cypher
MATCH (movieById:Movie)
WHERE movieById.movieId = $movieByIdMovieId
RETURN movieById { .title } AS movieById
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
  Movie(title: "River Runs Through It, A", first: 1, offset: 1) {
    title
    year
  }
}
```

```params
{"movieTitle": "River Runs Through It, A"}
```


```cypher
MATCH (movie:Movie) 
WHERE movie.title = $movieTitle 
RETURN movie { .title,.year } AS movie 
SKIP 1 LIMIT 1
```
### Query Single Relation

```graphql
{
      MovieById(movieId: "3100") {
        title
        filmedIn {
          name
        }
      }
}
```

```params
{"movieByIdMovieId": "3100"}
```


```cypher
MATCH (movieById:Movie) 
WHERE movieById.movieId = $movieByIdMovieId 
RETURN movieById { .title,filmedIn:[(movieById)-[:FILMED_IN]->(movieByIdFilmedIn:State) | movieByIdFilmedIn { .name }][0] } AS movieById
```

### Query Single Object and Array of Object Relations

```graphql
{
      MovieById(movieId: "3100") {
        title
        actors {
          name
        }
        filmedIn{
          name
        }
      }
    }
```

```params
{"movieByIdMovieId": "3100"}
```


```cypher
MATCH (movieById:Movie) 
WHERE movieById.movieId = $movieByIdMovieId 
RETURN movieById { .title,actors:[(movieById)<-[:ACTED_IN]-(movieByIdActors:Actor) | movieByIdActors { .name }],filmedIn:[(movieById)-[:FILMED_IN]->(movieByIdFilmedIn:State) | movieByIdFilmedIn { .name }][0] } AS movieById
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
RETURN movie { .title,similar:[movieSimilar IN 
apoc.cypher.runFirstColumnMany('WITH $this AS this,$first AS first,$offset AS offset MATCH (this)--(:Genre)--(o:Movie) RETURN o',{this:movie,first:$movieFirst,offset:$movieOffset}) 
| movieSimilar { .title }] } AS movie 
```

### Subquery Cypher Directive Paging

```graphql
{
    Movie {
      title
      similar(first:3) {
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
RETURN movie { .title,similar:[movieSimilar IN 
apoc.cypher.runFirstColumnMany('WITH $this AS this,$first AS first,$offset AS offset MATCH (this)--(:Genre)--(o:Movie) RETURN o',{this:movie,first:$movieFirst,offset:$movieOffset}) 
| movieSimilar { .title }][0..3] } AS movie 
```

### Handle Cypher Directive on Query Type

```graphql
  {
  GenresBySubstring(substring:"Action") {
    name
    movies(first: 3) {
      title
    }
  }
}
```

```params
{"genresBySubstringSubstring":"Action"}
```

```cypher
UNWIND apoc.cypher.runFirstColumnMany('WITH $substring AS substring MATCH (g:Genre) WHERE toLower(g.name) CONTAINS toLower($substring) RETURN g',{substring:$genresBySubstringSubstring}) AS genresBySubstring
RETURN genresBySubstring { .name,movies:[(genresBySubstring)<-[:IN_GENRE]-(genresBySubstringMovies:Movie) | genresBySubstringMovies { .title }][0..3] } AS genresBySubstring
```

### Handle Cypher directive on Mutation type

```graphql
mutation someMutation {
  createGenre(name: "Wildlife Documentary") {
    name
  }
}
```

```params
{"createGenreName":"Wildlife Documentary"}
```

```cypher
CALL apoc.cypher.doIt('WITH $name AS name CREATE (g:Genre) SET g.name = name RETURN g',{name:$createGenreName}) YIELD value
WITH value[head(keys(value))] AS createGenre
RETURN createGenre { .name } AS createGenre
```

### Query using Inline Fragment

```graphql
  {
    Movie(title: "River Runs Through It, A") {
      title
      ratings {
        rating
        from {
          ... on User {
            name
            userId
          }
        }
      }
    }
  }
```

```params
{"movieTitle":"River Runs Through It, A"}
```

```cypher
MATCH (movie:Movie)
WHERE movie.title = $movieTitle
RETURN movie { .title,ratings:[(movie)<-[movieRatings:RATED]-(movieRatingsFrom:User) | 
  movieRatings { .rating,from:movieRatingsFrom { .name,.userId } }] } AS movie
```

### Deeply nested object query

```graphql
{ Movie(title: "River Runs Through It, A") {
		title
    actors {
      name
      movies {
        title
        actors(name: "Tom Hanks") {
          name
          movies {
            title
            year
            similar(first: 3) {
              title
              year
            }
          }
        }
      }
    }
  }
}
```

```params
{"movieTitle":  "River Runs Through It, A", "movieActorsMoviesActorsName": "Tom Hanks", "movieActorsMoviesActorsMoviesFirst": 3, "movieActorsMoviesActorsMoviesOffset" : 0 }
```

```cypher
MATCH (movie:Movie) 
WHERE movie.title = $movieTitle 
RETURN movie { .title,actors:[(movie)<-[:ACTED_IN]-(movieActors:Actor) | 
       movieActors { .name,movies:[(movieActors)-[:ACTED_IN]->(movieActorsMovies:Movie) | 
         movieActorsMovies { .title,actors:[(movieActorsMovies)<-[:ACTED_IN]-(movieActorsMoviesActors:Actor) 
           WHERE movieActorsMoviesActors.name = $movieActorsMoviesActorsName | 
             movieActorsMoviesActors { .name,movies:[(movieActorsMoviesActors)-[:ACTED_IN]->(movieActorsMoviesActorsMovies:Movie) | 
               movieActorsMoviesActorsMovies { .title,.year,similar:[movieActorsMoviesActorsMoviesSimilar 
                 IN apoc.cypher.runFirstColumnMany('WITH $this AS this,$first AS first,$offset AS offset MATCH (this)--(:Genre)--(o:Movie) RETURN o',{this:movieActorsMoviesActorsMovies,first:$movieActorsMoviesActorsMoviesFirst,offset:$movieActorsMoviesActorsMoviesOffset}) | 
                   movieActorsMoviesActorsMoviesSimilar { .title,.year }][0..3] }] }] }] }] } AS movie
```

### Should merge an actor by the userId

```graphql
mutation {
  actor: mergeActor(userId: "1", name: "Andrea") {
    name
  }
}
```

```params
{"actorUserId": "1", "actorName": "Andrea"}
```

```cypher
MERGE (actor:Actor {userId:$actorUserId}) SET actor.name=$actorName
WITH actor
RETURN actor { .name } AS actor
```

## Order By

### Descending, top level

```graphql
{ Movie(year: 2010, orderBy:title_desc, first: 10) {
      title
    }
}
```

```params
{"movieYear":  2010 }
```

```cypher
MATCH (movie:Movie) 
WHERE movie.year = $movieYear 
RETURN movie { .title } AS movie 
ORDER BY movie.title DESC  
LIMIT 10
```
