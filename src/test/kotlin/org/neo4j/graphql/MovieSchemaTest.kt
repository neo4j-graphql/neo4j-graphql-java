package demo.org.neo4j.graphql

import org.junit.Assert.assertEquals
import org.junit.Test
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.Translator
import java.io.InputStreamReader


class MovieSchemaTest {

    val schema = InputStreamReader(javaClass.getResourceAsStream("/movies-test-schema.graphql")).readText()

    fun testTranslation(graphQLQuery: String, expectedCypherQuery: String, params: Map<String, Any> = emptyMap()) {
        val query = Translator(SchemaBuilder.buildSchema(schema)).translate(graphQLQuery, emptyMap(), context = Translator.Context(topLevelWhere = false)).first()
        assertEquals(expectedCypherQuery.normalizeWhitespace(), query.query.normalizeWhitespace())
    }

    fun String.normalizeWhitespace() = this.replace("\\s+".toRegex(), " ")

    @Test
    fun `testsimple  Cypher  query`() {
        val graphQLQuery = """{  Movie(title: "River  Runs  Through  It,  A")  {  title }  }"""
        val expectedCypherQuery = """MATCH  (movie:Movie {title:${"$"}movieTitle})  RETURN  movie  {  .title  } AS  movie"""

        testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
                "title" to "River Runs Through It, A",
                "first" to -1,
                "offset" to 0
        ))
    }

    @Test
    fun testOrderBy() {
        val graphQLQuery = """{  Movie(orderBy:year_desc, first:10)  {  title }  }"""
        val expectedCypherQuery = """MATCH  (movie:Movie) RETURN  movie  {  .title  } AS  movie ORDER BY movie.year DESC LIMIT 10"""

        val params = mapOf(
                "first" to 10
        )
        val query = Translator(SchemaBuilder.buildSchema(schema)).translate(graphQLQuery).first()
        assertEquals(expectedCypherQuery.normalizeWhitespace(), query.query.normalizeWhitespace())
    }

    @Test
    fun testTck() {
        TckTest(schema).testTck("movie-test.md", 0, true)
    }

