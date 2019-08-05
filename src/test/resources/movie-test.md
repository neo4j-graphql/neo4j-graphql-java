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
MERGE (actor:Actor {userId:$actorUserId}) SET actor.name = $actorName
WITH actor
RETURN actor { .name } AS actor
```

### Should auto generate `add` relationship mutations for arrays

```graphql
mutation {
  add: addMovieGenres(movieId: 1, genres: ["Action", "Fantasy"]) {
    title
  }
}
```

```params
{"movieId": 1, "genres": ["Action", "Fantasy"]}
```

```cypher
MATCH (from:Movie {movieId:$movieId})
MATCH (to:Genre)
WHERE to.name IN $genres
MERGE (from)-[r:IN_GENRE]->(to)
WITH DISTINCT from
RETURN from { .title } AS movie
```

### Should auto generate `delete` relationship mutations for arrays

```graphql
mutation {
  del: deleteMovieGenres(movieId: 1, genres: ["Action", "Fantasy"]) {
    title
  }
}
```

```params
{"movieId": 1, "genres": ["Action", "Fantasy"]}
```

```cypher
MATCH (from:Movie {movieId:$movieId})
MATCH (to:Genre)
WHERE to.name IN $genres
MATCH (from)-[r:IN_GENRE]->(to)
DELETE r
WITH DISTINCT from
RETURN from { .title } AS movie
```

### Should auto generate `add` relationship mutations

```graphql
mutation {
  add: addMoviePublishedBy(movieId: 1, publishedBy: "Company") {
    title
  }
}
```

```params
{"movieId": 1, "publishedBy": "Company"}
```

```cypher
MATCH (from:Movie {movieId:$movieId})
MATCH (to:Publisher)
WHERE to.name = $publishedBy
MERGE (from)-[r:PUBLISHED_BY]->(to)
WITH DISTINCT from
RETURN from { .title } AS movie
```

### Should auto generate `delete` relationship mutations

```graphql
mutation {
  del: deleteMoviePublishedBy(movieId: 1, publishedBy: "Company") {
    title
  }
}
```

```params
{"movieId": 1, "publishedBy": "Company"}
```

```cypher
MATCH (from:Movie {movieId:$movieId})
MATCH (to:Publisher)
WHERE to.name = $publishedBy
MATCH (from)-[r:PUBLISHED_BY]->(to)
DELETE r
WITH DISTINCT from
RETURN from { .title } AS movie
```

### Should auto generate `add` recursive relationship mutations for arrays

```graphql
mutation {
  add: addUserKnows(userId: 1, knows: [10, 23]) {
    name
  }
}
```

```params
{"userId": 1, "knows": [10, 23]}
```

```cypher
MATCH (from:User {userId:$userId})
MATCH (to:User)
WHERE to.userId IN $knows
MERGE (from)-[r:KNOWS]->(to)
WITH DISTINCT from
RETURN from { .name } AS user
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

## Neo4j Data Types queryies


### User born extraction

```graphql
query {
  User {
    born {
      formatted
      year
    }
  }
}
```

```params
{}
```

```cypher
MATCH (user:User)
RETURN user { born: { formatted: user.born, year: user.born.year } } AS user
```

### User born query filter with multiple fields

```graphql
query {
  User {
    born(formatted: "2015-06-24T12:50:35.556000000+01:00", year: 2015) {
      year
    }
  }
}
```

```params
{"userBornFormatted": "2015-06-24T12:50:35.556000000+01:00", "userBornYear": 2015}
```

```cypher
MATCH (user:User)
WHERE user.born = datetime($userBornFormatted) AND user.born.year = $userBornYear
RETURN user { born: { year: user.born.year } } AS user
```

### Merge Actor with born field formatted

```graphql
mutation {
  actor: mergeActor(userId: "1", name: "Andrea", born: { formatted: "2015-06-24T12:50:35.556000000+01:00" }) {
    name
  }
}
```

```params
{"actorUserId": "1", "actorName": "Andrea", "actorBorn": { "formatted": "2015-06-24T12:50:35.556000000+01:00" }}
```

```cypher
MERGE (actor:Actor {userId:$actorUserId}) SET actor.name = $actorName,actor.born = datetime($actorBorn.formatted)
WITH actor
RETURN actor { .name } AS actor
```


### Create Actor with born field formatted

```graphql
mutation {
  actor: createActor(userId: "1", name: "Andrea", born: { formatted: "2015-06-24T12:50:35.556000000+01:00" }) {
    name
  }
}
```

```params
{"actorUserId": "1", "actorName": "Andrea", "actorBorn": { "formatted": "2015-06-24T12:50:35.556000000+01:00" }}
```

```cypher
CREATE (actor:Actor {userId: $actorUserId, name: $actorName, born: datetime($actorBorn.formatted)})
WITH actor
RETURN actor { .name } AS actor
```

### Merge Actor with born field object

```graphql
mutation {
  actor: mergeActor(userId: "1", name: "Andrea", born: { year: 2018
                                                         month: 11
                                                         day: 23
                                                         hour: 10
                                                         minute: 30
                                                         second: 1
                                                         millisecond: 2
                                                         microsecond: 3
                                                         nanosecond: 4
                                                         timezone: "America/Los_Angeles" }) {
    name
  }
}
```

```params
{"actorUserId": "1", "actorName": "Andrea", "actorBorn": { "year": 2018,
                                                           "month": 11,
                                                           "day": 23,
                                                           "hour": 10,
                                                           "minute": 30,
                                                           "second": 1,
                                                           "millisecond": 2,
                                                           "microsecond": 3,
                                                           "nanosecond": 4,
                                                           "timezone": "America/Los_Angeles" }}
```

```cypher
MERGE (actor:Actor {userId:$actorUserId}) SET actor.name = $actorName,actor.born = datetime($actorBorn)
WITH actor
RETURN actor { .name } AS actor
```

### Create Actor with born field object

```graphql
mutation {
  actor: createActor(userId: "1", name: "Andrea", born: { year: 2018
                                                         month: 11
                                                         day: 23
                                                         hour: 10
                                                         minute: 30
                                                         second: 1
                                                         millisecond: 2
                                                         microsecond: 3
                                                         nanosecond: 4
                                                         timezone: "America/Los_Angeles" }) {
    name
    born {
      year
      month
    }
  }
}
```

```params
{"actorUserId": "1", "actorName": "Andrea", "actorBorn": { "year": 2018,
                                                           "month": 11,
                                                           "day": 23,
                                                           "hour": 10,
                                                           "minute": 30,
                                                           "second": 1,
                                                           "millisecond": 2,
                                                           "microsecond": 3,
                                                           "nanosecond": 4,
                                                           "timezone": "America/Los_Angeles" }}
```

```cypher
CREATE (actor:Actor {userId: $actorUserId, name: $actorName, born: datetime($actorBorn)})
WITH actor
RETURN actor { .name,born: { year: actor.born.year, month: actor.born.month } } AS actor
```