/*
fun `testHandle Query with name not aligning to type`() {
  val graphQLQuery = """{
  MoviesByYear(year: 2010) {
    title
  }
}
  """
val expectedCypherQuery =
      """MATCH (movie:Movie {year:${"$"}year}) RETURN movie { .title } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      year: 2010,
      "first" to -1,
      "offset" to 0
    ))
}


fun `testDeeply nested object query`() {
  val graphQLQuery = """
 {
  Movie(title: "River Runs Through It, A") {
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
}"""
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) RETURN movie { .title ,actors: [(movie)<-[:ACTED_IN]-(movie_actors:Actor) | movie_actors { .name ,movies: [(movie_actors)-[:ACTED_IN]->(movie_actors_movies:Movie) | movie_actors_movies { .title ,actors: [(movie_actors_movies)<-[:ACTED_IN]-(movie_actors_movies_actors:Actor{name:${"$"}1_name}) | movie_actors_movies_actors { .name ,movies: [(movie_actors_movies_actors)-[:ACTED_IN]->(movie_actors_movies_actors_movies:Movie) | movie_actors_movies_actors_movies { .title , .year ,similar: [ movie_actors_movies_actors_movies_similar IN apoc.cypher.runFirstColumn("WITH {this} AS this MATCH (this)--(:Genre)--(o:Movie) RETURN o", mapOf(this: movie_actors_movies_actors_movies, first: 3, offset: 0}, true) | movie_actors_movies_actors_movies_similar { .title , .year }][..3] }] }] }] }] } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "title" to  "River Runs Through It, A",
      """1_name": "Tom Hanks",
      """2_first": 3,
      "first" to -1,
      "offset" to 0
    ))
}

fun `testHandle meta field at beginning of selection set`() {
  val graphQLQuery = """
  {
    Movie(title:"River Runs Through It, A"){
      __typename
      title
    }
  }"""
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) RETURN movie { .title } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "title" to  "River Runs Through It, A",
      "first" to -1,
      "offset" to 0
    ))
}

fun `testHandle meta field at end of selection set`() {
  val graphQLQuery = """
  {
    Movie(title:"River Runs Through It, A"){
      title
      __typename
    }
  }
  """
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) RETURN movie { .title } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "title" to  "River Runs Through It, A",
      "first" to -1,
      "offset" to 0
    ))
}

fun `testHandle meta field in middle of selection set`() {
  val graphQLQuery = """
  {
    Movie(title:"River Runs Through It, A"){
      title
      __typename
      year
    }
  }
  """
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) RETURN movie { .title , .year } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "title" to  "River Runs Through It, A",
      "first" to -1,
      "offset" to 0
    ))
}

fun `testHandle @cypher directive without any params for sub-query`() {
  val graphQLQuery = """{
    Movie(title: "River Runs Through It, A") {
      mostSimilar {
        title
        year
      }
    }

  }"""
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) RETURN movie {mostSimilar: head([ movie_mostSimilar IN apoc.cypher.runFirstColumn("WITH {this} AS this RETURN this", mapOf(this: movie}, true) | movie_mostSimilar { .title , .year }]) } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "title" to  "River Runs Through It, A",
      "first" to -1,
      "offset" to 0
    ))
}

fun `testPass @cypher directive default params to sub-query`() {
  val graphQLQuery = """{
    Movie(title: "River Runs Through It, A") {
      scaleRating
    }

  }"""
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) RETURN movie {scaleRating: apoc.cypher.runFirstColumn("WITH ${"$"}this AS this RETURN ${"$"}scale * this.imdbRating", mapOf(this: movie, scale: 3}, false)} AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "first" to -1,
      offset: 0,
      title: "River Runs Through It, A"
    ))
}

fun `testPass @cypher directive params to sub-query`() {
  val graphQLQuery = """{
    Movie(title: "River Runs Through It, A") {
      scaleRating(scale: 10)
    }

  }"""
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) RETURN movie {scaleRating: apoc.cypher.runFirstColumn("WITH ${"$"}this AS this RETURN ${"$"}scale * this.imdbRating", mapOf(this: movie, scale: 10}, false)} AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "first" to -1,
      offset: 0,
      "title" to  "River Runs Through It, A",
      """1_scale": 10
    ))
}

fun `testQuery for Neo4js internal _id`() {
  val graphQLQuery = """{
    Movie(_id: "0") {
      title
      year
    }

  }"""
val expectedCypherQuery = """MATCH (movie:Movie {}) WHERE ID(movie)=0 RETURN movie { .title , .year } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "first" to -1,
      "offset" to 0
    ))
}

fun `testQuery for Neo4js internal _id and another param before _id`() {
  val graphQLQuery = """{
    Movie("title" to  "River Runs Through It, A", _id: "0") {
      title
      year
    }

  }"""
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) WHERE ID(movie)=0 RETURN movie { .title , .year } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "title" to  "River Runs Through It, A",
      "first" to -1,
      "offset" to 0
    ))
}

fun `testQuery for Neo4js internal _id and another param after _id`() {
  val graphQLQuery = """{
    Movie(_id: "0", year: 2010) {
      title
      year
    }

  }"""
val expectedCypherQuery = """MATCH (movie:Movie {year:${"$"}year}) WHERE ID(movie)=0 RETURN movie { .title , .year } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "first" to -1,
      offset: 0,
      year: 2010
    ))
}

fun `testQuery for Neo4js internal _id by dedicated Query MovieBy_Id(_id: String!)`() {
  val graphQLQuery = """{
    MovieBy_Id(_id: "0") {
      title
      year
    }

  }"""
val expectedCypherQuery = """MATCH (movie:Movie {}) WHERE ID(movie)=0 RETURN movie { .title , .year } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "first" to -1,
      "offset" to 0
    ))
}

test("""Query for null value translates to "IS NULL" WHERE clause""", t => {
  val graphQLQuery = """{
    Movie(poster: null) {
      title
      year
    }
  }"""
val expectedCypherQuery = """MATCH (movie:Movie {}) WHERE movie.poster IS NULL RETURN movie { .title , .year } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "first" to -1,
      "offset" to 0
    ))
}

test("""Query for null value combined with internal ID and another param""", t => {
  val graphQLQuery = """{
      Movie(poster: null, _id: "0", year: 2010) {
        title
        year
      }
    }"""
val expectedCypherQuery = """MATCH (movie:Movie {year:${"$"}year}) WHERE ID(movie)=0 AND movie.poster IS NULL RETURN movie { .title , .year } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      year: 2010,
      "first" to -1,
      "offset" to 0
    ))
}

fun `testCypher subquery filters`() {
  val graphQLQuery = """
  {
    Movie(title: "River Runs Through It, A") {
        title
        actors(name: "Tom Hanks") {
          name
        }
        similar(first: 3) {
          title
        }
      }
    }"""
val expectedCypherQuery =
      """MATCH (movie:Movie {title:${"$"}title}) RETURN movie { .title ,actors: [(movie)<-[:ACTED_IN]-(movie_actors:Actor{name:${"$"}1_name}) | movie_actors { .name }] ,similar: [ movie_similar IN apoc.cypher.runFirstColumn("WITH {this} AS this MATCH (this)--(:Genre)--(o:Movie) RETURN o", mapOf(this: movie, first: 3, offset: 0}, true) | movie_similar { .title }][..3] } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "title" to  "River Runs Through It, A",
      "first" to -1,
      offset: 0,
      """1_name": "Tom Hanks",
      """3_first": 3
    ))
}

fun `testCypher subquery filters with paging`() {
  val graphQLQuery = """
  {
    Movie(title: "River Runs Through It, A") {
        title
        actors(name: "Tom Hanks", first: 3) {
          name
        }
        similar(first: 3) {
          title
        }
      }
    }"""
val expectedCypherQuery =
      """MATCH (movie:Movie {title:${"$"}title}) RETURN movie { .title ,actors: [(movie)<-[:ACTED_IN]-(movie_actors:Actor{name:${"$"}1_name}) | movie_actors { .name }][..3] ,similar: [ movie_similar IN apoc.cypher.runFirstColumn("WITH {this} AS this MATCH (this)--(:Genre)--(o:Movie) RETURN o", mapOf(this: movie, first: 3, offset: 0}, true) | movie_similar { .title }][..3] } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "title" to  "River Runs Through It, A",
      "first" to -1,
      offset: 0,
      """1_first": 3,
      """1_name": "Tom Hanks",
      """3_first": 3
    ))
}

fun `testHandle @cypher directive on Query Type`() {
  val graphQLQuery = """
  {
  GenresBySubstring(substring:"Action") {
    name
    movies(first: 3) {
      title
    }
  }
}
  """

test.cb("Create node mutation`() {
  val graphQLQuery = """	mutation someMutation {
  	CreateMovie(movieId: "12dd334d5", title:"My Super Awesome Movie", year:2018, plot:"An unending saga", poster:"www.movieposter.com/img.png", imdbRating: 1.0) {
			_id
      title
      genres {
        name
      }
    }
  }"""
val expectedCypherQuery = """CREATE (movie:Movie) SET movie = ${"$"}params RETURN movie {_id: ID(movie), .title ,genres: [(movie)-[:IN_GENRE]->(movie_genres:Genre) | movie_genres { .name }] } AS movie""";

  t.plan(2);
  testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
    params: {
      movieId: "12dd334d5",
      title: "My Super Awesome Movie",
      year: 2018,
      plot: "An unending saga",
      poster: "www.movieposter.com/img.png",
      imdbRating: 1.0
    },
    "first" to -1,
    "offset" to 0
  }
}

test.cb("Update node mutation`() {
  val graphQLQuery = """mutation updateMutation {
    UpdateMovie(movieId: "12dd334d5", year: 2010) {
      _id
      title
      year
    }
  }"""
val expectedCypherQuery = """MATCH (movie:Movie {movieId: ${"$"}params.movieId}) SET movie += ${"$"}params RETURN movie {_id: ID(movie), .title , .year } AS movie""";

  t.plan(2);
  testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
    params: {
      movieId: "12dd334d5",
      year: 2010
    },
    "first" to -1,
    "offset" to 0
  }
}

test.cb("Delete node mutation`() {
  val graphQLQuery = """mutation deleteMutation{
      DeleteMovie(movieId: "12dd334d5") {
        _id
        movieId
      }
    }"""
val expectedCypherQuery = """MATCH (movie:Movie {movieId: ${"$"}movieId})
WITH movie AS movie_toDelete, movie {_id: ID(movie), .movieId } AS movie
DETACH DELETE movie_toDelete
RETURN movie""";

  t.plan(2);
  testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
    movieId: "12dd334d5",
    "first" to -1,
    "offset" to 0
  }
}

fun `testAdd relationship mutation`() {
  val graphQLQuery = """mutation someMutation {
    AddMovieGenres(
      from: { movieId: "123" },
      to: { name: "Action" }
    ) {
      from {
        movieId
        genres {
          _id
          name
        }
      }
      to {
        name
      }
    }
  }"""
val expectedCypherQuery = """
      MATCH (movie_from:Movie {movieId: ${"$"}from.movieId})
      MATCH (genre_to:Genre {name: ${"$"}to.name})
      CREATE (movie_from)-[in_genre_relation:IN_GENRE]->(genre_to)
      RETURN in_genre_relation { from: movie_from { .movieId ,genres: [(movie_from)-[:IN_GENRE]->(movie_from_genres:Genre) | movie_from_genres {_id: ID(movie_from_genres), .name }] } ,to: genre_to { .name }  } AS _AddMovieGenresPayload;
    """;

  t.plan(1);
  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {
      from: { movieId: "123" },
      to: { name: "Action" },
      "first" to -1,
      "offset" to 0
    }
val expectedCypherQuery
  );
}

fun `testAdd relationship mutation with GraphQL variables`() {
  val graphQLQuery = """mutation someMutation(${"$"}from: _MovieInput!) {
    AddMovieGenres(
      from: ${"$"}from,
      to: { name: "Action" }
    ) {
      from {
        movieId
        genres {
          _id
          name
        }
      }
      to {
        name
      }
    }
  }"""
val expectedCypherQuery = """
      MATCH (movie_from:Movie {movieId: ${"$"}from.movieId})
      MATCH (genre_to:Genre {name: ${"$"}to.name})
      CREATE (movie_from)-[in_genre_relation:IN_GENRE]->(genre_to)
      RETURN in_genre_relation { from: movie_from { .movieId ,genres: [(movie_from)-[:IN_GENRE]->(movie_from_genres:Genre) | movie_from_genres {_id: ID(movie_from_genres), .name }] } ,to: genre_to { .name }  } AS _AddMovieGenresPayload;
    """;

  t.plan(1);
  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {
      from: { movieId: "123" },
      to: { name: "Action" },
      "first" to -1,
      "offset" to 0
    }
val expectedCypherQuery
  );
}

fun `testAdd relationship mutation with relationship property`() {
  val graphQLQuery = """mutation someMutation {
    AddUserRated(
      from: {
        userId: "123"
      },
      to: {
        movieId: "456"
      },
      data: {
        rating: 5
      }
    ) {
      from {
        _id
        userId
        name
        rated {
          rating
          Movie {
            _id
            movieId
            title
          }
        }
      }
      to {
        _id
        movieId
        title 
        ratings {
          rating
          User {
            _id
            userId
            name
          }
        }
      }
      rating
    }
  }"""
val expectedCypherQuery = """
      MATCH (user_from:User {userId: ${"$"}from.userId})
      MATCH (movie_to:Movie {movieId: ${"$"}to.movieId})
      CREATE (user_from)-[rated_relation:RATED {rating:${"$"}data.rating}]->(movie_to)
      RETURN rated_relation { from: user_from {_id: ID(user_from), .userId , .name ,rated: [(user_from)-[user_from_rated_relation:RATED]->(:Movie) | user_from_rated_relation { .rating ,Movie: head([(:User)-[user_from_rated_relation]->(user_from_rated_Movie:Movie) | user_from_rated_Movie {_id: ID(user_from_rated_Movie), .movieId , .title }]) }] } ,to: movie_to {_id: ID(movie_to), .movieId , .title ,ratings: [(movie_to)<-[movie_to_ratings_relation:RATED]-(:User) | movie_to_ratings_relation { .rating ,User: head([(:Movie)<-[movie_to_ratings_relation]-(movie_to_ratings_User:User) | movie_to_ratings_User {_id: ID(movie_to_ratings_User), .userId , .name }]) }] } , .rating  } AS _AddUserRatedPayload;
    """;

  t.plan(1);
  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {
      from: { userId: "123" },
      to: { movieId: "456" },
      data: { rating: 5 },
      "first" to -1,
      "offset" to 0
    }
val expectedCypherQuery
  );
}

fun `testAdd relationship mutation with relationship property (reflexive)`() {
  val graphQLQuery = """mutation {
    AddUserFriends(
      from: {
        userId: "123"
      },
      to: {
        userId: "456"
      },
      data: {
        since: 7
      }
    ) {
      from {
        _id
        userId
        name
        friends {
          from {
            since
            User {
              _id
              name
              friends {
                from {
                  since
                  User {
                    _id
                    name
                  }
                }
                to {
                  since
                  User {
                    _id
                    name
                  }
                }
              }
            }
          }
          to {
            since
            User {
              _id
              name
            }
          }
        }
      }
      to {
        _id
        name
        friends {
          from {
            since
            User {
              _id
              name
            }
          }
          to {
            since
            User {
              _id
              name
            }
          }
        }
      }
      since
    }
  }
  """
val expectedCypherQuery = """
      MATCH (user_from:User {userId: ${"$"}from.userId})
      MATCH (user_to:User {userId: ${"$"}to.userId})
      CREATE (user_from)-[friend_of_relation:FRIEND_OF {since:${"$"}data.since}]->(user_to)
      RETURN friend_of_relation { from: user_from {_id: ID(user_from), .userId , .name ,friends: {from: [(user_from)<-[user_from_from_relation:FRIEND_OF]-(user_from_from:User) | user_from_from_relation { .since ,User: user_from_from {_id: ID(user_from_from), .name ,friends: {from: [(user_from_from)<-[user_from_from_from_relation:FRIEND_OF]-(user_from_from_from:User) | user_from_from_from_relation { .since ,User: user_from_from_from {_id: ID(user_from_from_from), .name } }] ,to: [(user_from_from)-[user_from_from_to_relation:FRIEND_OF]->(user_from_from_to:User) | user_from_from_to_relation { .since ,User: user_from_from_to {_id: ID(user_from_from_to), .name } }] } } }] ,to: [(user_from)-[user_from_to_relation:FRIEND_OF]->(user_from_to:User) | user_from_to_relation { .since ,User: user_from_to {_id: ID(user_from_to), .name } }] } } ,to: user_to {_id: ID(user_to), .name ,friends: {from: [(user_to)<-[user_to_from_relation:FRIEND_OF]-(user_to_from:User) | user_to_from_relation { .since ,User: user_to_from {_id: ID(user_to_from), .name } }] ,to: [(user_to)-[user_to_to_relation:FRIEND_OF]->(user_to_to:User) | user_to_to_relation { .since ,User: user_to_to {_id: ID(user_to_to), .name } }] } } , .since  } AS _AddUserFriendsPayload;
    """;

  t.plan(1);
  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {
      from: { userId: "123" },
      to: { userId: "456" },
      data: { since: 7 },
      "first" to -1,
      "offset" to 0
    }
val expectedCypherQuery
  );
}

fun `testRemove relationship mutation`() {
  val graphQLQuery = """mutation someMutation {
    RemoveMovieGenres(
      from: { movieId: "123" },
      to: { name: "Action" }
    ) {
      from {
        _id
        title
      }
      to {
        _id
        name
      }
    }
  }"""
val expectedCypherQuery = """
      MATCH (movie_from:Movie {movieId: ${"$"}from.movieId})
      MATCH (genre_to:Genre {name: ${"$"}to.name})
      OPTIONAL MATCH (movie_from)-[movie_fromgenre_to:IN_GENRE]->(genre_to)
      DELETE movie_fromgenre_to
      WITH COUNT(*) AS scope, movie_from AS _movie_from, genre_to AS _genre_to
      RETURN {from: _movie_from {_id: ID(_movie_from), .title } ,to: _genre_to {_id: ID(_genre_to), .name } } AS _RemoveMovieGenresPayload;
    """;

  t.plan(1);
  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {
      from: { movieId: "123" },
      to: { name: "Action" },
      "first" to -1,
      "offset" to 0
    }
val expectedCypherQuery
  );
}

fun `testRemove relationship mutation (reflexive)`() {
  val graphQLQuery = """mutation {
    RemoveUserFriends(
      from: {
        userId: "123"
      },
      to: {
        userId: "456"
      },
    ) {
      from {
        _id
        name
        friends {
          from {
            since
            User {
              _id
              name
            }
          }
          to {
            since
            User {
              _id
              name
            }
          }
        }
      }
      to {
        _id
        name
        friends {
          from {
            since
            User {
              _id
              name
            }
          }
          to {
            since
            User {
              _id
              name
            }
          }
        }      
      }
    }
  }
  """
val expectedCypherQuery = """
      MATCH (user_from:User {userId: ${"$"}from.userId})
      MATCH (user_to:User {userId: ${"$"}to.userId})
      OPTIONAL MATCH (user_from)-[user_fromuser_to:FRIEND_OF]->(user_to)
      DELETE user_fromuser_to
      WITH COUNT(*) AS scope, user_from AS _user_from, user_to AS _user_to
      RETURN {from: _user_from {_id: ID(_user_from), .name ,friends: {from: [(_user_from)<-[_user_from_from_relation:FRIEND_OF]-(_user_from_from:User) | _user_from_from_relation { .since ,User: _user_from_from {_id: ID(_user_from_from), .name } }] ,to: [(_user_from)-[_user_from_to_relation:FRIEND_OF]->(_user_from_to:User) | _user_from_to_relation { .since ,User: _user_from_to {_id: ID(_user_from_to), .name } }] } } ,to: _user_to {_id: ID(_user_to), .name ,friends: {from: [(_user_to)<-[_user_to_from_relation:FRIEND_OF]-(_user_to_from:User) | _user_to_from_relation { .since ,User: _user_to_from {_id: ID(_user_to_from), .name } }] ,to: [(_user_to)-[_user_to_to_relation:FRIEND_OF]->(_user_to_to:User) | _user_to_to_relation { .since ,User: _user_to_to {_id: ID(_user_to_to), .name } }] } } } AS _RemoveUserFriendsPayload;
    """;

  t.plan(1);
  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {
      from: { userId: "123" },
      to: { userId: "456" },
      "first" to -1,
      "offset" to 0
    }
val expectedCypherQuery
  );
}

fun `testHandle GraphQL variables in nested selection - first/offset`() {
  val graphQLQuery = """query (${"$"}year: Int!, ${"$"}first: Int!) {

  Movie(year: ${"$"}year) {
    title
    year
    similar(first: ${"$"}first) {
      title
    }
  }
}"""
val expectedCypherQuery = """MATCH (movie:Movie {year:${"$"}year}) RETURN movie { .title , .year ,similar: [ movie_similar IN apoc.cypher.runFirstColumn("WITH {this} AS this MATCH (this)--(:Genre)--(o:Movie) RETURN o", mapOf(this: movie, first: 3, offset: 0}, true) | movie_similar { .title }][..3] } AS movie SKIP ${"$"}offset""";


    cypherTestRunner(
      t,
      graphQLQuery,
      { year: 2016, first: 3 },
      expectedCypherQuery,
      {
        """1_first": 3,
        year: 2016,
        "first" to -1,
        "offset" to 0
      }
    ),
    augmentedSchemaCypherTestRunner(
      t,
      graphQLQuery,
      { year: 2016, first: 3 },
      expectedCypherQuery
    )
}

fun `testHandle GraphQL variables in nest selection - @cypher param (not first/offset)`() {
  val graphQLQuery = """query (${"$"}year: Int = 2016, ${"$"}first: Int = 2, ${"$"}scale:Int) {

  Movie(year: ${"$"}year) {
    title
    year
    similar(first: ${"$"}first) {
      title
      scaleRating(scale:${"$"}scale)
    }

  }
}"""
val expectedCypherQuery = """MATCH (movie:Movie {year:${"$"}year}) RETURN movie { .title , .year ,similar: [ movie_similar IN apoc.cypher.runFirstColumn("WITH {this} AS this MATCH (this)--(:Genre)--(o:Movie) RETURN o", mapOf(this: movie, first: 3, offset: 0}, true) | movie_similar { .title ,scaleRating: apoc.cypher.runFirstColumn("WITH ${"$"}this AS this RETURN ${"$"}scale * this.imdbRating", mapOf(this: movie_similar, scale: 5}, false)}][..3] } AS movie SKIP ${"$"}offset""";

    cypherTestRunner(
      t,
      graphQLQuery,
      { year: 2016, first: 3, scale: 5 },
      expectedCypherQuery,
      {
        year: 2016,
        "first" to -1,
        offset: 0,
        """1_first": 3,
        """2_scale": 5
      }
    ),
    augmentedSchemaCypherTestRunner(
      t,
      graphQLQuery,
      { year: 2016, first: 3, scale: 5 },
      expectedCypherQuery
    )
}

fun `testReturn internal node id for _id field`() {
  val graphQLQuery = """{
  Movie(year: 2016) {
    _id
    title
    year
    genres {
      _id
      name
    }
  }
}
"""
val expectedCypherQuery = """MATCH (movie:Movie {year:${"$"}year}) RETURN movie {_id: ID(movie), .title , .year ,genres: [(movie)-[:IN_GENRE]->(movie_genres:Genre) | movie_genres {_id: ID(movie_genres), .name }] } AS movie SKIP ${"$"}offset""";


    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      year: 2016,
      "first" to -1,
      "offset" to 0
    ))
}

fun `testTreat enum as a scalar`() {
  val graphQLQuery = """
  {
    Books {
      genre
    }
  }"""
val expectedCypherQuery = """MATCH (book:Book {}) RETURN book { .genre } AS book SKIP ${"$"}offset""";


    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "first" to -1,
      "offset" to 0
    ))
}

fun `testHandle query fragment`() {
  val graphQLQuery = """
fragment myTitle on Movie {
  title
  actors {
    name
  }
}

query getMovie {
  Movie(title: "River Runs Through It, A") {
    ...myTitle
    year
  }
}"""
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) RETURN movie { .title ,actors: [(movie)<-[:ACTED_IN]-(movie_actors:Actor) | movie_actors { .name }] , .year } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "title" to  "River Runs Through It, A",
      "first" to -1,
      "offset" to 0
    ))
}

fun `testHandle multiple query fragments`() {
  val graphQLQuery = """
    fragment myTitle on Movie {
  title
}

fragment myActors on Movie {
  actors {
    name
  }
}

query getMovie {
  Movie(title: "River Runs Through It, A") {
    ...myTitle
    ...myActors
    year
  }
}
  """
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) RETURN movie { .title ,actors: [(movie)<-[:ACTED_IN]-(movie_actors:Actor) | movie_actors { .name }] , .year } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      "title" to  "River Runs Through It, A",
      "first" to -1,
      "offset" to 0
    ))
}

fun `testnested fragments`() {
  val graphQLQuery = """
    query movieItems {
      Movie(year:2010) {
        ...Foo
      }
    }
    
    fragment Foo on Movie {
      title
      ...Bar
    }
    
    fragment Bar on Movie {
      year
    }"""
val expectedCypherQuery = """MATCH (movie:Movie {year:${"$"}year}) RETURN movie { .title , .year } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      year: 2010,
      "first" to -1,
      "offset" to 0
    ))
}

fun `testfragments on relations`() {
  val graphQLQuery = """
    query movieItems {
      Movie(year:2010) {
        title
        actors {
          ...Foo
        }
      }
    }
    
    fragment Foo on Actor {
      name
    }"""
val expectedCypherQuery = """MATCH (movie:Movie {year:${"$"}year}) RETURN movie { .title ,actors: [(movie)<-[:ACTED_IN]-(movie_actors:Actor) | movie_actors { .name }] } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      year: 2010,
      "first" to -1,
      "offset" to 0
    ))
}

fun `testnested fragments on relations`() {
  val graphQLQuery = """
    query movieItems {
      Movie(year:2010) {
        ...Foo
      }
    }
    
    fragment Foo on Movie {
      title
      actors {
        ...Bar
      }
    }
    
    fragment Bar on Actor {
      name
    }"""
val expectedCypherQuery = """MATCH (movie:Movie {year:${"$"}year}) RETURN movie { .title ,actors: [(movie)<-[:ACTED_IN]-(movie_actors:Actor) | movie_actors { .name }] } AS movie SKIP ${"$"}offset""";

    testTranslation(graphQLQuery, expectedCypherQuery, mapOf(
      year: 2010,
      "first" to -1,
      "offset" to 0
    ))
}

fun `testorderBy test - descending, top level - augmented schema`() {
  val graphQLQuery = """{
    Movie(year: 2010, orderBy:title_desc, first: 10) {
      title
      actors(first:3) {
        name
      }
    }
  }
  """
val expectedCypherQuery = """MATCH (movie:Movie {year:${"$"}year}) RETURN movie { .title ,actors: [(movie)<-[:ACTED_IN]-(movie_actors:Actor{}) | movie_actors { .name }][..3] } AS movie ORDER BY movie.title DESC  SKIP ${"$"}offset LIMIT ${"$"}first""";

  t.plan(1);

  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {}
val expectedCypherQuery,
    {
      offset: 0,
      first: 10,
      year: 2010,
      """1_first": 3
    }
  );
}

fun `testquery for relationship properties`() {
  val graphQLQuery = """{
    Movie(title: "River Runs Through It, A") {
       title
      ratings {
        rating
        User {
          name
        }
      }
    }
  }"""
val expectedCypherQuery = """MATCH (movie:Movie {title:${"$"}title}) RETURN movie { .title ,ratings: [(movie)<-[movie_ratings_relation:RATED]-(:User) | movie_ratings_relation { .rating ,User: head([(:Movie)<-[movie_ratings_relation]-(movie_ratings_User:User) | movie_ratings_User { .name }]) }] } AS movie SKIP ${"$"}offset""";

  t.plan(1);

  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {}
val expectedCypherQuery,
    {}
  );
}

fun `testquery reflexive relation nested in non-reflexive relation`() {
  val graphQLQuery = """query {
    Movie {
      movieId
      title
      ratings {
        rating
        User {
          userId
          name
          friends {
            from {
              since
              User {
                name
                friends {
                  from {
                    since
                    User {
                      name
                    }
                  }
                  to {
                    since
                    User {
                      name
                    }
                  }
                }
              }
            }
            to {
              since
              User {
                name
                friends {
                  from {
                    since
                    User {
                      name
                    }
                  }
                  to {
                    since
                    User {
                      name
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }"""
val expectedCypherQuery = """MATCH (movie:Movie {}) RETURN movie { .movieId , .title ,ratings: [(movie)<-[movie_ratings_relation:RATED]-(:User) | movie_ratings_relation { .rating ,User: head([(:Movie)<-[movie_ratings_relation]-(movie_ratings_User:User) | movie_ratings_User { .userId , .name ,friends: {from: [(movie_ratings_User)<-[movie_ratings_User_from_relation:FRIEND_OF]-(movie_ratings_User_from:User) | movie_ratings_User_from_relation { .since ,User: movie_ratings_User_from { .name ,friends: {from: [(movie_ratings_User_from)<-[movie_ratings_User_from_from_relation:FRIEND_OF]-(movie_ratings_User_from_from:User) | movie_ratings_User_from_from_relation { .since ,User: movie_ratings_User_from_from { .name } }] ,to: [(movie_ratings_User_from)-[movie_ratings_User_from_to_relation:FRIEND_OF]->(movie_ratings_User_from_to:User) | movie_ratings_User_from_to_relation { .since ,User: movie_ratings_User_from_to { .name } }] } } }] ,to: [(movie_ratings_User)-[movie_ratings_User_to_relation:FRIEND_OF]->(movie_ratings_User_to:User) | movie_ratings_User_to_relation { .since ,User: movie_ratings_User_to { .name ,friends: {from: [(movie_ratings_User_to)<-[movie_ratings_User_to_from_relation:FRIEND_OF]-(movie_ratings_User_to_from:User) | movie_ratings_User_to_from_relation { .since ,User: movie_ratings_User_to_from { .name } }] ,to: [(movie_ratings_User_to)-[movie_ratings_User_to_to_relation:FRIEND_OF]->(movie_ratings_User_to_to:User) | movie_ratings_User_to_to_relation { .since ,User: movie_ratings_User_to_to { .name } }] } } }] } }]) }] } AS movie SKIP ${"$"}offset""";

  t.plan(1);

  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {}
val expectedCypherQuery,
    {}
  );
}

fun `testquery non-reflexive relation nested in reflexive relation`() {
  val graphQLQuery = """query {
    User {
      _id
      name
      friends {
        from {
          since
          User {
            _id
            name
            rated {
              rating
              Movie {
                _id
                ratings {
                  rating 
                  User {
                    _id
                    friends {
                      from {
                        since
                        User {
                          _id
                        }
                      }
                      to {
                        since 
                        User {
                          _id
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
        to {
          since
          User {
            _id
            name
            rated {
              rating
              Movie {
                _id
              }
            }
          }
        }
      }
    }
  }"""
val expectedCypherQuery = """MATCH (user:User {}) RETURN user {_id: ID(user), .name ,friends: {from: [(user)<-[user_from_relation:FRIEND_OF]-(user_from:User) | user_from_relation { .since ,User: user_from {_id: ID(user_from), .name ,rated: [(user_from)-[user_from_rated_relation:RATED]->(:Movie) | user_from_rated_relation { .rating ,Movie: head([(:User)-[user_from_rated_relation]->(user_from_rated_Movie:Movie) | user_from_rated_Movie {_id: ID(user_from_rated_Movie),ratings: [(user_from_rated_Movie)<-[user_from_rated_Movie_ratings_relation:RATED]-(:User) | user_from_rated_Movie_ratings_relation { .rating ,User: head([(:Movie)<-[user_from_rated_Movie_ratings_relation]-(user_from_rated_Movie_ratings_User:User) | user_from_rated_Movie_ratings_User {_id: ID(user_from_rated_Movie_ratings_User),friends: {from: [(user_from_rated_Movie_ratings_User)<-[user_from_rated_Movie_ratings_User_from_relation:FRIEND_OF]-(user_from_rated_Movie_ratings_User_from:User) | user_from_rated_Movie_ratings_User_from_relation { .since ,User: user_from_rated_Movie_ratings_User_from {_id: ID(user_from_rated_Movie_ratings_User_from)} }] ,to: [(user_from_rated_Movie_ratings_User)-[user_from_rated_Movie_ratings_User_to_relation:FRIEND_OF]->(user_from_rated_Movie_ratings_User_to:User) | user_from_rated_Movie_ratings_User_to_relation { .since ,User: user_from_rated_Movie_ratings_User_to {_id: ID(user_from_rated_Movie_ratings_User_to)} }] } }]) }] }]) }] } }] ,to: [(user)-[user_to_relation:FRIEND_OF]->(user_to:User) | user_to_relation { .since ,User: user_to {_id: ID(user_to), .name ,rated: [(user_to)-[user_to_rated_relation:RATED]->(:Movie) | user_to_rated_relation { .rating ,Movie: head([(:User)-[user_to_rated_relation]->(user_to_rated_Movie:Movie) | user_to_rated_Movie {_id: ID(user_to_rated_Movie)}]) }] } }] } } AS user SKIP ${"$"}offset""";

  t.plan(1);

  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {}
val expectedCypherQuery,
    {}
  );
}

fun `testquery relation type with argument`() {
  val graphQLQuery = """query {
    User {
      _id
      name
      rated(rating: 5) {
        rating
        Movie {
          title
        }
      }
    }
  }"""
val expectedCypherQuery = """MATCH (user:User {}) RETURN user {_id: ID(user), .name ,rated: [(user)-[user_rated_relation:RATED{rating:${"$"}1_rating}]->(:Movie) | user_rated_relation { .rating ,Movie: head([(:User)-[user_rated_relation]->(user_rated_Movie:Movie) | user_rated_Movie { .title }]) }] } AS user SKIP ${"$"}offset""";

  t.plan(1);

  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {}
val expectedCypherQuery,
    {}
  );
}

fun `testquery reflexive relation type with arguments`() {
  val graphQLQuery = """query {
    User {
      userId
      name
      friends {
        from(since: 3) {
          since
          User {
            name
          }
        }
        to(since: 5) {
          since
          User {
            name
          }
        }
      }
    }
  }
  """
val expectedCypherQuery = """MATCH (user:User {}) RETURN user { .userId , .name ,friends: {from: [(user)<-[user_from_relation:FRIEND_OF{since:${"$"}1_since}]-(user_from:User) | user_from_relation { .since ,User: user_from { .name } }] ,to: [(user)-[user_to_relation:FRIEND_OF{since:${"$"}3_since}]->(user_to:User) | user_to_relation { .since ,User: user_to { .name } }] } } AS user SKIP ${"$"}offset""";

  t.plan(1);

  return augmentedSchemaCypherTestRunner(
    t,
    graphQLQuery,
    {}
val expectedCypherQuery,
    {}
  );
}


 */

}